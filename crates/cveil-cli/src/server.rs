use axum::{
    Json, Router,
    extract::State,
    http::StatusCode,
    response::IntoResponse,
    routing::{get, post},
};
use serde::{Deserialize, Serialize};
use tokio::net::UnixListener;
use tracing::info;

use crate::actor::{SdkHandle, SdkRequest};

/// Shared state passed to all handlers.
#[derive(Clone)]
pub struct AppState {
    pub sdk: SdkHandle,
    pub shutdown_tx: tokio::sync::watch::Sender<bool>,
}

// ── Request/Response types ─────────────────────────────────────────

#[derive(Deserialize)]
pub struct MaskRequest {
    pub subject: String,
    pub entity: String,
    pub purpose: String,
    pub jurisdiction: Option<String>,
    pub data: serde_json::Value,
}

#[derive(Deserialize)]
pub struct MaskBatchRequest {
    pub subject: String,
    pub entity: String,
    pub purpose: String,
    #[allow(dead_code)]
    pub jurisdiction: Option<String>,
    pub data: Vec<serde_json::Value>,
}

#[derive(Deserialize)]
pub struct EvaluateRequest {
    pub subject: String,
    pub entity: String,
    pub purpose: String,
    pub fields: Vec<String>,
}

#[derive(Serialize)]
struct ErrorResponse {
    error: String,
}

fn sdk_error(msg: String) -> impl IntoResponse {
    (
        StatusCode::INTERNAL_SERVER_ERROR,
        Json(ErrorResponse { error: msg }),
    )
}

// ── Handlers ───────────────────────────────────────────────────────

async fn handle_mask(
    State(state): State<AppState>,
    Json(req): Json<MaskRequest>,
) -> impl IntoResponse {
    let payload = serde_json::to_string(&req.data).unwrap();

    let request = if let Some(jurisdiction) = req.jurisdiction {
        SdkRequest::MaskWithJurisdiction {
            subject: req.subject,
            entity: req.entity,
            payload,
            purpose: req.purpose,
            jurisdiction,
        }
    } else {
        SdkRequest::Mask {
            subject: req.subject,
            entity: req.entity,
            payload,
            purpose: req.purpose,
        }
    };

    match state.sdk.send(request).await {
        Ok(result_json) => {
            let value: serde_json::Value = serde_json::from_str(&result_json).unwrap();
            (StatusCode::OK, Json(value)).into_response()
        }
        Err(e) => sdk_error(e).into_response(),
    }
}

async fn handle_mask_batch(
    State(state): State<AppState>,
    Json(req): Json<MaskBatchRequest>,
) -> impl IntoResponse {
    let json_array = serde_json::to_string(&req.data).unwrap();

    // Batch masking — for now uses default jurisdiction only
    let request = SdkRequest::MaskBatch {
        subject: req.subject,
        entity: req.entity,
        json_array,
        purpose: req.purpose,
    };

    match state.sdk.send(request).await {
        Ok(result_json) => {
            let value: serde_json::Value = serde_json::from_str(&result_json).unwrap();
            (StatusCode::OK, Json(value)).into_response()
        }
        Err(e) => sdk_error(e).into_response(),
    }
}

async fn handle_evaluate(
    State(state): State<AppState>,
    Json(req): Json<EvaluateRequest>,
) -> impl IntoResponse {
    let fields_json = serde_json::to_string(&req.fields).unwrap();

    let request = SdkRequest::Evaluate {
        subject: req.subject,
        entity: req.entity,
        fields_json,
        purpose: req.purpose,
    };

    match state.sdk.send(request).await {
        Ok(result_json) => {
            let value: serde_json::Value = serde_json::from_str(&result_json).unwrap();
            (StatusCode::OK, Json(value)).into_response()
        }
        Err(e) => sdk_error(e).into_response(),
    }
}

async fn handle_status(State(state): State<AppState>) -> impl IntoResponse {
    match state.sdk.send(SdkRequest::Status).await {
        Ok(result_json) => {
            let value: serde_json::Value = serde_json::from_str(&result_json).unwrap();
            (StatusCode::OK, Json(value)).into_response()
        }
        Err(e) => sdk_error(e).into_response(),
    }
}

async fn handle_shutdown(State(state): State<AppState>) -> impl IntoResponse {
    // Signal daemon to shut down
    let _ = state.sdk.send(SdkRequest::Shutdown).await;
    let _ = state.shutdown_tx.send(true);
    Json(serde_json::json!({"status": "shutting_down"}))
}

// ── Router + Server ────────────────────────────────────────────────

pub fn build_router(state: AppState) -> Router {
    Router::new()
        .route("/mask", post(handle_mask))
        .route("/mask/batch", post(handle_mask_batch))
        .route("/evaluate", post(handle_evaluate))
        .route("/status", get(handle_status))
        .route("/shutdown", post(handle_shutdown))
        .with_state(state)
}

/// Start the HTTP server on a Unix domain socket.
/// Returns when shutdown_rx receives true.
pub async fn serve_uds(
    socket_path: &str,
    router: Router,
    mut shutdown_rx: tokio::sync::watch::Receiver<bool>,
) -> Result<(), String> {
    // Remove stale socket file
    if std::path::Path::new(socket_path).exists() {
        std::fs::remove_file(socket_path)
            .map_err(|e| format!("Failed to remove stale socket {socket_path}: {e}"))?;
    }

    let listener = UnixListener::bind(socket_path)
        .map_err(|e| format!("Failed to bind UDS at {socket_path}: {e}"))?;

    info!(socket = %socket_path, "HTTP server listening");

    // Use axum's serve with unix listener
    let server = axum::serve(listener, router)
        .with_graceful_shutdown(async move {
            let _ = shutdown_rx.wait_for(|&v| v).await;
            info!("Graceful shutdown initiated");
        });

    server
        .await
        .map_err(|e| format!("Server error: {e}"))?;

    // Clean up socket file
    let _ = std::fs::remove_file(socket_path);
    info!("Socket file removed");

    Ok(())
}
