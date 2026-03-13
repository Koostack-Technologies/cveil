use reqwest::Client;
use tokio::signal;
use tracing::{error, info};

use crate::actor::{self, SdkRequest};
use crate::cli::StartArgs;
use crate::config::DaemonConfig;
use crate::graph;
use crate::server;
use crate::sse;

/// Main daemon entry point — called by `cveil start`.
pub async fn run_daemon(start: StartArgs) -> i32 {
    // 1. Load and validate config
    let config = match DaemonConfig::load(&start) {
        Ok(c) => c,
        Err(e) => {
            eprintln!("Configuration error: {e}");
            return 1;
        }
    };

    info!(
        api = %config.api_base_url,
        jurisdiction = %config.default_jurisdiction,
        socket = %config.socket_path,
        "Starting cveil daemon"
    );

    // 2. Write PID file if daemonizing
    if start.daemonize {
        if let Err(e) = write_pid_file(&start.pid_file) {
            eprintln!("Failed to write PID file: {e}");
            return 1;
        }
    }

    // 3. Spawn SDK actor thread
    let (sdk, actor_join) = actor::spawn_sdk_actor(256);

    // 4. Initialize the SDK core
    let sdk_config_json = match config.to_sdk_config_json() {
        Ok(j) => j,
        Err(e) => {
            eprintln!("Failed to serialize SDK config: {e}");
            return 1;
        }
    };
    if let Err(e) = sdk.send(SdkRequest::Init { config_json: sdk_config_json }).await {
        eprintln!("Failed to initialize SDK: {e}");
        return 1;
    }

    // 5. Build HTTP client for API calls
    let http = Client::builder()
        .user_agent("cveil-cli/0.1.0")
        .build()
        .expect("Failed to build HTTP client");

    // 6. Load the consent graph
    if let Err(e) = graph::load_full_graph(&config, &sdk, &http).await {
        error!(error = %e, "Failed to load consent graph — starting without cache");
        // Don't fail — daemon will operate in stale/API-fallback mode
    }

    // 7. Create shutdown channel
    let (shutdown_tx, shutdown_rx) = tokio::sync::watch::channel(false);

    // 8. Spawn SSE listener
    let sse_config = config.clone();
    let sse_sdk = sdk.clone();
    let sse_http = http.clone();
    let sse_shutdown = shutdown_rx.clone();
    let sse_handle = tokio::spawn(async move {
        sse::run_sse_loop(sse_config, sse_sdk, sse_http, sse_shutdown).await;
    });

    // 9. Build and start HTTP server
    let state = server::AppState {
        sdk: sdk.clone(),
        shutdown_tx: shutdown_tx.clone(),
    };
    let router = server::build_router(state);

    // 10. Handle OS signals
    let signal_shutdown = shutdown_tx.clone();
    tokio::spawn(async move {
        let ctrl_c = signal::ctrl_c();
        #[cfg(unix)]
        let mut sigterm = signal::unix::signal(signal::unix::SignalKind::terminate())
            .expect("Failed to register SIGTERM handler");

        #[cfg(unix)]
        tokio::select! {
            _ = ctrl_c => info!("Received SIGINT"),
            _ = sigterm.recv() => info!("Received SIGTERM"),
        }

        #[cfg(not(unix))]
        ctrl_c.await.ok();

        let _ = signal_shutdown.send(true);
    });

    info!(socket = %config.socket_path, "cveil daemon ready");

    // 11. Run the server (blocks until shutdown)
    if let Err(e) = server::serve_uds(&config.socket_path, router, shutdown_rx).await {
        error!(error = %e, "Server error");
    }

    // 12. Cleanup
    info!("Waiting for SSE task to finish...");
    let _ = sse_handle.await;

    // Shutdown the SDK actor
    let _ = sdk.send(SdkRequest::Shutdown).await;

    // Wait for actor thread
    let _ = actor_join.join();

    // Remove PID file
    if start.daemonize {
        let _ = std::fs::remove_file(&start.pid_file);
    }

    info!("cveil daemon stopped");
    0
}

fn write_pid_file(path: &str) -> Result<(), String> {
    let pid = std::process::id();
    std::fs::write(path, pid.to_string())
        .map_err(|e| format!("Failed to write PID to {path}: {e}"))?;
    info!(pid, path, "PID file written");
    Ok(())
}
