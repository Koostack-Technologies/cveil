use std::thread;

use cveil_sdk_core::ffi::exports;
use tokio::sync::{mpsc, oneshot};
use tracing::{debug, info, warn};

/// Request types that HTTP handlers can send to the SDK actor.
#[derive(Debug)]
pub enum SdkRequest {
    Init { config_json: String },
    Mask { subject: String, entity: String, payload: String, purpose: String },
    MaskWithJurisdiction { subject: String, entity: String, payload: String, purpose: String, jurisdiction: String },
    MaskBatch { subject: String, entity: String, json_array: String, purpose: String },
    Evaluate { subject: String, entity: String, fields_json: String, purpose: String },
    IngestGraphPage { page_json: String },
    #[allow(dead_code)]
    IngestDecisions { subject: String, decisions_json: String },
    ProcessInvalidation { event_json: String },
    SseConnected,
    SseDisconnected,
    SseReconnected,
    Status,
    Shutdown,
}

/// Response from the SDK actor.
#[derive(Debug)]
pub struct SdkResponse {
    pub result: Result<String, String>,
}

/// A message sent to the actor: a request paired with a oneshot reply channel.
pub struct SdkMessage {
    pub request: SdkRequest,
    pub reply: oneshot::Sender<SdkResponse>,
}

/// Handle to communicate with the SDK actor thread.
#[derive(Clone)]
pub struct SdkHandle {
    tx: mpsc::Sender<SdkMessage>,
}

impl SdkHandle {
    /// Send a request to the SDK actor and await the response.
    pub async fn send(&self, request: SdkRequest) -> Result<String, String> {
        let (reply_tx, reply_rx) = oneshot::channel();
        let msg = SdkMessage {
            request,
            reply: reply_tx,
        };
        self.tx
            .send(msg)
            .await
            .map_err(|_| "SDK actor is not running".to_string())?;
        let response = reply_rx
            .await
            .map_err(|_| "SDK actor dropped the response channel".to_string())?;
        response.result
    }
}

/// Spawn the SDK actor on a dedicated OS thread.
/// Returns a handle for sending requests and a JoinHandle for the thread.
pub fn spawn_sdk_actor(buffer: usize) -> (SdkHandle, thread::JoinHandle<()>) {
    let (tx, mut rx) = mpsc::channel::<SdkMessage>(buffer);

    let handle = SdkHandle { tx };

    // The actor thread owns all SDK state via thread-local.
    // We use a tokio runtime on this thread ONLY for the channel receiver.
    let join = thread::Builder::new()
        .name("cveil-sdk-actor".into())
        .spawn(move || {
            // Single-threaded runtime — we only need the channel receiver here
            let rt = tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()
                .expect("Failed to create actor runtime");

            rt.block_on(async move {
                info!("SDK actor thread started");
                while let Some(msg) = rx.recv().await {
                    let is_shutdown = matches!(msg.request, SdkRequest::Shutdown);
                    let result = dispatch(msg.request);
                    let _ = msg.reply.send(SdkResponse { result });
                    if is_shutdown {
                        info!("SDK actor received shutdown — exiting");
                        break;
                    }
                }
                info!("SDK actor thread stopped");
            });
        })
        .expect("Failed to spawn SDK actor thread");

    (handle, join)
}

/// Dispatch a request to the appropriate cveil_* function.
/// All calls happen on this thread, satisfying the thread-local constraint.
fn dispatch(request: SdkRequest) -> Result<String, String> {
    match request {
        SdkRequest::Init { config_json } => {
            debug!("SDK init");
            exports::cveil_init(&config_json)
        }
        SdkRequest::Mask { subject, entity, payload, purpose } => {
            debug!(subject = %subject, entity = %entity, purpose = %purpose, "mask");
            exports::cveil_mask(&subject, &entity, &payload, &purpose)
        }
        SdkRequest::MaskWithJurisdiction { subject, entity, payload, purpose, jurisdiction } => {
            debug!(subject = %subject, entity = %entity, purpose = %purpose, jurisdiction = %jurisdiction, "mask_with_jurisdiction");
            exports::cveil_mask_with_jurisdiction(&subject, &entity, &payload, &purpose, &jurisdiction)
        }
        SdkRequest::MaskBatch { subject, entity, json_array, purpose } => {
            debug!(subject = %subject, entity = %entity, purpose = %purpose, "mask_batch");
            exports::cveil_mask_batch(&subject, &entity, &json_array, &purpose)
        }
        SdkRequest::Evaluate { subject, entity, fields_json, purpose } => {
            debug!(subject = %subject, entity = %entity, purpose = %purpose, "evaluate");
            exports::cveil_evaluate(&subject, &entity, &fields_json, &purpose)
        }
        SdkRequest::IngestGraphPage { page_json } => {
            debug!("ingest_graph_page");
            exports::cveil_ingest_graph_page(&page_json)
        }
        SdkRequest::IngestDecisions { subject, decisions_json } => {
            debug!(subject = %subject, "ingest_decisions");
            exports::cveil_ingest_decisions(&subject, &decisions_json)
        }
        SdkRequest::ProcessInvalidation { event_json } => {
            debug!("process_invalidation");
            exports::cveil_process_invalidation(&event_json)
        }
        SdkRequest::SseConnected => {
            debug!("sse_connected");
            exports::cveil_sse_connected()
        }
        SdkRequest::SseDisconnected => {
            warn!("SSE disconnected");
            exports::cveil_sse_disconnected()
        }
        SdkRequest::SseReconnected => {
            info!("SSE reconnected — flushing cache");
            exports::cveil_sse_reconnected()
        }
        SdkRequest::Status => {
            exports::cveil_status()
        }
        SdkRequest::Shutdown => {
            info!("Shutting down SDK core");
            exports::cveil_shutdown()
        }
    }
}
