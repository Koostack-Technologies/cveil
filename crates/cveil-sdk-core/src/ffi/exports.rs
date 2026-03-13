use std::cell::RefCell;
use std::collections::HashMap;

use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::cache::DecisionCache;
use crate::config::{SdkConfig, StaleMode};
use crate::evaluator::{ConsentEvaluator, EvalResult};
use crate::graph::GraphStore;
use crate::invalidation::{
    ConnectionState, ConnectionTracker, InvalidationEffect, InvalidationHandler,
};
use crate::masking::mask_json_tree;
use crate::models::{
    CachedDecision, ConsentAction, ConsentGraphPage, InvalidationMessage, MaskingType,
};

/// Central SDK state. All WASM exports operate on this via thread-local.
struct SdkCore {
    config: SdkConfig,
    graph: GraphStore,
    decisions: DecisionCache,
    invalidation: InvalidationHandler,
    connection: ConnectionTracker,
    initialized: bool,
}

thread_local! {
    static SDK: RefCell<Option<SdkCore>> = const { RefCell::new(None) };
}

fn with_sdk<F, R>(f: F) -> Result<R, String>
where
    F: FnOnce(&mut SdkCore) -> Result<R, String>,
{
    SDK.with(|cell| {
        let mut borrow = cell.borrow_mut();
        match borrow.as_mut() {
            Some(sdk) => f(sdk),
            None => Err("SDK not initialized — call cveil_init first".to_string()),
        }
    })
}

// ─── Exported Functions ────────────────────────────────────────────

/// Initialize the SDK with a JSON config string.
/// Must be called before any other function.
pub fn cveil_init(config_json: &str) -> Result<String, String> {
    let config: SdkConfig = serde_json::from_str(config_json).map_err(|e| e.to_string())?;
    config.validate().map_err(|e| e.to_string())?;

    let decisions = DecisionCache::new(
        config.decision_cache_max_size,
        config.decision_cache_ttl_ms,
    );
    let invalidation = InvalidationHandler::new(config.dedup_window_size);
    let connection = ConnectionTracker::new(config.disconnect_threshold_ms);

    let core = SdkCore {
        config,
        graph: GraphStore::new(),
        decisions,
        invalidation,
        connection,
        initialized: true,
    };

    SDK.with(|cell| {
        *cell.borrow_mut() = Some(core);
    });

    Ok(r#"{"status":"initialized"}"#.to_string())
}

/// Ingest a page of the consent graph (from paginated API response).
pub fn cveil_ingest_graph_page(page_json: &str) -> Result<String, String> {
    let page: ConsentGraphPage =
        serde_json::from_str(page_json).map_err(|e| format!("Invalid graph page JSON: {e}"))?;

    with_sdk(|sdk| {
        sdk.graph.ingest_page(&page);
        let has_more = page.page_info.has_more;
        Ok(serde_json::json!({
            "ingested": true,
            "hasMore": has_more,
            "totalNodes": sdk.graph.node_count(),
        })
        .to_string())
    })
}

/// Ingest subject consent decisions (from /evaluate API response).
pub fn cveil_ingest_decisions(subject: &str, decisions_json: &str) -> Result<String, String> {
    #[derive(Deserialize)]
    struct DecisionEntry {
        #[serde(rename = "policyName")]
        policy_name: String,
        action: ConsentAction,
        #[serde(rename = "expiresAt")]
        expires_at: Option<u64>,
    }

    let entries: Vec<DecisionEntry> =
        serde_json::from_str(decisions_json).map_err(|e| format!("Invalid decisions JSON: {e}"))?;

    with_sdk(|sdk| {
        let now_ms = current_time_ms();
        let count = entries.len();
        for entry in entries {
            let key = DecisionCache::cache_key(subject, &entry.policy_name);
            sdk.decisions.put(
                key,
                CachedDecision {
                    action: entry.action,
                    expires_at: entry.expires_at,
                    cached_at: now_ms,
                },
            );
        }
        Ok(serde_json::json!({"ingested": count}).to_string())
    })
}

/// Core mask function — masks a JSON object based on consent graph evaluation.
///
/// Returns a JSON object with two fields:
/// - `"data"`: the masked JSON payload
/// - `"needsFetch"`: array of `{subject, policyNames}` objects that need API calls (empty if all resolved)
pub fn cveil_mask(
    subject: &str,
    entity: &str,
    json_payload: &str,
    purpose: &str,
) -> Result<String, String> {
    with_sdk(|sdk| {
        do_mask(sdk, subject, entity, json_payload, purpose, &sdk.config.default_jurisdiction.clone())
    })
}

/// Core mask with explicit jurisdiction override.
pub fn cveil_mask_with_jurisdiction(
    subject: &str,
    entity: &str,
    json_payload: &str,
    purpose: &str,
    jurisdiction: &str,
) -> Result<String, String> {
    with_sdk(|sdk| do_mask(sdk, subject, entity, json_payload, purpose, jurisdiction))
}

fn do_mask(
    sdk: &mut SdkCore,
    subject: &str,
    entity: &str,
    json_payload: &str,
    purpose: &str,
    jurisdiction: &str,
) -> Result<String, String> {
    let mut value: Value =
        serde_json::from_str(json_payload).map_err(|e| format!("Invalid JSON payload: {e}"))?;

    let now_ms = current_time_ms();

    // Check stale threshold
    sdk.connection.tick(now_ms);

    let conservative = match sdk.connection.state() {
        ConnectionState::Connected | ConnectionState::Disconnected => false,
        ConnectionState::Stale => matches!(sdk.config.stale_mode, StaleMode::ConservativeMask),
    };

    // Discover fields for this entity
    let registered = ConsentEvaluator::registered_field_paths(&sdk.graph, entity);
    if registered.is_empty() {
        // No fields registered → nothing to mask
        return Ok(serde_json::json!({"data": value, "needsFetch": []}).to_string());
    }

    // Evaluate each registered field
    let results = ConsentEvaluator::evaluate_fields(
        &sdk.graph,
        &mut sdk.decisions,
        subject,
        entity,
        &registered,
        purpose,
        jurisdiction,
        now_ms,
        conservative,
    );

    let mut resolutions: HashMap<String, MaskingType> = HashMap::new();
    let mut needs_fetch: Vec<Value> = Vec::new();

    for result in results {
        match result {
            EvalResult::Resolved(res) => {
                resolutions.insert(res.field_path.clone(), res.masking_type);
            }
            EvalResult::NeedsFetch {
                field_path,
                policy_names,
                ..
            } => {
                needs_fetch.push(serde_json::json!({
                    "fieldPath": field_path,
                    "policyNames": policy_names,
                }));
                // Apply deny-by-default while waiting for fetch
                resolutions.insert(field_path, MaskingType::RedactAll);
            }
        }
    }

    mask_json_tree(&mut value, &resolutions);

    Ok(serde_json::json!({
        "data": value,
        "needsFetch": needs_fetch,
    })
    .to_string())
}

/// Batch mask — masks an array of JSON objects (same subject/entity/purpose).
pub fn cveil_mask_batch(
    subject: &str,
    entity: &str,
    json_array: &str,
    purpose: &str,
) -> Result<String, String> {
    let arr: Vec<Value> =
        serde_json::from_str(json_array).map_err(|e| format!("Invalid JSON array: {e}"))?;

    let mut results = Vec::with_capacity(arr.len());
    for item in &arr {
        let item_json = serde_json::to_string(item).map_err(|e| e.to_string())?;
        let masked_json = cveil_mask(subject, entity, &item_json, purpose)?;
        let masked: Value = serde_json::from_str(&masked_json).map_err(|e| e.to_string())?;
        results.push(masked);
    }

    Ok(serde_json::to_string(&results).map_err(|e| e.to_string())?)
}

/// Direct consent evaluation — returns per-field MaskingType resolution without masking.
pub fn cveil_evaluate(
    subject: &str,
    entity: &str,
    fields_json: &str,
    purpose: &str,
) -> Result<String, String> {
    let fields: Vec<String> =
        serde_json::from_str(fields_json).map_err(|e| format!("Invalid fields JSON: {e}"))?;

    with_sdk(|sdk| {
        let now_ms = current_time_ms();
        sdk.connection.tick(now_ms);

        let conservative = matches!(
            (sdk.connection.state(), &sdk.config.stale_mode),
            (ConnectionState::Stale, StaleMode::ConservativeMask)
        );

        let results = ConsentEvaluator::evaluate_fields(
            &sdk.graph,
            &mut sdk.decisions,
            subject,
            entity,
            &fields,
            purpose,
            &sdk.config.default_jurisdiction,
            now_ms,
            conservative,
        );

        #[derive(Serialize)]
        struct FieldResult {
            field: String,
            masking_type: String,
            #[serde(skip_serializing_if = "Option::is_none")]
            consent_action: Option<String>,
            needs_fetch: bool,
            #[serde(skip_serializing_if = "Vec::is_empty")]
            policy_names: Vec<String>,
        }

        let output: Vec<FieldResult> = results
            .into_iter()
            .map(|r| match r {
                EvalResult::Resolved(res) => FieldResult {
                    field: res.field_path,
                    masking_type: format!("{:?}", res.masking_type),
                    consent_action: res.consent_action.map(|a| format!("{:?}", a)),
                    needs_fetch: false,
                    policy_names: vec![],
                },
                EvalResult::NeedsFetch {
                    field_path,
                    policy_names,
                    ..
                } => FieldResult {
                    field: field_path,
                    masking_type: "UNKNOWN".to_string(),
                    consent_action: None,
                    needs_fetch: true,
                    policy_names,
                },
            })
            .collect();

        serde_json::to_string(&output).map_err(|e| e.to_string())
    })
}

/// Process an SSE invalidation event.
pub fn cveil_process_invalidation(event_json: &str) -> Result<String, String> {
    let msg: InvalidationMessage = serde_json::from_str(event_json)
        .map_err(|e| format!("Invalid invalidation JSON: {e}"))?;

    with_sdk(|sdk| {
        let effect = sdk
            .invalidation
            .process(&msg, &mut sdk.graph, &mut sdk.decisions);

        let effect_str = match effect {
            Some(InvalidationEffect::None) => "none",
            Some(InvalidationEffect::RefreshFullGraph) => "refresh_full_graph",
            Some(InvalidationEffect::RefreshSubgraph { .. }) => "refresh_subgraph",
            Some(InvalidationEffect::FlushAndReload) => "flush_and_reload",
            None => "duplicate",
        };

        let mut response = serde_json::json!({"effect": effect_str});

        if let Some(InvalidationEffect::RefreshSubgraph {
            node_type,
            node_name,
        }) = effect
        {
            response["nodeType"] = Value::String(node_type);
            response["nodeName"] = Value::String(node_name);
        }

        Ok(response.to_string())
    })
}

/// Notify the core that the SSE connection has been established.
pub fn cveil_sse_connected() -> Result<String, String> {
    with_sdk(|sdk| {
        sdk.connection.on_connected();
        Ok(r#"{"connected":true}"#.to_string())
    })
}

/// Notify the core that the SSE connection has been lost.
/// Returns the effect the facade should handle.
pub fn cveil_sse_disconnected() -> Result<String, String> {
    with_sdk(|sdk| {
        let now_ms = current_time_ms();
        sdk.connection.on_disconnected(now_ms);
        Ok(serde_json::json!({
            "connected": false,
            "state": format!("{:?}", sdk.connection.state()),
        })
        .to_string())
    })
}

/// Signal that SSE has reconnected — triggers full cache flush and reload.
pub fn cveil_sse_reconnected() -> Result<String, String> {
    with_sdk(|sdk| {
        let effect =
            sdk.invalidation
                .on_reconnect(&mut sdk.graph, &mut sdk.decisions);
        sdk.connection.on_connected();
        let effect_str = match effect {
            InvalidationEffect::FlushAndReload => "flush_and_reload",
            _ => "none",
        };
        Ok(serde_json::json!({"effect": effect_str}).to_string())
    })
}

/// Get SDK status — health, connection, cache stats.
pub fn cveil_status() -> Result<String, String> {
    with_sdk(|sdk| {
        let now_ms = current_time_ms();
        sdk.connection.tick(now_ms);

        Ok(serde_json::json!({
            "initialized": sdk.initialized,
            "connectionState": format!("{:?}", sdk.connection.state()),
            "graphNodes": sdk.graph.node_count(),
            "cachedDecisions": sdk.decisions.len(),
            "decisionCacheCapacity": sdk.decisions.max_size(),
        })
        .to_string())
    })
}

/// Graceful shutdown — flush all caches and drop state.
pub fn cveil_shutdown() -> Result<String, String> {
    SDK.with(|cell| {
        *cell.borrow_mut() = None;
    });
    Ok(r#"{"status":"shutdown"}"#.to_string())
}

// ─── Time Helper ───────────────────────────────────────────────────

/// Get current time in milliseconds.
/// Uses std::time on native targets; on WASM this should be injected by the facade.
fn current_time_ms() -> u64 {
    #[cfg(not(target_arch = "wasm32"))]
    {
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_millis() as u64)
            .unwrap_or(0)
    }
    #[cfg(target_arch = "wasm32")]
    {
        // WASI provides clock_time_get
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_millis() as u64)
            .unwrap_or(0)
    }
}

// ─── Tests ─────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    fn test_config() -> String {
        serde_json::json!({
            "apiBaseUrl": "http://localhost:8080",
            "apiToken": "cveil_test_token_12345",
            "defaultJurisdiction": "EU",
            "staleMode": "conservativeMask",
            "disconnectThresholdMs": 60000,
            "decisionCacheMaxSize": 100,
            "decisionCacheTtlMs": 300000,
            "dedupWindowSize": 500
        })
        .to_string()
    }

    fn test_graph_page() -> String {
        serde_json::json!({
            "nodes": [
                {"type": "entity", "name": "User", "properties": {}},
                {"type": "field", "name": "User/email", "properties": {}},
                {"type": "field", "name": "User/phone", "properties": {}},
                {"type": "category", "name": "PII", "properties": {}},
                {"type": "purpose", "name": "Marketing", "properties": {}},
                {"type": "regulation", "name": "GDPR", "properties": {}},
                {"type": "jurisdiction", "name": "EU", "properties": {}},
                {"type": "policy", "name": "FIELD:User/email:Marketing", "properties": {"policyType": "FIELD"}},
                {"type": "policy", "name": "CAT:PII:Marketing", "properties": {"policyType": "CATEGORY"}}
            ],
            "edges": [
                {"sourceType": "field", "sourceName": "User/email", "targetType": "category", "targetName": "PII", "properties": {}},
                {"sourceType": "field", "sourceName": "User/phone", "targetType": "category", "targetName": "PII", "properties": {}},
                {"sourceType": "purpose", "sourceName": "Marketing", "targetType": "regulation", "targetName": "GDPR", "properties": {"lawfulBasis": "consent", "onGranted": "PLAINTEXT", "onDenied": "REDACT_ALL"}},
                {"sourceType": "regulation", "sourceName": "GDPR", "targetType": "jurisdiction", "targetName": "EU", "properties": {}},
                {"sourceType": "purpose", "sourceName": "Marketing", "targetType": "policy", "targetName": "FIELD:User/email:Marketing", "properties": {}},
                {"sourceType": "purpose", "sourceName": "Marketing", "targetType": "policy", "targetName": "CAT:PII:Marketing", "properties": {}}
            ],
            "pageInfo": {"nextCursor": null, "hasMore": false, "totalNodes": 9, "totalEdges": 6}
        })
        .to_string()
    }

    fn setup() {
        // Clean up any previous state
        let _ = cveil_shutdown();
        let result = cveil_init(&test_config());
        assert!(result.is_ok(), "Init failed: {:?}", result);
        let result = cveil_ingest_graph_page(&test_graph_page());
        assert!(result.is_ok(), "Ingest failed: {:?}", result);
    }

    #[test]
    fn init_and_status() {
        setup();
        let status = cveil_status().unwrap();
        let v: Value = serde_json::from_str(&status).unwrap();
        assert_eq!(v["initialized"], true);
        assert!(v["graphNodes"].as_u64().unwrap() > 0);
        let _ = cveil_shutdown();
    }

    #[test]
    fn init_rejects_bad_config() {
        let _ = cveil_shutdown();
        let result = cveil_init(r#"{"api_base_url":"","api_token":"bad"}"#);
        assert!(result.is_err());
    }

    #[test]
    fn mask_without_consent_applies_deny_default() {
        setup();
        // Mark as connected so conservative fallback is NOT used (stale mode),
        // but since no decisions are cached, the evaluator will try NeedsFetch
        // However, we set StaleMode::ConservativeMask and connection starts as Disconnected,
        // so the evaluator will use conservative=true (deny by default)
        let payload = r#"{"email": "alice@example.com", "name": "Alice"}"#;
        let result = cveil_mask("user-1", "User", payload, "Marketing").unwrap();
        let v: Value = serde_json::from_str(&result).unwrap();
        // email is registered → should be masked (deny-by-default)
        assert_ne!(v["data"]["email"], "alice@example.com");
        // name is NOT registered → should pass through
        assert_eq!(v["data"]["name"], "Alice");
        let _ = cveil_shutdown();
    }

    #[test]
    fn mask_with_granted_consent_passes_through() {
        setup();
        // Ingest a GRANT decision for email
        let decisions = serde_json::json!([
            {"policyName": "FIELD:User/email:Marketing", "action": "GRANT", "expiresAt": null}
        ])
        .to_string();
        cveil_ingest_decisions("user-1", &decisions).unwrap();

        let payload = r#"{"email": "alice@example.com", "phone": "555-1234"}"#;
        let result = cveil_mask("user-1", "User", payload, "Marketing").unwrap();
        let v: Value = serde_json::from_str(&result).unwrap();

        // email: GRANTED → PLAINTEXT (pass through)
        assert_eq!(v["data"]["email"], "alice@example.com");
        // phone: no consent → REDACT_ALL
        assert_eq!(v["data"]["phone"], "***");
        let _ = cveil_shutdown();
    }

    #[test]
    fn mask_batch() {
        setup();
        let arr = serde_json::json!([
            {"email": "a@b.com"},
            {"email": "c@d.com"}
        ])
        .to_string();
        let result = cveil_mask_batch("user-1", "User", &arr, "Marketing").unwrap();
        let v: Value = serde_json::from_str(&result).unwrap();
        assert!(v.as_array().unwrap().len() == 2);
        // Both should have masked emails (no consent)
        for item in v.as_array().unwrap() {
            assert_ne!(item["data"]["email"], "a@b.com");
        }
        let _ = cveil_shutdown();
    }

    #[test]
    fn evaluate_returns_field_resolutions() {
        setup();
        let fields = serde_json::json!(["email", "phone"]).to_string();
        let result = cveil_evaluate("user-1", "User", &fields, "Marketing").unwrap();
        let v: Value = serde_json::from_str(&result).unwrap();
        let arr = v.as_array().unwrap();
        assert_eq!(arr.len(), 2);
        let _ = cveil_shutdown();
    }

    #[test]
    fn process_invalidation_and_dedup() {
        setup();
        let event = serde_json::json!({
            "messageId": "evt-1",
            "tenantId": "t1",
            "action": "PURPOSE_CHANGED",
            "purpose": "Marketing"
        })
        .to_string();

        let r1 = cveil_process_invalidation(&event).unwrap();
        let v1: Value = serde_json::from_str(&r1).unwrap();
        assert_eq!(v1["effect"], "refresh_subgraph");

        // Duplicate should be ignored
        let r2 = cveil_process_invalidation(&event).unwrap();
        let v2: Value = serde_json::from_str(&r2).unwrap();
        assert_eq!(v2["effect"], "duplicate");
        let _ = cveil_shutdown();
    }

    #[test]
    fn sse_lifecycle() {
        setup();

        let r = cveil_sse_connected().unwrap();
        let v: Value = serde_json::from_str(&r).unwrap();
        assert_eq!(v["connected"], true);

        let status = cveil_status().unwrap();
        let v: Value = serde_json::from_str(&status).unwrap();
        assert_eq!(v["connectionState"], "Connected");

        let r = cveil_sse_disconnected().unwrap();
        let v: Value = serde_json::from_str(&r).unwrap();
        assert_eq!(v["connected"], false);

        let _ = cveil_shutdown();
    }

    #[test]
    fn reconnect_flushes_and_reloads() {
        setup();
        cveil_sse_connected().unwrap();

        let r = cveil_sse_reconnected().unwrap();
        let v: Value = serde_json::from_str(&r).unwrap();
        assert_eq!(v["effect"], "flush_and_reload");

        // Graph should be empty after flush
        let status = cveil_status().unwrap();
        let v: Value = serde_json::from_str(&status).unwrap();
        assert_eq!(v["graphNodes"], 0);
        assert_eq!(v["connectionState"], "Connected");

        let _ = cveil_shutdown();
    }

    #[test]
    fn shutdown_cleans_up() {
        setup();
        cveil_shutdown().unwrap();
        assert!(cveil_status().is_err());
    }
}
