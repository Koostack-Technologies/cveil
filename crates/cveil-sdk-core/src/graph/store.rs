use std::collections::{HashMap, HashSet};
use crate::models::{
    ConsentGraphPage, MaskingType, PurposeRegulationLink,
};

/// In-memory property graph mirroring the Redis consent graph.
///
/// Stores nodes indexed by `(type, name)`, directed edges with adjacency lists,
/// and edge properties. Provides indexed lookups for consent evaluation traversal.
pub struct GraphStore {
    /// Node properties: `(type_str, name)` → properties map.
    nodes: HashMap<(String, String), HashMap<String, String>>,
    /// Out-edges: `(from_type, from_name)` → set of `(to_type, to_name)`.
    out_edges: HashMap<(String, String), HashSet<(String, String)>>,
    /// In-edges: `(to_type, to_name)` → set of `(from_type, from_name)`.
    in_edges: HashMap<(String, String), HashSet<(String, String)>>,
    /// Edge properties: `(from_type, from_name, to_type, to_name)` → properties.
    edge_props: HashMap<(String, String, String, String), HashMap<String, String>>,

    // --- Derived indexes (rebuilt on ingest) ---

    /// entity name → set of qualified field names ("Entity/field").
    entity_fields: HashMap<String, HashSet<String>>,
    /// qualified field → set of category names.
    field_categories: HashMap<String, HashSet<String>>,
    /// category name → set of qualified fields.
    category_fields: HashMap<String, HashSet<String>>,
    /// purpose name → set of policy node names.
    purpose_policies: HashMap<String, HashSet<String>>,
    /// purpose name → list of regulation links (with masking types).
    purpose_regulations: HashMap<String, Vec<PurposeRegulationLink>>,
    /// regulation name → set of jurisdiction names.
    regulation_jurisdictions: HashMap<String, HashSet<String>>,
    /// jurisdiction name → set of regulation names.
    jurisdiction_regulations: HashMap<String, HashSet<String>>,
}

impl GraphStore {
    pub fn new() -> Self {
        Self {
            nodes: HashMap::new(),
            out_edges: HashMap::new(),
            in_edges: HashMap::new(),
            edge_props: HashMap::new(),
            entity_fields: HashMap::new(),
            field_categories: HashMap::new(),
            category_fields: HashMap::new(),
            purpose_policies: HashMap::new(),
            purpose_regulations: HashMap::new(),
            regulation_jurisdictions: HashMap::new(),
            jurisdiction_regulations: HashMap::new(),
        }
    }

    // -----------------------------------------------------------------------
    // Ingestion
    // -----------------------------------------------------------------------

    /// Ingest a page of the consent graph from the API response.
    /// Can be called multiple times for paginated results.
    pub fn ingest_page(&mut self, page: &ConsentGraphPage) {
        for node in &page.nodes {
            self.put_node(&node.node_type, &node.name, &node.properties);
        }
        for edge in &page.edges {
            self.put_edge(
                &edge.source_type,
                &edge.source_name,
                &edge.target_type,
                &edge.target_name,
                &edge.properties,
            );
        }
    }

    fn put_node(&mut self, type_str: &str, name: &str, properties: &HashMap<String, String>) {
        let key = (type_str.to_string(), name.to_string());
        self.nodes.insert(key, properties.clone());
        self.update_node_index(type_str, name, properties);
    }

    fn put_edge(
        &mut self,
        from_type: &str,
        from_name: &str,
        to_type: &str,
        to_name: &str,
        properties: &HashMap<String, String>,
    ) {
        let from_key = (from_type.to_string(), from_name.to_string());
        let to_key = (to_type.to_string(), to_name.to_string());

        self.out_edges
            .entry(from_key.clone())
            .or_default()
            .insert(to_key.clone());
        self.in_edges
            .entry(to_key)
            .or_default()
            .insert(from_key);

        if !properties.is_empty() {
            let ep_key = (
                from_type.to_string(),
                from_name.to_string(),
                to_type.to_string(),
                to_name.to_string(),
            );
            self.edge_props.insert(ep_key, properties.clone());
        }

        self.update_edge_index(from_type, from_name, to_type, to_name, properties);
    }

    // -----------------------------------------------------------------------
    // Index maintenance
    // -----------------------------------------------------------------------

    fn update_node_index(
        &mut self,
        type_str: &str,
        name: &str,
        _properties: &HashMap<String, String>,
    ) {
        match type_str {
            "field" => {
                // Field names are qualified: "Entity/fieldName"
                if let Some(pos) = name.find('/') {
                    let entity = &name[..pos];
                    self.entity_fields
                        .entry(entity.to_string())
                        .or_default()
                        .insert(name.to_string());
                }
            }
            _ => {}
        }
    }

    fn update_edge_index(
        &mut self,
        from_type: &str,
        from_name: &str,
        to_type: &str,
        to_name: &str,
        properties: &HashMap<String, String>,
    ) {
        match (from_type, to_type) {
            ("field", "category") => {
                self.field_categories
                    .entry(from_name.to_string())
                    .or_default()
                    .insert(to_name.to_string());
                self.category_fields
                    .entry(to_name.to_string())
                    .or_default()
                    .insert(from_name.to_string());
            }
            ("purpose", "policy") => {
                self.purpose_policies
                    .entry(from_name.to_string())
                    .or_default()
                    .insert(to_name.to_string());
            }
            ("purpose", "regulation") => {
                let link = PurposeRegulationLink {
                    purpose_name: from_name.to_string(),
                    regulation_name: to_name.to_string(),
                    lawful_basis: properties.get("lawfulBasis").cloned(),
                    on_granted: properties
                        .get("onGranted")
                        .and_then(|s| MaskingType::from_str(s))
                        .unwrap_or(MaskingType::Plaintext),
                    on_denied: properties
                        .get("onDenied")
                        .and_then(|s| MaskingType::from_str(s))
                        .unwrap_or(MaskingType::RedactAll),
                };
                self.purpose_regulations
                    .entry(from_name.to_string())
                    .or_default()
                    .push(link);
            }
            ("regulation", "jurisdiction") => {
                self.regulation_jurisdictions
                    .entry(from_name.to_string())
                    .or_default()
                    .insert(to_name.to_string());
                self.jurisdiction_regulations
                    .entry(to_name.to_string())
                    .or_default()
                    .insert(from_name.to_string());
            }
            // policy→field and policy→category edges are captured via purpose→policy
            _ => {}
        }
    }

    // -----------------------------------------------------------------------
    // Indexed lookups
    // -----------------------------------------------------------------------

    /// Returns all qualified field names for an entity (e.g., "User" → {"User/email", "User/phone"}).
    pub fn fields_for_entity(&self, entity: &str) -> HashSet<String> {
        self.entity_fields
            .get(entity)
            .cloned()
            .unwrap_or_default()
    }

    /// Returns all category names a qualified field belongs to.
    pub fn categories_for_field(&self, qualified_field: &str) -> HashSet<String> {
        self.field_categories
            .get(qualified_field)
            .cloned()
            .unwrap_or_default()
    }

    /// Returns all qualified fields in a category.
    pub fn fields_for_category(&self, category: &str) -> HashSet<String> {
        self.category_fields
            .get(category)
            .cloned()
            .unwrap_or_default()
    }

    /// Returns all policy node names under a purpose.
    pub fn policies_for_purpose(&self, purpose: &str) -> HashSet<String> {
        self.purpose_policies
            .get(purpose)
            .cloned()
            .unwrap_or_default()
    }

    /// Returns all purpose→regulation links for a purpose, with masking types.
    pub fn regulations_for_purpose(&self, purpose: &str) -> &[PurposeRegulationLink] {
        self.purpose_regulations
            .get(purpose)
            .map(|v| v.as_slice())
            .unwrap_or(&[])
    }

    /// Returns all jurisdiction names a regulation applies in.
    pub fn jurisdictions_for_regulation(&self, regulation: &str) -> HashSet<String> {
        self.regulation_jurisdictions
            .get(regulation)
            .cloned()
            .unwrap_or_default()
    }

    /// Returns all regulations linked to a jurisdiction.
    pub fn regulations_for_jurisdiction(&self, jurisdiction: &str) -> HashSet<String> {
        self.jurisdiction_regulations
            .get(jurisdiction)
            .cloned()
            .unwrap_or_default()
    }

    /// Check whether a qualified field name is registered in the graph.
    pub fn is_field_registered(&self, qualified_field: &str) -> bool {
        self.nodes.contains_key(&("field".to_string(), qualified_field.to_string()))
    }

    /// Get node properties.
    pub fn get_node_properties(&self, type_str: &str, name: &str) -> Option<&HashMap<String, String>> {
        self.nodes.get(&(type_str.to_string(), name.to_string()))
    }

    /// Get edge properties.
    pub fn get_edge_properties(
        &self,
        from_type: &str,
        from_name: &str,
        to_type: &str,
        to_name: &str,
    ) -> Option<&HashMap<String, String>> {
        self.edge_props.get(&(
            from_type.to_string(),
            from_name.to_string(),
            to_type.to_string(),
            to_name.to_string(),
        ))
    }

    /// Get out-edges from a node.
    pub fn get_out_edges(&self, type_str: &str, name: &str) -> HashSet<(String, String)> {
        self.out_edges
            .get(&(type_str.to_string(), name.to_string()))
            .cloned()
            .unwrap_or_default()
    }

    // -----------------------------------------------------------------------
    // Critical traversal: resolve MaskingType for a field
    // -----------------------------------------------------------------------

    /// Resolve the MaskingType for a specific field under a purpose in a jurisdiction.
    ///
    /// Traversal: jurisdiction → regulations → purpose→regulation edges → read onGranted/onDenied.
    /// If multiple regulations apply, the **most restrictive** MaskingType wins.
    ///
    /// Returns `(on_granted, on_denied, regulation_name, lawful_basis)`.
    pub fn resolve_masking_for_field(
        &self,
        purpose: &str,
        jurisdiction: &str,
        consent_granted: bool,
    ) -> MaskingResolution {
        let reg_links = self.regulations_for_purpose(purpose);
        if reg_links.is_empty() {
            // No regulations linked to this purpose — fall back to most restrictive
            return MaskingResolution {
                masking_type: MaskingType::RedactAll,
                regulation_name: None,
                lawful_basis: None,
            };
        }

        // Find regulations that apply in the target jurisdiction
        let applicable: Vec<&PurposeRegulationLink> = reg_links
            .iter()
            .filter(|link| {
                self.jurisdictions_for_regulation(&link.regulation_name)
                    .contains(jurisdiction)
            })
            .collect();

        if applicable.is_empty() {
            // No regulations for this jurisdiction — fall back to most restrictive
            return MaskingResolution {
                masking_type: MaskingType::RedactAll,
                regulation_name: None,
                lawful_basis: None,
            };
        }

        // Resolve: most restrictive wins across all applicable regulations
        let mut best_type = MaskingType::Plaintext;
        let mut best_link: Option<&PurposeRegulationLink> = None;

        for link in &applicable {
            let mt = if consent_granted {
                link.on_granted
            } else {
                link.on_denied
            };
            if mt > best_type {
                best_type = mt;
                best_link = Some(link);
            }
        }

        MaskingResolution {
            masking_type: best_type,
            regulation_name: best_link.map(|l| l.regulation_name.clone()),
            lawful_basis: best_link.and_then(|l| l.lawful_basis.clone()),
        }
    }

    // -----------------------------------------------------------------------
    // Cache management
    // -----------------------------------------------------------------------

    /// Flush the entire graph cache.
    pub fn flush(&mut self) {
        self.nodes.clear();
        self.out_edges.clear();
        self.in_edges.clear();
        self.edge_props.clear();
        self.entity_fields.clear();
        self.field_categories.clear();
        self.category_fields.clear();
        self.purpose_policies.clear();
        self.purpose_regulations.clear();
        self.regulation_jurisdictions.clear();
        self.jurisdiction_regulations.clear();
    }

    /// Total number of nodes in the graph.
    pub fn node_count(&self) -> usize {
        self.nodes.len()
    }

    /// Total number of edges in the graph.
    pub fn edge_count(&self) -> usize {
        self.edge_props.len()
            + self.out_edges.values().map(|s| s.len()).sum::<usize>()
                .saturating_sub(self.edge_props.len())
    }

    /// Remove a specific node and cascade-delete its edges.
    pub fn remove_node(&mut self, type_str: &str, name: &str) {
        let key = (type_str.to_string(), name.to_string());

        // Remove out-edges
        if let Some(targets) = self.out_edges.remove(&key) {
            for (tt, tn) in &targets {
                if let Some(ins) = self.in_edges.get_mut(&(tt.clone(), tn.clone())) {
                    ins.remove(&key);
                }
                self.edge_props.remove(&(
                    type_str.to_string(), name.to_string(),
                    tt.clone(), tn.clone(),
                ));
            }
        }

        // Remove in-edges
        if let Some(sources) = self.in_edges.remove(&key) {
            for (st, sn) in &sources {
                if let Some(outs) = self.out_edges.get_mut(&(st.clone(), sn.clone())) {
                    outs.remove(&key);
                }
                self.edge_props.remove(&(
                    st.clone(), sn.clone(),
                    type_str.to_string(), name.to_string(),
                ));
            }
        }

        // Remove node
        self.nodes.remove(&key);

        // Clean indexes
        self.clean_indexes_for_node(type_str, name);
    }

    fn clean_indexes_for_node(&mut self, type_str: &str, name: &str) {
        match type_str {
            "field" => {
                if let Some(pos) = name.find('/') {
                    let entity = &name[..pos];
                    if let Some(fields) = self.entity_fields.get_mut(entity) {
                        fields.remove(name);
                    }
                }
                self.field_categories.remove(name);
                for cats in self.category_fields.values_mut() {
                    cats.remove(name);
                }
            }
            "category" => {
                self.category_fields.remove(name);
                for fields in self.field_categories.values_mut() {
                    fields.remove(name);
                }
            }
            "purpose" => {
                self.purpose_policies.remove(name);
                self.purpose_regulations.remove(name);
            }
            "regulation" => {
                if let Some(jurisdictions) = self.regulation_jurisdictions.remove(name) {
                    for j in jurisdictions {
                        if let Some(regs) = self.jurisdiction_regulations.get_mut(&j) {
                            regs.remove(name);
                        }
                    }
                }
                // Also remove from purpose_regulations
                for links in self.purpose_regulations.values_mut() {
                    links.retain(|l| l.regulation_name != name);
                }
            }
            "jurisdiction" => {
                if let Some(regs) = self.jurisdiction_regulations.remove(name) {
                    for r in regs {
                        if let Some(js) = self.regulation_jurisdictions.get_mut(&r) {
                            js.remove(name);
                        }
                    }
                }
            }
            "policy" => {
                for policies in self.purpose_policies.values_mut() {
                    policies.remove(name);
                }
            }
            _ => {}
        }
    }
}

/// Result of resolving masking type via graph traversal.
#[derive(Debug, Clone)]
pub struct MaskingResolution {
    pub masking_type: MaskingType,
    pub regulation_name: Option<String>,
    pub lawful_basis: Option<String>,
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use crate::models::ConsentGraphPage;

    fn sample_graph_page() -> ConsentGraphPage {
        serde_json::from_str(r#"{
            "nodes": [
                {"type": "entity", "name": "User", "properties": {"description": "User entity"}},
                {"type": "field", "name": "User/email", "properties": {"entity": "User"}},
                {"type": "field", "name": "User/phone", "properties": {"entity": "User"}},
                {"type": "category", "name": "PII", "properties": {}},
                {"type": "purpose", "name": "Marketing", "properties": {}},
                {"type": "regulation", "name": "GDPR", "properties": {}},
                {"type": "regulation", "name": "CCPA", "properties": {}},
                {"type": "jurisdiction", "name": "EU", "properties": {}},
                {"type": "jurisdiction", "name": "California", "properties": {}},
                {"type": "policy", "name": "FIELD:User/email:Marketing", "properties": {"policyType": "FIELD", "purpose": "Marketing", "field": "User/email"}}
            ],
            "edges": [
                {"sourceType": "field", "sourceName": "User/email", "targetType": "category", "targetName": "PII", "properties": {}},
                {"sourceType": "field", "sourceName": "User/phone", "targetType": "category", "targetName": "PII", "properties": {}},
                {"sourceType": "purpose", "sourceName": "Marketing", "targetType": "policy", "targetName": "FIELD:User/email:Marketing", "properties": {}},
                {"sourceType": "purpose", "sourceName": "Marketing", "targetType": "regulation", "targetName": "GDPR", "properties": {"lawfulBasis": "consent", "onGranted": "PLAINTEXT", "onDenied": "REDACT_ALL"}},
                {"sourceType": "purpose", "sourceName": "Marketing", "targetType": "regulation", "targetName": "CCPA", "properties": {"lawfulBasis": "consent", "onGranted": "PLAINTEXT", "onDenied": "REDACT_UP_TO_LAST_4"}},
                {"sourceType": "regulation", "sourceName": "GDPR", "targetType": "jurisdiction", "targetName": "EU", "properties": {}},
                {"sourceType": "regulation", "sourceName": "CCPA", "targetType": "jurisdiction", "targetName": "California", "properties": {}}
            ],
            "pageInfo": {"nextCursor": null, "hasMore": false, "totalNodes": 10, "totalEdges": 7}
        }"#).unwrap()
    }

    #[test]
    fn ingest_and_lookup_entity_fields() {
        let mut store = GraphStore::new();
        store.ingest_page(&sample_graph_page());

        let fields = store.fields_for_entity("User");
        assert!(fields.contains("User/email"));
        assert!(fields.contains("User/phone"));
        assert_eq!(fields.len(), 2);
    }

    #[test]
    fn ingest_and_lookup_field_categories() {
        let mut store = GraphStore::new();
        store.ingest_page(&sample_graph_page());

        let cats = store.categories_for_field("User/email");
        assert!(cats.contains("PII"));

        let fields = store.fields_for_category("PII");
        assert!(fields.contains("User/email"));
        assert!(fields.contains("User/phone"));
    }

    #[test]
    fn ingest_and_lookup_purpose_regulations() {
        let mut store = GraphStore::new();
        store.ingest_page(&sample_graph_page());

        let links = store.regulations_for_purpose("Marketing");
        assert_eq!(links.len(), 2);

        let gdpr = links.iter().find(|l| l.regulation_name == "GDPR").unwrap();
        assert_eq!(gdpr.on_granted, MaskingType::Plaintext);
        assert_eq!(gdpr.on_denied, MaskingType::RedactAll);
        assert_eq!(gdpr.lawful_basis.as_deref(), Some("consent"));

        let ccpa = links.iter().find(|l| l.regulation_name == "CCPA").unwrap();
        assert_eq!(ccpa.on_denied, MaskingType::RedactUpToLast4);
    }

    #[test]
    fn ingest_and_lookup_regulation_jurisdictions() {
        let mut store = GraphStore::new();
        store.ingest_page(&sample_graph_page());

        let js = store.jurisdictions_for_regulation("GDPR");
        assert!(js.contains("EU"));
        assert!(!js.contains("California"));

        let regs = store.regulations_for_jurisdiction("EU");
        assert!(regs.contains("GDPR"));
    }

    #[test]
    fn resolve_masking_eu_denied() {
        let mut store = GraphStore::new();
        store.ingest_page(&sample_graph_page());

        // In EU, GDPR applies: onDenied = REDACT_ALL
        let res = store.resolve_masking_for_field("Marketing", "EU", false);
        assert_eq!(res.masking_type, MaskingType::RedactAll);
        assert_eq!(res.regulation_name.as_deref(), Some("GDPR"));
    }

    #[test]
    fn resolve_masking_california_denied() {
        let mut store = GraphStore::new();
        store.ingest_page(&sample_graph_page());

        // In California, CCPA applies: onDenied = REDACT_UP_TO_LAST_4
        let res = store.resolve_masking_for_field("Marketing", "California", false);
        assert_eq!(res.masking_type, MaskingType::RedactUpToLast4);
        assert_eq!(res.regulation_name.as_deref(), Some("CCPA"));
    }

    #[test]
    fn resolve_masking_granted_is_plaintext() {
        let mut store = GraphStore::new();
        store.ingest_page(&sample_graph_page());

        let res = store.resolve_masking_for_field("Marketing", "EU", true);
        assert_eq!(res.masking_type, MaskingType::Plaintext);
    }

    #[test]
    fn resolve_masking_unknown_jurisdiction_falls_back() {
        let mut store = GraphStore::new();
        store.ingest_page(&sample_graph_page());

        // No regulation linked to "Brazil" → falls back to REDACT_ALL
        let res = store.resolve_masking_for_field("Marketing", "Brazil", false);
        assert_eq!(res.masking_type, MaskingType::RedactAll);
        assert!(res.regulation_name.is_none());
    }

    #[test]
    fn resolve_masking_most_restrictive_wins() {
        // Create a graph where two regulations apply in the same jurisdiction
        let json = r#"{
            "nodes": [
                {"type": "purpose", "name": "Analytics", "properties": {}},
                {"type": "regulation", "name": "RegA", "properties": {}},
                {"type": "regulation", "name": "RegB", "properties": {}},
                {"type": "jurisdiction", "name": "Global", "properties": {}}
            ],
            "edges": [
                {"sourceType": "purpose", "sourceName": "Analytics", "targetType": "regulation", "targetName": "RegA", "properties": {"onGranted": "PLAINTEXT", "onDenied": "REDACT_UP_TO_LAST_4"}},
                {"sourceType": "purpose", "sourceName": "Analytics", "targetType": "regulation", "targetName": "RegB", "properties": {"onGranted": "HASH256", "onDenied": "REDACT_ALL"}},
                {"sourceType": "regulation", "sourceName": "RegA", "targetType": "jurisdiction", "targetName": "Global", "properties": {}},
                {"sourceType": "regulation", "sourceName": "RegB", "targetType": "jurisdiction", "targetName": "Global", "properties": {}}
            ],
            "pageInfo": {"nextCursor": null, "hasMore": false, "totalNodes": 4, "totalEdges": 4}
        }"#;
        let page: ConsentGraphPage = serde_json::from_str(json).unwrap();
        let mut store = GraphStore::new();
        store.ingest_page(&page);

        // Denied: REDACT_ALL > REDACT_UP_TO_LAST_4 → REDACT_ALL wins
        let res = store.resolve_masking_for_field("Analytics", "Global", false);
        assert_eq!(res.masking_type, MaskingType::RedactAll);

        // Granted: HASH256 > PLAINTEXT → HASH256 wins
        let res = store.resolve_masking_for_field("Analytics", "Global", true);
        assert_eq!(res.masking_type, MaskingType::Hash256);
    }

    #[test]
    fn flush_clears_everything() {
        let mut store = GraphStore::new();
        store.ingest_page(&sample_graph_page());
        assert!(store.node_count() > 0);

        store.flush();
        assert_eq!(store.node_count(), 0);
        assert!(store.fields_for_entity("User").is_empty());
    }

    #[test]
    fn remove_node_cascades() {
        let mut store = GraphStore::new();
        store.ingest_page(&sample_graph_page());

        assert!(store.is_field_registered("User/email"));
        store.remove_node("field", "User/email");
        assert!(!store.is_field_registered("User/email"));
        assert!(!store.fields_for_entity("User").contains("User/email"));
    }

    #[test]
    fn purpose_policies_indexed() {
        let mut store = GraphStore::new();
        store.ingest_page(&sample_graph_page());

        let policies = store.policies_for_purpose("Marketing");
        assert!(policies.contains("FIELD:User/email:Marketing"));
    }

    #[test]
    fn is_field_registered() {
        let mut store = GraphStore::new();
        store.ingest_page(&sample_graph_page());

        assert!(store.is_field_registered("User/email"));
        assert!(store.is_field_registered("User/phone"));
        assert!(!store.is_field_registered("User/ssn"));
    }
}
