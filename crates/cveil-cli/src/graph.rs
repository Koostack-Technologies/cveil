use reqwest::Client;
use tracing::{debug, info, warn};

use crate::actor::{SdkHandle, SdkRequest};
use crate::config::DaemonConfig;

/// Fetch the full consent graph via paginated requests and ingest into the SDK.
pub async fn load_full_graph(
    config: &DaemonConfig,
    sdk: &SdkHandle,
    http: &Client,
) -> Result<u64, String> {
    let mut cursor = String::new();
    let mut total_nodes: u64 = 0;
    let limit = 100;
    let mut page_num = 0u32;

    info!("Loading consent graph...");

    loop {
        page_num += 1;
        let mut url = format!(
            "{}/api/v1/consent/graph?limit={limit}",
            config.api_base_url.trim_end_matches('/')
        );
        if !cursor.is_empty() {
            url.push_str(&format!("&cursor={cursor}"));
        }

        debug!(page = page_num, url = %url, "Fetching graph page");

        let resp = http
            .get(&url)
            .bearer_auth(&config.api_token)
            .send()
            .await
            .map_err(|e| format!("Graph fetch failed: {e}"))?;

        if !resp.status().is_success() {
            return Err(format!(
                "Graph fetch returned HTTP {}: {}",
                resp.status(),
                resp.text().await.unwrap_or_default()
            ));
        }

        let body = resp.text().await.map_err(|e| format!("Failed to read graph response: {e}"))?;

        let result = sdk
            .send(SdkRequest::IngestGraphPage {
                page_json: body.clone(),
            })
            .await?;

        let parsed: serde_json::Value =
            serde_json::from_str(&result).map_err(|e| format!("Bad ingest response: {e}"))?;

        total_nodes = parsed["totalNodes"].as_u64().unwrap_or(total_nodes);
        let has_more = parsed["hasMore"].as_bool().unwrap_or(false);

        debug!(page = page_num, total_nodes, has_more, "Graph page ingested");

        if !has_more {
            break;
        }

        // Extract cursor from the response body for the next page
        let page_body: serde_json::Value = serde_json::from_str(&body).unwrap_or_default();
        cursor = page_body["pageInfo"]["endCursor"]
            .as_str()
            .unwrap_or("")
            .to_string();

        if cursor.is_empty() {
            warn!("Graph API returned hasMore=true but no endCursor — stopping pagination");
            break;
        }
    }

    info!(pages = page_num, total_nodes, "Consent graph loaded");
    Ok(total_nodes)
}

/// Fetch a partial subgraph (for invalidation-triggered refresh).
pub async fn load_subgraph(
    config: &DaemonConfig,
    sdk: &SdkHandle,
    http: &Client,
    root_type: &str,
    root_name: &str,
) -> Result<(), String> {
    let url = format!(
        "{}/api/v1/consent/graph?rootType={root_type}&rootName={root_name}&depth=3&limit=100",
        config.api_base_url.trim_end_matches('/')
    );

    debug!(root_type, root_name, "Fetching subgraph");

    let resp = http
        .get(&url)
        .bearer_auth(&config.api_token)
        .send()
        .await
        .map_err(|e| format!("Subgraph fetch failed: {e}"))?;

    if !resp.status().is_success() {
        return Err(format!(
            "Subgraph fetch returned HTTP {}: {}",
            resp.status(),
            resp.text().await.unwrap_or_default()
        ));
    }

    let body = resp.text().await.map_err(|e| e.to_string())?;
    sdk.send(SdkRequest::IngestGraphPage { page_json: body }).await?;

    info!(root_type, root_name, "Subgraph refreshed");
    Ok(())
}
