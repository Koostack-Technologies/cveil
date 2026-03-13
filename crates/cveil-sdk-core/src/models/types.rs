use serde::{Deserialize, Serialize};
use std::collections::HashMap;

// ---------------------------------------------------------------------------
// Node types
// ---------------------------------------------------------------------------

/// All recognized graph node types in canonical traversal order.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum NodeType {
    Entity,
    Field,
    Category,
    Purpose,
    Regulation,
    Jurisdiction,
    Policy,
    Subject,
}

impl NodeType {
    pub fn as_str(self) -> &'static str {
        match self {
            NodeType::Entity => "entity",
            NodeType::Field => "field",
            NodeType::Category => "category",
            NodeType::Purpose => "purpose",
            NodeType::Regulation => "regulation",
            NodeType::Jurisdiction => "jurisdiction",
            NodeType::Policy => "policy",
            NodeType::Subject => "subject",
        }
    }

    pub fn from_str(s: &str) -> Option<NodeType> {
        match s {
            "entity" => Some(NodeType::Entity),
            "field" => Some(NodeType::Field),
            "category" => Some(NodeType::Category),
            "purpose" => Some(NodeType::Purpose),
            "regulation" => Some(NodeType::Regulation),
            "jurisdiction" => Some(NodeType::Jurisdiction),
            "policy" => Some(NodeType::Policy),
            "subject" => Some(NodeType::Subject),
            _ => None,
        }
    }
}

// ---------------------------------------------------------------------------
// Graph nodes & edges (mirror the REST API DTOs)
// ---------------------------------------------------------------------------

/// A node in the consent graph.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GraphNode {
    #[serde(rename = "type")]
    pub node_type: String,
    pub name: String,
    #[serde(default)]
    pub properties: HashMap<String, String>,
}

/// A directed edge in the consent graph, optionally carrying properties.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GraphEdge {
    #[serde(rename = "sourceType")]
    pub source_type: String,
    #[serde(rename = "sourceName")]
    pub source_name: String,
    #[serde(rename = "targetType")]
    pub target_type: String,
    #[serde(rename = "targetName")]
    pub target_name: String,
    #[serde(default)]
    pub properties: HashMap<String, String>,
}

/// Pagination metadata for graph queries.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GraphPageInfo {
    #[serde(rename = "nextCursor")]
    pub next_cursor: Option<String>,
    #[serde(rename = "hasMore")]
    pub has_more: bool,
    #[serde(rename = "totalNodes")]
    pub total_nodes: u32,
    #[serde(rename = "totalEdges")]
    pub total_edges: u32,
}

/// A page of the consent graph returned by the API.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConsentGraphPage {
    pub nodes: Vec<GraphNode>,
    pub edges: Vec<GraphEdge>,
    #[serde(rename = "pageInfo")]
    pub page_info: GraphPageInfo,
}

// ---------------------------------------------------------------------------
// Masking types (mirrors DB enum)
// ---------------------------------------------------------------------------

/// Masking strategy applied to a field based on consent status.
///
/// Variants are ordered by restrictiveness (lowest = least restrictive).
/// Use `Ord` to compare: `max(a, b)` gives the most restrictive.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum MaskingType {
    /// Return data as-is (no masking).
    Plaintext = 0,
    /// Show last 4 chars, mask the rest (e.g., "****5678").
    RedactUpToLast4 = 1,
    /// Mask local part of email/compound ID (e.g., "****@example.com").
    RedactLocal = 2,
    /// SHA-256 hex digest of the value.
    Hash256 = 3,
    /// Full redaction (type-safe sentinels: "***", 0, false, null).
    RedactAll = 4,
}

impl MaskingType {
    pub fn from_str(s: &str) -> Option<MaskingType> {
        match s {
            "PLAINTEXT" => Some(MaskingType::Plaintext),
            "REDACT_UP_TO_LAST_4" => Some(MaskingType::RedactUpToLast4),
            "REDACT_LOCAL" => Some(MaskingType::RedactLocal),
            "HASH256" => Some(MaskingType::Hash256),
            "REDACT_ALL" => Some(MaskingType::RedactAll),
            _ => None,
        }
    }

    pub fn as_str(self) -> &'static str {
        match self {
            MaskingType::Plaintext => "PLAINTEXT",
            MaskingType::RedactUpToLast4 => "REDACT_UP_TO_LAST_4",
            MaskingType::RedactLocal => "REDACT_LOCAL",
            MaskingType::Hash256 => "HASH256",
            MaskingType::RedactAll => "REDACT_ALL",
        }
    }
}

impl Default for MaskingType {
    fn default() -> Self {
        MaskingType::RedactAll
    }
}

// ---------------------------------------------------------------------------
// Policy types
// ---------------------------------------------------------------------------

/// Whether a policy binds a purpose to a specific field or a category.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum PolicyType {
    Field,
    Category,
}

// ---------------------------------------------------------------------------
// Consent actions
// ---------------------------------------------------------------------------

/// The consent decision action on a subject→policy edge.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ConsentAction {
    Grant,
    Revoke,
    Expire,
}

// ---------------------------------------------------------------------------
// Purpose–regulation link (edge data)
// ---------------------------------------------------------------------------

/// Data carried on a purpose→regulation graph edge.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PurposeRegulationLink {
    pub purpose_name: String,
    pub regulation_name: String,
    pub lawful_basis: Option<String>,
    #[serde(default = "default_on_granted")]
    pub on_granted: MaskingType,
    #[serde(default)]
    pub on_denied: MaskingType,
}

fn default_on_granted() -> MaskingType {
    MaskingType::Plaintext
}

// ---------------------------------------------------------------------------
// Subject consent decision (cached)
// ---------------------------------------------------------------------------

/// A cached consent decision for a subject + policy.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CachedDecision {
    pub action: ConsentAction,
    /// Unix milliseconds when this decision expires, or `None` for indefinite.
    pub expires_at: Option<u64>,
    /// Unix milliseconds when this was cached.
    pub cached_at: u64,
}

impl CachedDecision {
    /// Returns `true` if the decision has expired as of `now_ms` (Unix millis).
    pub fn is_expired(&self, now_ms: u64) -> bool {
        match self.expires_at {
            Some(exp) => now_ms >= exp,
            None => false,
        }
    }

    /// Returns `true` if the decision represents granted consent.
    pub fn is_granted(&self) -> bool {
        self.action == ConsentAction::Grant
    }
}

// ---------------------------------------------------------------------------
// Invalidation messages (from SSE)
// ---------------------------------------------------------------------------

/// The type of cache invalidation event.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum InvalidationAction {
    Granted,
    Revoked,
    AllRevoked,
    FieldChanged,
    PurposeChanged,
    RegulationChanged,
    CategoryChanged,
    JurisdictionChanged,
    PolicyChanged,
    SubjectChanged,
    FieldRemoved,
    EntityRemoved,
    ConsentEntryChanged,
}

/// A cache invalidation message received via SSE.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct InvalidationMessage {
    pub message_id: String,
    pub tenant_id: String,
    pub subject: Option<String>,
    pub entity: Option<String>,
    pub field: Option<String>,
    pub category: Option<String>,
    pub purpose: Option<String>,
    pub action: InvalidationAction,
}

// ---------------------------------------------------------------------------
// Masking resolution result (per field)
// ---------------------------------------------------------------------------

/// The resolved masking decision for a single field in a mask() call.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FieldMaskingResolution {
    /// JSON path within the payload (e.g., "email", "home.street").
    pub field_path: String,
    /// Qualified field name in the graph (e.g., "User/email").
    pub qualified_field: String,
    /// The resolved masking type to apply.
    pub masking_type: MaskingType,
    /// The consent action found (if any).
    pub consent_action: Option<ConsentAction>,
    /// Which regulation determined the masking type.
    pub regulation_name: Option<String>,
    /// The lawful basis from the purpose→regulation edge.
    pub lawful_basis: Option<String>,
}

// ---------------------------------------------------------------------------
// Evaluate response (from /evaluate API)
// ---------------------------------------------------------------------------

/// Response from the /evaluate API endpoint, used to ingest decisions.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EvaluateApiResponse {
    pub subject: String,
    pub granted: Vec<ConsentScopeDto>,
    pub denied: Vec<ConsentScopeDto>,
    #[serde(default)]
    pub details: HashMap<String, DecisionDetailDto>,
}

/// Consent scope DTO matching the API.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ConsentScopeDto {
    pub category: Option<String>,
    pub field_name: Option<String>,
    pub entity_name: Option<String>,
    pub purpose_name: Option<String>,
}

/// Decision detail from the /evaluate API.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DecisionDetailDto {
    pub effective_status: Option<String>,
    pub resolved_by: Option<String>,
    pub expires_at: Option<String>,
}

// ---------------------------------------------------------------------------
// SDK status
// ---------------------------------------------------------------------------

/// Health/status response from cveil_status().
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SdkStatus {
    pub initialized: bool,
    pub connected: bool,
    pub stale: bool,
    pub graph_node_count: usize,
    pub graph_edge_count: usize,
    pub cached_decisions: usize,
    pub jurisdiction: String,
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn masking_type_ordering() {
        assert!(MaskingType::Plaintext < MaskingType::RedactUpToLast4);
        assert!(MaskingType::RedactUpToLast4 < MaskingType::RedactLocal);
        assert!(MaskingType::RedactLocal < MaskingType::Hash256);
        assert!(MaskingType::Hash256 < MaskingType::RedactAll);

        // Most restrictive wins via max
        let types = vec![
            MaskingType::Plaintext,
            MaskingType::Hash256,
            MaskingType::RedactUpToLast4,
        ];
        assert_eq!(types.into_iter().max(), Some(MaskingType::Hash256));
    }

    #[test]
    fn masking_type_from_str() {
        assert_eq!(MaskingType::from_str("PLAINTEXT"), Some(MaskingType::Plaintext));
        assert_eq!(MaskingType::from_str("REDACT_ALL"), Some(MaskingType::RedactAll));
        assert_eq!(MaskingType::from_str("HASH256"), Some(MaskingType::Hash256));
        assert_eq!(MaskingType::from_str("REDACT_UP_TO_LAST_4"), Some(MaskingType::RedactUpToLast4));
        assert_eq!(MaskingType::from_str("REDACT_LOCAL"), Some(MaskingType::RedactLocal));
        assert_eq!(MaskingType::from_str("UNKNOWN"), None);
    }

    #[test]
    fn cached_decision_expiry() {
        let decision = CachedDecision {
            action: ConsentAction::Grant,
            expires_at: Some(1000),
            cached_at: 500,
        };
        assert!(!decision.is_expired(999));
        assert!(decision.is_expired(1000));
        assert!(decision.is_expired(1001));
    }

    #[test]
    fn cached_decision_no_expiry() {
        let decision = CachedDecision {
            action: ConsentAction::Grant,
            expires_at: None,
            cached_at: 500,
        };
        assert!(!decision.is_expired(u64::MAX));
    }

    #[test]
    fn consent_action_serde_roundtrip() {
        let json = serde_json::to_string(&ConsentAction::Grant).unwrap();
        assert_eq!(json, "\"GRANT\"");
        let back: ConsentAction = serde_json::from_str(&json).unwrap();
        assert_eq!(back, ConsentAction::Grant);
    }

    #[test]
    fn invalidation_message_deserialize() {
        let json = r#"{
            "messageId": "abc-123",
            "tenantId": "t-1",
            "subject": "user-42",
            "entity": null,
            "field": "email",
            "category": null,
            "purpose": "marketing",
            "action": "GRANTED"
        }"#;
        let msg: InvalidationMessage = serde_json::from_str(json).unwrap();
        assert_eq!(msg.message_id, "abc-123");
        assert_eq!(msg.subject, Some("user-42".to_string()));
        assert_eq!(msg.entity, None);
        assert_eq!(msg.action, InvalidationAction::Granted);
    }

    #[test]
    fn graph_page_deserialize() {
        let json = r#"{
            "nodes": [
                {"type": "field", "name": "User/email", "properties": {"entity": "User"}},
                {"type": "purpose", "name": "Marketing", "properties": {}}
            ],
            "edges": [
                {
                    "sourceType": "purpose",
                    "sourceName": "Marketing",
                    "targetType": "regulation",
                    "targetName": "GDPR",
                    "properties": {"lawfulBasis": "consent", "onGranted": "PLAINTEXT", "onDenied": "REDACT_ALL"}
                }
            ],
            "pageInfo": {"nextCursor": null, "hasMore": false, "totalNodes": 2, "totalEdges": 1}
        }"#;
        let page: ConsentGraphPage = serde_json::from_str(json).unwrap();
        assert_eq!(page.nodes.len(), 2);
        assert_eq!(page.edges.len(), 1);
        assert!(!page.page_info.has_more);

        let edge = &page.edges[0];
        assert_eq!(edge.properties.get("lawfulBasis"), Some(&"consent".to_string()));
        assert_eq!(edge.properties.get("onGranted"), Some(&"PLAINTEXT".to_string()));
    }

    #[test]
    fn node_type_roundtrip() {
        for nt in [
            NodeType::Entity, NodeType::Field, NodeType::Category,
            NodeType::Purpose, NodeType::Regulation, NodeType::Jurisdiction,
            NodeType::Policy, NodeType::Subject,
        ] {
            let s = nt.as_str();
            assert_eq!(NodeType::from_str(s), Some(nt));
        }
    }
}
