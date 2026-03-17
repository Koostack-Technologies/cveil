use reqwest::Client;
use tokio::time::{Duration, sleep};
use tracing::{debug, error, info, warn};

use crate::actor::{SdkHandle, SdkRequest};
use crate::config::DaemonConfig;
use crate::graph;

/// Run the SSE invalidation listener loop.
/// This function runs forever until the shutdown signal fires.
pub async fn run_sse_loop(
    config: DaemonConfig,
    sdk: SdkHandle,
    http: Client,
    mut shutdown_rx: tokio::sync::watch::Receiver<bool>,
) {
    let base_url = config.api_base_url.trim_end_matches('/').to_string();
    let url = format!("{base_url}/api/v1/consent/events");
    let mut attempt: u32 = 0;

    loop {
        // Check for shutdown before each connection attempt
        if *shutdown_rx.borrow() {
            info!("SSE loop exiting — shutdown signaled");
            return;
        }

        info!(attempt = attempt + 1, "Connecting to SSE endpoint...");

        match connect_sse(&url, &config.api_token, &sdk, &http, &config, &mut shutdown_rx).await {
            Ok(()) => {
                // Clean disconnect (e.g. shutdown)
                info!("SSE connection closed cleanly");
                return;
            }
            Err(e) => {
                warn!(error = %e, "SSE connection lost");
                let _ = sdk.send(SdkRequest::SseDisconnected).await;
            }
        }

        attempt += 1;

        // Exponential backoff: 1s → 2s → 4s → ... → 30s, with jitter
        let base_delay = Duration::from_secs(1) * 2u32.saturating_pow(attempt.min(5));
        let capped = base_delay.min(Duration::from_secs(30));
        let jitter = Duration::from_millis(rand_u64() % 1000);
        let delay = capped + jitter;

        info!(delay_ms = delay.as_millis(), "Reconnecting after backoff...");

        tokio::select! {
            _ = sleep(delay) => {},
            _ = shutdown_rx.wait_for(|&v| v) => {
                info!("SSE loop exiting during backoff — shutdown signaled");
                return;
            }
        }

        // Signal reconnected — this flushes the local cache
        let _ = sdk.send(SdkRequest::SseReconnected).await;

        // Reload the full graph after reconnect (we may have missed events)
        if let Err(e) = graph::load_full_graph(&config, &sdk, &http).await {
            error!(error = %e, "Failed to reload graph after SSE reconnect");
        }

        // Reset attempt counter on successful graph reload
        attempt = 0;
    }
}

/// Connect to the SSE endpoint, process events until disconnect or shutdown.
async fn connect_sse(
    url: &str,
    token: &str,
    sdk: &SdkHandle,
    http: &Client,
    config: &DaemonConfig,
    shutdown_rx: &mut tokio::sync::watch::Receiver<bool>,
) -> Result<(), String> {
    let resp = http
        .get(url)
        .bearer_auth(token)
        .header("Accept", "text/event-stream")
        .send()
        .await
        .map_err(|e| format!("SSE connect failed: {e}"))?;

    if !resp.status().is_success() {
        return Err(format!("SSE returned HTTP {}", resp.status()));
    }

    let _ = sdk.send(SdkRequest::SseConnected).await;
    info!("SSE connected");

    // Read the response as chunks — we collect into a channel to avoid Send issues
    let (chunk_tx, mut chunk_rx) = tokio::sync::mpsc::channel::<Result<bytes::Bytes, String>>(64);

    // Spawn chunk reader in a separate task that owns the response
    tokio::spawn(async move {
        use futures_util::StreamExt;
        let mut stream = resp.bytes_stream();
        while let Some(result) = stream.next().await {
            let mapped = result.map_err(|e| e.to_string());
            if chunk_tx.send(mapped).await.is_err() {
                break;
            }
        }
    });

    let mut buffer = String::new();

    loop {
        // Collect events to process outside select! to avoid Send issues
        enum Action {
            Process(bytes::Bytes),
            StreamError(String),
            StreamEnd,
            Shutdown,
        }

        let action = tokio::select! {
            chunk = chunk_rx.recv() => {
                match chunk {
                    Some(Ok(bytes)) => Action::Process(bytes),
                    Some(Err(e)) => Action::StreamError(e),
                    None => Action::StreamEnd,
                }
            }
            _ = shutdown_rx.wait_for(|&v| v) => Action::Shutdown,
        };

        match action {
            Action::Process(bytes) => {
                buffer.push_str(&String::from_utf8_lossy(&bytes));
                while let Some(event) = extract_sse_event(&mut buffer) {
                    if let Some(data) = event.data {
                        handle_sse_event(&event.event_type, &data, sdk, http, config).await;
                    }
                }
            }
            Action::StreamError(e) => return Err(format!("SSE stream error: {e}")),
            Action::StreamEnd => return Err("SSE stream ended".into()),
            Action::Shutdown => return Ok(()),
        }
    }
}

/// A parsed SSE event.
struct SseEvent {
    event_type: String,
    data: Option<String>,
}

/// Extract one complete SSE event from the buffer (terminated by blank line).
fn extract_sse_event(buffer: &mut String) -> Option<SseEvent> {
    // SSE events are separated by \n\n
    let sep = buffer.find("\n\n")?;
    let raw = buffer[..sep].to_string();
    *buffer = buffer[sep + 2..].to_string();

    let mut event_type = String::from("message");
    let mut data_lines: Vec<String> = Vec::new();

    for line in raw.lines() {
        if let Some(value) = line.strip_prefix("event:") {
            event_type = value.trim().to_string();
        } else if let Some(value) = line.strip_prefix("data:") {
            data_lines.push(value.trim().to_string());
        } else if line.starts_with(':') {
            // Comment — ignore
        }
    }

    let data = if data_lines.is_empty() {
        None
    } else {
        Some(data_lines.join("\n"))
    };

    Some(SseEvent { event_type, data })
}

/// Handle a single SSE event.
async fn handle_sse_event(
    event_type: &str,
    data: &str,
    sdk: &SdkHandle,
    http: &Client,
    config: &DaemonConfig,
) {
    if event_type != "cache-invalidation" {
        debug!(event_type, "Ignoring non-invalidation SSE event");
        return;
    }

    match sdk
        .send(SdkRequest::ProcessInvalidation {
            event_json: data.to_string(),
        })
        .await
    {
        Ok(result_json) => {
            let parsed: serde_json::Value =
                serde_json::from_str(&result_json).unwrap_or_default();
            let effect = parsed["effect"].as_str().unwrap_or("unknown");

            match effect {
                "refresh_full_graph" | "flush_and_reload" => {
                    info!(effect, "Invalidation requires full graph reload");
                    if let Err(e) = graph::load_full_graph(config, sdk, http).await {
                        error!(error = %e, "Failed to reload graph after invalidation");
                    }
                }
                "refresh_subgraph" => {
                    let node_type = parsed["nodeType"].as_str().unwrap_or("");
                    let node_name = parsed["nodeName"].as_str().unwrap_or("");
                    info!(node_type, node_name, "Invalidation requires subgraph refresh");
                    if let Err(e) =
                        graph::load_subgraph(config, sdk, http, node_type, node_name).await
                    {
                        error!(error = %e, "Failed to reload subgraph after invalidation");
                    }
                }
                "none" | "duplicate" => {
                    debug!(effect, "Invalidation — no action needed");
                }
                other => {
                    warn!(effect = other, "Unknown invalidation effect");
                }
            }
        }
        Err(e) => {
            error!(error = %e, "Failed to process invalidation event");
        }
    }
}

/// Simple pseudo-random u64 (no external crate needed).
fn rand_u64() -> u64 {
    use std::collections::hash_map::RandomState;
    use std::hash::{BuildHasher, Hasher};
    let s = RandomState::new();
    let mut h = s.build_hasher();
    h.write_u8(0);
    h.finish()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_single_sse_event() {
        let mut buf = "event: cache-invalidation\ndata: {\"id\":\"1\"}\n\n".to_string();
        let evt = extract_sse_event(&mut buf).unwrap();
        assert_eq!(evt.event_type, "cache-invalidation");
        assert_eq!(evt.data.unwrap(), r#"{"id":"1"}"#);
        assert!(buf.is_empty());
    }

    #[test]
    fn parse_multi_line_data() {
        let mut buf = "event: cache-invalidation\ndata: line1\ndata: line2\n\n".to_string();
        let evt = extract_sse_event(&mut buf).unwrap();
        assert_eq!(evt.data.unwrap(), "line1\nline2");
    }

    #[test]
    fn parse_multiple_events() {
        let mut buf =
            "event: ping\ndata: {}\n\nevent: cache-invalidation\ndata: {\"x\":1}\n\n".to_string();
        let e1 = extract_sse_event(&mut buf).unwrap();
        assert_eq!(e1.event_type, "ping");
        let e2 = extract_sse_event(&mut buf).unwrap();
        assert_eq!(e2.event_type, "cache-invalidation");
        assert!(buf.is_empty());
    }

    #[test]
    fn incomplete_event_returns_none() {
        let mut buf = "event: cache-invalidation\ndata: partial".to_string();
        assert!(extract_sse_event(&mut buf).is_none());
        // Buffer should be unchanged
        assert!(buf.contains("partial"));
    }

    #[test]
    fn comments_are_ignored() {
        let mut buf = ": keep-alive\nevent: test\ndata: ok\n\n".to_string();
        let evt = extract_sse_event(&mut buf).unwrap();
        assert_eq!(evt.event_type, "test");
        assert_eq!(evt.data.unwrap(), "ok");
    }
}
