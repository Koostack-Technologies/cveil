use std::collections::VecDeque;

use crate::cache::DecisionCache;
use crate::graph::GraphStore;
use crate::models::{InvalidationAction, InvalidationMessage};

/// Actions the facade needs to take after processing an invalidation.
#[derive(Debug, PartialEq, Eq)]
pub enum InvalidationEffect {
    /// No additional action needed — cache was updated locally.
    None,
    /// Facade should re-fetch the full graph topology.
    RefreshFullGraph,
    /// Facade should re-fetch a subgraph rooted at this node.
    RefreshSubgraph { node_type: String, node_name: String },
    /// Facade should flush and re-fetch everything (reconnect scenario).
    FlushAndReload,
}

/// Processes SSE invalidation messages, dispatching cache evictions.
pub struct InvalidationHandler {
    /// Ring buffer for message deduplication.
    seen_ids: VecDeque<String>,
    /// Max size of dedup window.
    dedup_window: usize,
}

impl InvalidationHandler {
    pub fn new(dedup_window: usize) -> Self {
        Self {
            seen_ids: VecDeque::with_capacity(dedup_window),
            dedup_window,
        }
    }

    /// Process an invalidation message. Returns the effect the facade should act on.
    ///
    /// Returns `None` if the message was a duplicate.
    pub fn process(
        &mut self,
        msg: &InvalidationMessage,
        graph: &mut GraphStore,
        decisions: &mut DecisionCache,
    ) -> Option<InvalidationEffect> {
        // Dedup check
        if self.seen_ids.contains(&msg.message_id) {
            return None;
        }
        self.seen_ids.push_back(msg.message_id.clone());
        if self.seen_ids.len() > self.dedup_window {
            self.seen_ids.pop_front();
        }

        Some(self.dispatch(msg, graph, decisions))
    }

    fn dispatch(
        &self,
        msg: &InvalidationMessage,
        graph: &mut GraphStore,
        decisions: &mut DecisionCache,
    ) -> InvalidationEffect {
        match msg.action {
            // --- Subject consent changes ---
            InvalidationAction::Granted | InvalidationAction::Revoked => {
                // Evict specific subject+policy decision
                if let (Some(subject), Some(entity), Some(purpose)) =
                    (&msg.subject, &msg.entity, &msg.purpose)
                {
                    if let Some(field) = &msg.field {
                        let policy = format!("FIELD:{entity}/{field}:{purpose}");
                        let key = DecisionCache::cache_key(subject, &policy);
                        decisions.evict(&key);
                    }
                    if let Some(category) = &msg.category {
                        let policy = format!("CAT:{category}:{purpose}");
                        let key = DecisionCache::cache_key(subject, &policy);
                        decisions.evict(&key);
                    }
                } else if let Some(subject) = &msg.subject {
                    // Broad eviction for subject
                    decisions.evict_subject(subject);
                }
                InvalidationEffect::None
            }

            InvalidationAction::AllRevoked => {
                if let Some(subject) = &msg.subject {
                    decisions.evict_subject(subject);
                } else {
                    // Null subject → flush all decisions
                    decisions.flush();
                }
                InvalidationEffect::None
            }

            InvalidationAction::ConsentEntryChanged => {
                if let Some(subject) = &msg.subject {
                    if let (Some(entity), Some(purpose)) = (&msg.entity, &msg.purpose) {
                        if let Some(field) = &msg.field {
                            let policy = format!("FIELD:{entity}/{field}:{purpose}");
                            let key = DecisionCache::cache_key(subject, &policy);
                            decisions.evict(&key);
                        }
                        if let Some(category) = &msg.category {
                            let policy = format!("CAT:{category}:{purpose}");
                            let key = DecisionCache::cache_key(subject, &policy);
                            decisions.evict(&key);
                        }
                    } else {
                        decisions.evict_subject(subject);
                    }
                }
                InvalidationEffect::None
            }

            // --- Graph topology changes ---
            InvalidationAction::FieldRemoved => {
                if let Some(field) = &msg.field {
                    if let Some(entity) = &msg.entity {
                        let qualified = format!("{entity}/{field}");
                        graph.remove_node("field", &qualified);
                        decisions.evict_matching(&qualified);
                    }
                }
                InvalidationEffect::None
            }

            InvalidationAction::FieldChanged => {
                if let (Some(entity), Some(field)) = (&msg.entity, &msg.field) {
                    let qualified = format!("{entity}/{field}");
                    decisions.evict_matching(&qualified);
                    InvalidationEffect::RefreshSubgraph {
                        node_type: "field".to_string(),
                        node_name: qualified,
                    }
                } else {
                    InvalidationEffect::RefreshFullGraph
                }
            }

            InvalidationAction::EntityRemoved => {
                if let Some(entity) = &msg.entity {
                    // Remove all fields for this entity, then the entity
                    let fields = graph.fields_for_entity(entity);
                    for f in &fields {
                        graph.remove_node("field", f);
                        decisions.evict_matching(f);
                    }
                    graph.remove_node("entity", entity);
                }
                InvalidationEffect::None
            }

            InvalidationAction::CategoryChanged => {
                if let Some(category) = &msg.category {
                    decisions.evict_matching(category);
                    InvalidationEffect::RefreshSubgraph {
                        node_type: "category".to_string(),
                        node_name: category.clone(),
                    }
                } else {
                    InvalidationEffect::RefreshFullGraph
                }
            }

            InvalidationAction::PurposeChanged => {
                if let Some(purpose) = &msg.purpose {
                    decisions.evict_matching(purpose);
                    InvalidationEffect::RefreshSubgraph {
                        node_type: "purpose".to_string(),
                        node_name: purpose.clone(),
                    }
                } else {
                    decisions.flush();
                    InvalidationEffect::RefreshFullGraph
                }
            }

            InvalidationAction::RegulationChanged => {
                if let Some(regulation) = &msg.field {
                    // regulation name might come in `field` property for metadata events
                    InvalidationEffect::RefreshSubgraph {
                        node_type: "regulation".to_string(),
                        node_name: regulation.clone(),
                    }
                } else {
                    InvalidationEffect::RefreshFullGraph
                }
            }

            InvalidationAction::JurisdictionChanged => {
                InvalidationEffect::RefreshFullGraph
            }

            InvalidationAction::PolicyChanged => {
                if let Some(purpose) = &msg.purpose {
                    decisions.evict_matching(purpose);
                    InvalidationEffect::RefreshSubgraph {
                        node_type: "purpose".to_string(),
                        node_name: purpose.clone(),
                    }
                } else {
                    decisions.flush();
                    InvalidationEffect::RefreshFullGraph
                }
            }

            InvalidationAction::SubjectChanged => {
                if let Some(subject) = &msg.subject {
                    decisions.evict_subject(subject);
                }
                InvalidationEffect::None
            }
        }
    }

    /// Handle SSE reconnection: flush everything and signal facade to reload.
    pub fn on_reconnect(
        &mut self,
        graph: &mut GraphStore,
        decisions: &mut DecisionCache,
    ) -> InvalidationEffect {
        self.seen_ids.clear();
        graph.flush();
        decisions.flush();
        InvalidationEffect::FlushAndReload
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::models::ConsentGraphPage;

    fn build_graph_and_cache() -> (GraphStore, DecisionCache) {
        let json = r#"{
            "nodes": [
                {"type": "entity", "name": "User", "properties": {}},
                {"type": "field", "name": "User/email", "properties": {}},
                {"type": "field", "name": "User/phone", "properties": {}}
            ],
            "edges": [],
            "pageInfo": {"nextCursor": null, "hasMore": false, "totalNodes": 3, "totalEdges": 0}
        }"#;
        let page: ConsentGraphPage = serde_json::from_str(json).unwrap();
        let mut store = GraphStore::new();
        store.ingest_page(&page);
        (store, DecisionCache::new(100, 300_000))
    }

    fn msg(id: &str, action: InvalidationAction) -> InvalidationMessage {
        InvalidationMessage {
            message_id: id.to_string(),
            tenant_id: "t1".to_string(),
            subject: None,
            entity: None,
            field: None,
            category: None,
            purpose: None,
            action,
        }
    }

    #[test]
    fn dedup_skips_repeated_messages() {
        let (mut graph, mut cache) = build_graph_and_cache();
        let mut handler = InvalidationHandler::new(500);

        let m = msg("msg-1", InvalidationAction::FieldChanged);
        assert!(handler.process(&m, &mut graph, &mut cache).is_some());
        assert!(handler.process(&m, &mut graph, &mut cache).is_none()); // dup
    }

    #[test]
    fn dedup_ring_buffer_evicts_old() {
        let (mut graph, mut cache) = build_graph_and_cache();
        let mut handler = InvalidationHandler::new(3);

        // Fill the ring buffer
        for i in 0..3 {
            let m = msg(&format!("msg-{i}"), InvalidationAction::FieldChanged);
            handler.process(&m, &mut graph, &mut cache);
        }

        // msg-0 should be evicted when we add msg-3
        let m3 = msg("msg-3", InvalidationAction::FieldChanged);
        handler.process(&m3, &mut graph, &mut cache);

        // msg-0 should now be accepted again (no longer in dedup window)
        let m0 = msg("msg-0", InvalidationAction::FieldChanged);
        assert!(handler.process(&m0, &mut graph, &mut cache).is_some());
    }

    #[test]
    fn granted_evicts_specific_decision() {
        let (mut graph, mut cache) = build_graph_and_cache();
        let mut handler = InvalidationHandler::new(500);

        // Pre-populate decision cache
        use crate::models::{CachedDecision, ConsentAction};
        let key = DecisionCache::cache_key("user-1", "FIELD:User/email:Marketing");
        cache.put(
            key.clone(),
            CachedDecision { action: ConsentAction::Grant, expires_at: None, cached_at: 1000 },
        );
        assert_eq!(cache.len(), 1);

        let mut m = msg("m1", InvalidationAction::Granted);
        m.subject = Some("user-1".to_string());
        m.entity = Some("User".to_string());
        m.field = Some("email".to_string());
        m.purpose = Some("Marketing".to_string());

        handler.process(&m, &mut graph, &mut cache);
        assert_eq!(cache.len(), 0);
    }

    #[test]
    fn all_revoked_flushes_subject() {
        let (mut graph, mut cache) = build_graph_and_cache();
        let mut handler = InvalidationHandler::new(500);

        use crate::models::{CachedDecision, ConsentAction};
        cache.put(
            DecisionCache::cache_key("user-1", "p1"),
            CachedDecision { action: ConsentAction::Grant, expires_at: None, cached_at: 1000 },
        );
        cache.put(
            DecisionCache::cache_key("user-1", "p2"),
            CachedDecision { action: ConsentAction::Grant, expires_at: None, cached_at: 1000 },
        );
        cache.put(
            DecisionCache::cache_key("user-2", "p1"),
            CachedDecision { action: ConsentAction::Grant, expires_at: None, cached_at: 1000 },
        );

        let mut m = msg("m1", InvalidationAction::AllRevoked);
        m.subject = Some("user-1".to_string());

        handler.process(&m, &mut graph, &mut cache);
        assert_eq!(cache.len(), 1); // Only user-2's entry remains
    }

    #[test]
    fn field_removed_cascades() {
        let (mut graph, mut cache) = build_graph_and_cache();
        let mut handler = InvalidationHandler::new(500);

        assert!(graph.is_field_registered("User/email"));

        let mut m = msg("m1", InvalidationAction::FieldRemoved);
        m.entity = Some("User".to_string());
        m.field = Some("email".to_string());

        handler.process(&m, &mut graph, &mut cache);
        assert!(!graph.is_field_registered("User/email"));
    }

    #[test]
    fn on_reconnect_flushes_everything() {
        let (mut graph, mut cache) = build_graph_and_cache();
        let mut handler = InvalidationHandler::new(500);

        use crate::models::{CachedDecision, ConsentAction};
        cache.put(
            "k1".into(),
            CachedDecision { action: ConsentAction::Grant, expires_at: None, cached_at: 1000 },
        );

        let effect = handler.on_reconnect(&mut graph, &mut cache);
        assert_eq!(effect, InvalidationEffect::FlushAndReload);
        assert!(cache.is_empty());
        assert_eq!(graph.node_count(), 0);
    }

    #[test]
    fn purpose_changed_requests_subgraph_refresh() {
        let (mut graph, mut cache) = build_graph_and_cache();
        let mut handler = InvalidationHandler::new(500);

        let mut m = msg("m1", InvalidationAction::PurposeChanged);
        m.purpose = Some("Marketing".to_string());

        let effect = handler.process(&m, &mut graph, &mut cache).unwrap();
        assert_eq!(
            effect,
            InvalidationEffect::RefreshSubgraph {
                node_type: "purpose".to_string(),
                node_name: "Marketing".to_string(),
            }
        );
    }
}
