use crate::cache::DecisionCache;
use crate::graph::{GraphStore, MaskingResolution};
#[allow(unused_imports)]
use crate::models::{
    ConsentAction, FieldMaskingResolution, MaskingType,
};

/// Result of evaluating consent for a single field.
#[derive(Debug)]
pub enum EvalResult {
    /// Consent resolved locally — here's the masking type to apply.
    Resolved(FieldMaskingResolution),
    /// Cache miss — the facade needs to fetch decisions for these policies.
    NeedsFetch {
        qualified_field: String,
        field_path: String,
        policy_names: Vec<String>,
    },
}

/// Evaluates consent for fields against the cached graph and decision cache.
pub struct ConsentEvaluator;

impl ConsentEvaluator {
    /// Evaluate consent for a single field and resolve its MaskingType.
    ///
    /// Steps:
    /// 1. Find policy nodes for this field + purpose (field-level and category-level)
    /// 2. Check decision cache for subject→policy consent action
    /// 3. Apply field-over-category precedence
    /// 4. Resolve MaskingType via graph traversal (purpose→regulation filtered by jurisdiction)
    /// 5. Multi-regulation: most restrictive wins
    pub fn evaluate_field(
        graph: &GraphStore,
        cache: &mut DecisionCache,
        subject: &str,
        entity: &str,
        field_path: &str,
        purpose: &str,
        jurisdiction: &str,
        now_ms: u64,
        conservative_fallback: bool,
    ) -> EvalResult {
        let qualified_field = format!("{entity}/{field_path}");

        // Build policy names to check (field-level first, then category-level)
        let field_policy = format!("FIELD:{qualified_field}:{purpose}");
        let categories = graph.categories_for_field(&qualified_field);
        let category_policies: Vec<String> = categories
            .iter()
            .map(|cat| format!("CAT:{cat}:{purpose}"))
            .collect();

        // Check field-level policy first (field precedence over category)
        let field_key = DecisionCache::cache_key(subject, &field_policy);
        if let Some(decision) = cache.get(&field_key, now_ms) {
            let consent_granted = decision.is_granted();
            let action = decision.action;
            let resolution =
                graph.resolve_masking_for_field(purpose, jurisdiction, consent_granted);
            return EvalResult::Resolved(make_field_resolution(
                field_path,
                &qualified_field,
                resolution,
                Some(action),
            ));
        }

        // Check category-level policies
        for cat_policy in &category_policies {
            let cat_key = DecisionCache::cache_key(subject, cat_policy);
            if let Some(decision) = cache.get(&cat_key, now_ms) {
                let consent_granted = decision.is_granted();
                let action = decision.action;
                let resolution =
                    graph.resolve_masking_for_field(purpose, jurisdiction, consent_granted);
                return EvalResult::Resolved(make_field_resolution(
                    field_path,
                    &qualified_field,
                    resolution,
                    Some(action),
                ));
            }
        }

        // Cache miss — no decision found
        if conservative_fallback {
            // Deny-by-default: no consent record → apply onDenied MaskingType
            let resolution = graph.resolve_masking_for_field(purpose, jurisdiction, false);
            return EvalResult::Resolved(make_field_resolution(
                field_path,
                &qualified_field,
                resolution,
                None,
            ));
        }

        // Signal that the facade needs to fetch decisions
        let mut policy_names = vec![field_policy];
        policy_names.extend(category_policies);
        EvalResult::NeedsFetch {
            qualified_field,
            field_path: field_path.to_string(),
            policy_names,
        }
    }

    /// Evaluate consent for multiple fields at once.
    /// Returns a mix of resolved and needs-fetch results.
    pub fn evaluate_fields(
        graph: &GraphStore,
        cache: &mut DecisionCache,
        subject: &str,
        entity: &str,
        field_paths: &[String],
        purpose: &str,
        jurisdiction: &str,
        now_ms: u64,
        conservative_fallback: bool,
    ) -> Vec<EvalResult> {
        field_paths
            .iter()
            .map(|fp| {
                Self::evaluate_field(
                    graph,
                    cache,
                    subject,
                    entity,
                    fp,
                    purpose,
                    jurisdiction,
                    now_ms,
                    conservative_fallback,
                )
            })
            .collect()
    }

    /// Discover which fields in the graph are registered for a given entity.
    /// Used to filter JSON field paths to only those that need consent checks.
    pub fn registered_field_paths(graph: &GraphStore, entity: &str) -> Vec<String> {
        let prefix = format!("{entity}/");
        graph
            .fields_for_entity(entity)
            .into_iter()
            .map(|qf| qf.strip_prefix(&prefix).unwrap_or(&qf).to_string())
            .collect()
    }
}

fn make_field_resolution(
    field_path: &str,
    qualified_field: &str,
    resolution: MaskingResolution,
    consent_action: Option<ConsentAction>,
) -> FieldMaskingResolution {
    FieldMaskingResolution {
        field_path: field_path.to_string(),
        qualified_field: qualified_field.to_string(),
        masking_type: resolution.masking_type,
        consent_action,
        regulation_name: resolution.regulation_name,
        lawful_basis: resolution.lawful_basis,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::models::{CachedDecision, ConsentGraphPage};

    fn build_test_graph() -> GraphStore {
        let json = r#"{
            "nodes": [
                {"type": "entity", "name": "User", "properties": {}},
                {"type": "field", "name": "User/email", "properties": {}},
                {"type": "field", "name": "User/phone", "properties": {}},
                {"type": "field", "name": "User/ssn", "properties": {}},
                {"type": "category", "name": "PII", "properties": {}},
                {"type": "category", "name": "Sensitive", "properties": {}},
                {"type": "purpose", "name": "Marketing", "properties": {}},
                {"type": "regulation", "name": "GDPR", "properties": {}},
                {"type": "jurisdiction", "name": "EU", "properties": {}},
                {"type": "policy", "name": "FIELD:User/email:Marketing", "properties": {"policyType": "FIELD"}},
                {"type": "policy", "name": "CAT:PII:Marketing", "properties": {"policyType": "CATEGORY"}},
                {"type": "policy", "name": "CAT:Sensitive:Marketing", "properties": {"policyType": "CATEGORY"}}
            ],
            "edges": [
                {"sourceType": "field", "sourceName": "User/email", "targetType": "category", "targetName": "PII", "properties": {}},
                {"sourceType": "field", "sourceName": "User/phone", "targetType": "category", "targetName": "PII", "properties": {}},
                {"sourceType": "field", "sourceName": "User/ssn", "targetType": "category", "targetName": "Sensitive", "properties": {}},
                {"sourceType": "purpose", "sourceName": "Marketing", "targetType": "regulation", "targetName": "GDPR", "properties": {"lawfulBasis": "consent", "onGranted": "PLAINTEXT", "onDenied": "REDACT_ALL"}},
                {"sourceType": "regulation", "sourceName": "GDPR", "targetType": "jurisdiction", "targetName": "EU", "properties": {}},
                {"sourceType": "purpose", "sourceName": "Marketing", "targetType": "policy", "targetName": "FIELD:User/email:Marketing", "properties": {}},
                {"sourceType": "purpose", "sourceName": "Marketing", "targetType": "policy", "targetName": "CAT:PII:Marketing", "properties": {}},
                {"sourceType": "purpose", "sourceName": "Marketing", "targetType": "policy", "targetName": "CAT:Sensitive:Marketing", "properties": {}}
            ],
            "pageInfo": {"nextCursor": null, "hasMore": false, "totalNodes": 12, "totalEdges": 8}
        }"#;
        let page: ConsentGraphPage = serde_json::from_str(json).unwrap();
        let mut store = GraphStore::new();
        store.ingest_page(&page);
        store
    }

    #[test]
    fn field_level_grant_resolves_plaintext() {
        let graph = build_test_graph();
        let mut cache = DecisionCache::new(100, 300_000);

        // Cache a GRANT for the field-level policy
        let key = DecisionCache::cache_key("user-1", "FIELD:User/email:Marketing");
        cache.put(
            key,
            CachedDecision { action: ConsentAction::Grant, expires_at: None, cached_at: 1000 },
        );

        let result = ConsentEvaluator::evaluate_field(
            &graph, &mut cache, "user-1", "User", "email", "Marketing", "EU", 1001, true,
        );

        match result {
            EvalResult::Resolved(res) => {
                assert_eq!(res.masking_type, MaskingType::Plaintext);
                assert_eq!(res.consent_action, Some(ConsentAction::Grant));
                assert_eq!(res.field_path, "email");
                assert_eq!(res.qualified_field, "User/email");
            }
            _ => panic!("Expected Resolved"),
        }
    }

    #[test]
    fn field_level_revoke_resolves_redact_all() {
        let graph = build_test_graph();
        let mut cache = DecisionCache::new(100, 300_000);

        let key = DecisionCache::cache_key("user-1", "FIELD:User/email:Marketing");
        cache.put(
            key,
            CachedDecision { action: ConsentAction::Revoke, expires_at: None, cached_at: 1000 },
        );

        let result = ConsentEvaluator::evaluate_field(
            &graph, &mut cache, "user-1", "User", "email", "Marketing", "EU", 1001, true,
        );

        match result {
            EvalResult::Resolved(res) => {
                assert_eq!(res.masking_type, MaskingType::RedactAll);
            }
            _ => panic!("Expected Resolved"),
        }
    }

    #[test]
    fn category_level_fallback_when_no_field_policy() {
        let graph = build_test_graph();
        let mut cache = DecisionCache::new(100, 300_000);

        // "phone" has no field-level policy, but it's in "PII" category
        let key = DecisionCache::cache_key("user-1", "CAT:PII:Marketing");
        cache.put(
            key,
            CachedDecision { action: ConsentAction::Grant, expires_at: None, cached_at: 1000 },
        );

        let result = ConsentEvaluator::evaluate_field(
            &graph, &mut cache, "user-1", "User", "phone", "Marketing", "EU", 1001, true,
        );

        match result {
            EvalResult::Resolved(res) => {
                assert_eq!(res.masking_type, MaskingType::Plaintext);
            }
            _ => panic!("Expected Resolved"),
        }
    }

    #[test]
    fn field_level_takes_precedence_over_category() {
        let graph = build_test_graph();
        let mut cache = DecisionCache::new(100, 300_000);

        // Field-level says REVOKE, category-level says GRANT
        let fk = DecisionCache::cache_key("user-1", "FIELD:User/email:Marketing");
        cache.put(
            fk,
            CachedDecision { action: ConsentAction::Revoke, expires_at: None, cached_at: 1000 },
        );
        let ck = DecisionCache::cache_key("user-1", "CAT:PII:Marketing");
        cache.put(
            ck,
            CachedDecision { action: ConsentAction::Grant, expires_at: None, cached_at: 1000 },
        );

        let result = ConsentEvaluator::evaluate_field(
            &graph, &mut cache, "user-1", "User", "email", "Marketing", "EU", 1001, true,
        );

        match result {
            EvalResult::Resolved(res) => {
                // Field-level REVOKE wins over category GRANT
                assert_eq!(res.masking_type, MaskingType::RedactAll);
                assert_eq!(res.consent_action, Some(ConsentAction::Revoke));
            }
            _ => panic!("Expected Resolved"),
        }
    }

    #[test]
    fn no_consent_record_conservative_denies() {
        let graph = build_test_graph();
        let mut cache = DecisionCache::new(100, 300_000);

        let result = ConsentEvaluator::evaluate_field(
            &graph, &mut cache, "user-1", "User", "email", "Marketing", "EU", 1001,
            true, // conservative fallback
        );

        match result {
            EvalResult::Resolved(res) => {
                assert_eq!(res.masking_type, MaskingType::RedactAll);
                assert_eq!(res.consent_action, None);
            }
            _ => panic!("Expected Resolved"),
        }
    }

    #[test]
    fn no_consent_record_needs_fetch_when_not_conservative() {
        let graph = build_test_graph();
        let mut cache = DecisionCache::new(100, 300_000);

        let result = ConsentEvaluator::evaluate_field(
            &graph, &mut cache, "user-1", "User", "email", "Marketing", "EU", 1001,
            false, // NOT conservative — signal fetch needed
        );

        match result {
            EvalResult::NeedsFetch { policy_names, .. } => {
                assert!(policy_names.contains(&"FIELD:User/email:Marketing".to_string()));
                // Should also include category policies
                assert!(policy_names.iter().any(|p| p.starts_with("CAT:PII:")));
            }
            _ => panic!("Expected NeedsFetch"),
        }
    }

    #[test]
    fn registered_field_paths_strips_entity_prefix() {
        let graph = build_test_graph();
        let paths = ConsentEvaluator::registered_field_paths(&graph, "User");
        assert!(paths.contains(&"email".to_string()));
        assert!(paths.contains(&"phone".to_string()));
        assert!(paths.contains(&"ssn".to_string()));
    }

    #[test]
    fn evaluate_fields_batch() {
        let graph = build_test_graph();
        let mut cache = DecisionCache::new(100, 300_000);

        let fk = DecisionCache::cache_key("user-1", "FIELD:User/email:Marketing");
        cache.put(
            fk,
            CachedDecision { action: ConsentAction::Grant, expires_at: None, cached_at: 1000 },
        );

        let fields = vec!["email".to_string(), "phone".to_string()];
        let results = ConsentEvaluator::evaluate_fields(
            &graph, &mut cache, "user-1", "User", &fields, "Marketing", "EU", 1001, true,
        );

        assert_eq!(results.len(), 2);
        // email: granted via field policy → PLAINTEXT
        match &results[0] {
            EvalResult::Resolved(res) => assert_eq!(res.masking_type, MaskingType::Plaintext),
            _ => panic!("Expected Resolved for email"),
        }
        // phone: no consent → conservative deny → REDACT_ALL
        match &results[1] {
            EvalResult::Resolved(res) => assert_eq!(res.masking_type, MaskingType::RedactAll),
            _ => panic!("Expected Resolved for phone"),
        }
    }
}
