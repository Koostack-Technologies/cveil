package com.koostack.operio.tenant.consent.projection.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.koostack.operio.core.event.models.TenantEvent;
import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.core.tenant.consent.ConsentEventTypes;
import com.koostack.operio.core.tenant.consent.invalidation.CacheInvalidationMessage;
import com.koostack.operio.core.tenant.consent.invalidation.InvalidationAction;
import com.koostack.operio.core.tenant.consent.invalidation.InvalidationPublisher;
import com.koostack.operio.plugin.event.jooq.StreamEventHandler;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Unified stream projection handler that materializes ALL consent domain events into the Redis
 * property graph.
 *
 * <p>Replaces both {@code ConsentDecisionProjectionHandler} (decision cache) and {@code
 * ConsentAdminRedisProjectionHandler} (admin views) with a single graph-based projection that
 * stores 8 first-class node types: entity, field, category, purpose, regulation, jurisdiction,
 * policy, subject.
 *
 * <p>Each event creates/updates/removes nodes and edges in the graph via {@link
 * ConsentGraphRedisRepository}. All writes are routed to the correct tenant's key namespace via
 * {@link TenantContext}.
 */
public class ConsentGraphProjectionHandler implements StreamEventHandler {

    private static final Logger log = LoggerFactory.getLogger(ConsentGraphProjectionHandler.class);

    private final ObjectMapper objectMapper;
    private final ConsentGraphRedisRepository graph;
    private final InvalidationPublisher invalidationPublisher;

    public ConsentGraphProjectionHandler(ConsentGraphRedisRepository graph) {
        this(graph, null, defaultObjectMapper());
    }

    public ConsentGraphProjectionHandler(
            ConsentGraphRedisRepository graph, InvalidationPublisher invalidationPublisher) {
        this(graph, invalidationPublisher, defaultObjectMapper());
    }

    public ConsentGraphProjectionHandler(
            ConsentGraphRedisRepository graph,
            InvalidationPublisher invalidationPublisher,
            ObjectMapper objectMapper) {
        this.graph = graph;
        this.invalidationPublisher = invalidationPublisher;
        this.objectMapper = objectMapper;
    }

    private static ObjectMapper defaultObjectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    // -----------------------------------------------------------------------
    // StreamEventHandler
    // -----------------------------------------------------------------------

    @Override
    public void onEvent(String streamKey, String messageId, Map<String, String> fields) {
        String tenantId = fields.get(TenantEvent.FIELD_TENANT_ID);
        String eventType = fields.get(TenantEvent.FIELD_EVENT_TYPE);
        String payload = fields.get(TenantEvent.FIELD_PAYLOAD);

        if (tenantId == null || eventType == null) {
            log.warn("Skipping event {} — missing tenantId or eventType", messageId);
            return;
        }

        TenantContext.runWhere(
                tenantId,
                () -> {
                    try {
                        JsonNode node =
                                payload != null
                                        ? objectMapper.readTree(payload)
                                        : objectMapper.createObjectNode();
                        dispatch(tenantId, eventType, node);
                        log.debug(
                                "Graph-projected event {} [{}] for tenant {}",
                                messageId,
                                eventType,
                                tenantId);
                    } catch (Exception e) {
                        log.error(
                                "Failed to graph-project event {} [{}] for tenant {}",
                                messageId,
                                eventType,
                                tenantId,
                                e);
                        throw new RuntimeException(e);
                    }
                });
    }

    private void dispatch(String tenantId, String eventType, JsonNode node) {
        switch (eventType) {
                // -- Entity Aggregate --
            case ConsentEventTypes.ENTITY_REGISTERED -> handleEntityRegistered(tenantId, node);
            case ConsentEventTypes.ENTITY_UPDATED -> handleEntityUpdated(tenantId, node);
            case ConsentEventTypes.ENTITY_REMOVED -> handleEntityRemoved(tenantId, node);

                // -- Category Aggregate --
            case ConsentEventTypes.CATEGORY_REGISTERED -> handleCategoryRegistered(tenantId, node);
            case ConsentEventTypes.CATEGORY_UPDATED -> handleCategoryUpdated(tenantId, node);
            case ConsentEventTypes.CATEGORY_REMOVED -> handleCategoryRemoved(tenantId, node);

                // -- Field Aggregate --
            case ConsentEventTypes.FIELD_REGISTERED -> handleFieldRegistered(tenantId, node);
            case ConsentEventTypes.FIELD_UPDATED -> handleFieldUpdated(tenantId, node);
            case ConsentEventTypes.FIELD_REMOVED -> handleFieldRemoved(tenantId, node);
            case ConsentEventTypes.FIELD_CATEGORY_ASSIGNED -> handleFieldCategoryAssigned(
                    tenantId, node);
            case ConsentEventTypes.FIELD_CATEGORY_UNASSIGNED -> handleFieldCategoryUnassigned(
                    tenantId, node);

                // -- Purpose Aggregate --
            case ConsentEventTypes.PURPOSE_REGISTERED -> handlePurposeRegistered(tenantId, node);
            case ConsentEventTypes.PURPOSE_UPDATED -> handlePurposeUpdated(tenantId, node);
            case ConsentEventTypes.PURPOSE_REMOVED -> handlePurposeRemoved(tenantId, node);
            case ConsentEventTypes.PURPOSE_REGULATION_LINKED -> handlePurposeRegulationLinked(
                    tenantId, node);
            case ConsentEventTypes.PURPOSE_REGULATION_UNLINKED -> handlePurposeRegulationUnlinked(
                    tenantId, node);
            case ConsentEventTypes.PURPOSE_POLICY_ADDED -> handlePurposePolicyAdded(tenantId, node);
            case ConsentEventTypes.PURPOSE_POLICY_REMOVED -> handlePurposePolicyRemoved(
                    tenantId, node);

                // -- Regulation Aggregate --
            case ConsentEventTypes.REGULATION_REGISTERED -> handleRegulationRegistered(
                    tenantId, node);
            case ConsentEventTypes.REGULATION_UPDATED -> handleRegulationUpdated(tenantId, node);
            case ConsentEventTypes.REGULATION_REMOVED -> handleRegulationRemoved(tenantId, node);
            case ConsentEventTypes
                    .REGULATION_JURISDICTION_ADDED -> handleRegulationJurisdictionAdded(
                    tenantId, node);
            case ConsentEventTypes
                    .REGULATION_JURISDICTION_REMOVED -> handleRegulationJurisdictionRemoved(
                    tenantId, node);

                // -- Jurisdiction Aggregate --
            case ConsentEventTypes.JURISDICTION_REGISTERED -> handleJurisdictionRegistered(
                    tenantId, node);
            case ConsentEventTypes.JURISDICTION_UPDATED -> handleJurisdictionUpdated(
                    tenantId, node);
            case ConsentEventTypes.JURISDICTION_REMOVED -> handleJurisdictionRemoved(
                    tenantId, node);

                // -- Subject Aggregate (entity lifecycle) --
            case ConsentEventTypes.SUBJECT_REGISTERED -> handleSubjectRegistered(tenantId, node);
            case ConsentEventTypes.SUBJECT_UPDATED -> handleSubjectUpdated(tenantId, node);
            case ConsentEventTypes.SUBJECT_REMOVED -> handleSubjectRemoved(tenantId, node);

                // -- SubjectConsent Aggregate --
            case ConsentEventTypes.CONSENT_GRANTED -> handleConsentGranted(tenantId, node);
            case ConsentEventTypes.CONSENT_REVOKED -> handleConsentRevoked(tenantId, node);
            case ConsentEventTypes.CONSENT_EXPIRED -> handleConsentExpired(tenantId, node);
            case ConsentEventTypes.CONSENT_ALL_REVOKED -> handleConsentAllRevoked(tenantId, node);

            default -> log.trace("Ignoring event type: {}", eventType);
        }
    }

    // -----------------------------------------------------------------------
    // Entity Aggregate
    // -----------------------------------------------------------------------

    private void handleEntityRegistered(String tenantId, JsonNode node) {
        String entityName = requiredText(node, "entityName");
        String description = textOrNull(node, "description");

        Map<String, String> props = new HashMap<>();
        props.put("name", entityName);
        if (description != null) props.put("desc", description);
        graph.putNode("entity", entityName, props);

        publishInvalidation(
                tenantId, InvalidationAction.ENTITY_CHANGED, entityName, null, null, null);
    }

    private void handleEntityUpdated(String tenantId, JsonNode node) {
        String entityName = requiredText(node, "entityName");
        String description = textOrNull(node, "description");

        Map<String, String> props = new HashMap<>();
        props.put("name", entityName);
        if (description != null) props.put("desc", description);
        graph.putNode("entity", entityName, props);

        publishInvalidation(
                tenantId, InvalidationAction.ENTITY_CHANGED, entityName, null, null, null);
    }

    private void handleEntityRemoved(String tenantId, JsonNode node) {
        String entityName = requiredText(node, "entityName");
        graph.removeNode("entity", entityName);
        publishInvalidation(
                tenantId, InvalidationAction.ENTITY_CHANGED, entityName, null, null, null);
    }

    // -----------------------------------------------------------------------
    // Category Aggregate
    // -----------------------------------------------------------------------

    private void handleCategoryRegistered(String tenantId, JsonNode node) {
        String categoryName = requiredText(node, "categoryName");
        String description = textOrNull(node, "description");

        Map<String, String> props = new HashMap<>();
        props.put("name", categoryName);
        if (description != null) props.put("desc", description);
        graph.putNode("category", categoryName, props);

        publishInvalidation(
                tenantId, InvalidationAction.CATEGORY_CHANGED, null, null, categoryName, null);
    }

    private void handleCategoryUpdated(String tenantId, JsonNode node) {
        String categoryName = requiredText(node, "categoryName");
        String description = textOrNull(node, "description");

        Map<String, String> props = new HashMap<>();
        props.put("name", categoryName);
        if (description != null) props.put("desc", description);
        graph.putNode("category", categoryName, props);

        publishInvalidation(
                tenantId, InvalidationAction.CATEGORY_CHANGED, null, null, categoryName, null);
    }

    private void handleCategoryRemoved(String tenantId, JsonNode node) {
        String categoryName = requiredText(node, "categoryName");
        graph.removeNode("category", categoryName);
        publishInvalidation(
                tenantId, InvalidationAction.CATEGORY_CHANGED, null, null, categoryName, null);
    }

    // -----------------------------------------------------------------------
    // Field Aggregate
    // -----------------------------------------------------------------------

    private void handleFieldRegistered(String tenantId, JsonNode node) {
        String entityName = requiredText(node, "entityName");
        String fieldName = requiredText(node, "fieldName");
        String description = textOrNull(node, "description");
        String qualifiedField = entityName + "/" + fieldName;

        // Ensure entity node exists
        boolean entityCreated = !graph.nodeExists("entity", entityName);
        ensureNode("entity", entityName, Map.of("name", entityName));

        // Create field node + edge to entity
        Map<String, String> props = new HashMap<>();
        props.put("name", fieldName);
        props.put("entity", entityName);
        if (description != null) props.put("desc", description);
        graph.putNode("field", qualifiedField, props);
        graph.addEdge("field", qualifiedField, "entity", entityName, null);

        publishInvalidation(
                tenantId, InvalidationAction.FIELD_CHANGED, entityName, fieldName, null, null);
        if (entityCreated) {
            publishInvalidation(
                    tenantId, InvalidationAction.ENTITY_CHANGED, entityName, null, null, null);
        }
    }

    private void handleFieldUpdated(String tenantId, JsonNode node) {
        String entityName = requiredText(node, "entityName");
        String fieldName = requiredText(node, "fieldName");
        String description = textOrNull(node, "description");
        String qualifiedField = entityName + "/" + fieldName;

        Map<String, String> props = new HashMap<>();
        props.put("name", fieldName);
        props.put("entity", entityName);
        if (description != null) props.put("desc", description);
        graph.putNode("field", qualifiedField, props);

        // Re-sync categories from snapshot if present
        JsonNode snapshot = node.get("snapshot");
        if (snapshot != null && !snapshot.isNull()) {
            syncFieldCategories(qualifiedField, snapshot);
        }

        publishInvalidation(
                tenantId, InvalidationAction.FIELD_CHANGED, entityName, fieldName, null, null);
    }

    private void handleFieldRemoved(String tenantId, JsonNode node) {
        String entityName = requiredText(node, "entityName");
        String fieldName = requiredText(node, "fieldName");
        String qualifiedField = entityName + "/" + fieldName;

        graph.removeNode("field", qualifiedField);
        publishInvalidation(
                tenantId, InvalidationAction.FIELD_REMOVED, entityName, fieldName, null, null);
    }

    private void handleFieldCategoryAssigned(String tenantId, JsonNode node) {
        String entityName = requiredText(node, "entityName");
        String fieldName = requiredText(node, "fieldName");
        String categoryName = requiredText(node, "categoryName");
        String qualifiedField = entityName + "/" + fieldName;

        ensureNode("category", categoryName, Map.of("name", categoryName));
        graph.addEdge("field", qualifiedField, "category", categoryName, null);

        publishInvalidation(
                tenantId, InvalidationAction.FIELD_CHANGED, entityName, fieldName, null, null);
        publishInvalidation(
                tenantId,
                InvalidationAction.CATEGORY_CHANGED,
                entityName,
                fieldName,
                categoryName,
                null);
    }

    private void handleFieldCategoryUnassigned(String tenantId, JsonNode node) {
        String entityName = requiredText(node, "entityName");
        String fieldName = requiredText(node, "fieldName");
        String categoryName = requiredText(node, "categoryName");
        String qualifiedField = entityName + "/" + fieldName;

        graph.removeEdge("field", qualifiedField, "category", categoryName);
        publishInvalidation(
                tenantId, InvalidationAction.FIELD_CHANGED, entityName, fieldName, null, null);
        publishInvalidation(
                tenantId,
                InvalidationAction.CATEGORY_CHANGED,
                entityName,
                fieldName,
                categoryName,
                null);
    }

    // -----------------------------------------------------------------------
    // Purpose Aggregate
    // -----------------------------------------------------------------------

    private void handlePurposeRegistered(String tenantId, JsonNode node) {
        String purposeName = requiredText(node, "purposeName");
        String description = textOrNull(node, "description");

        Map<String, String> props = new HashMap<>();
        props.put("name", purposeName);
        if (description != null) props.put("desc", description);
        graph.putNode("purpose", purposeName, props);

        publishInvalidation(
                tenantId, InvalidationAction.PURPOSE_CHANGED, null, null, null, purposeName);
    }

    private void handlePurposeUpdated(String tenantId, JsonNode node) {
        String purposeName = requiredText(node, "purposeName");
        String description = textOrNull(node, "description");

        Map<String, String> props = new HashMap<>();
        props.put("name", purposeName);
        if (description != null) props.put("desc", description);
        graph.putNode("purpose", purposeName, props);

        publishInvalidation(
                tenantId, InvalidationAction.PURPOSE_CHANGED, null, null, null, purposeName);
    }

    private void handlePurposeRemoved(String tenantId, JsonNode node) {
        String purposeName = requiredText(node, "purposeName");
        graph.removeNode("purpose", purposeName);
        publishInvalidation(
                tenantId, InvalidationAction.PURPOSE_CHANGED, null, null, null, purposeName);
    }

    private void handlePurposeRegulationLinked(String tenantId, JsonNode node) {
        String purposeName = requiredText(node, "purposeName");
        String regulationName = requiredText(node, "regulationName");
        String lawfulBasis = textOrNull(node, "lawfulBasis");
        String onGranted = textOrNull(node, "onGranted");
        String onDenied = textOrNull(node, "onDenied");

        ensureNode("regulation", regulationName, Map.of("name", regulationName));

        Map<String, String> edgeProps = new HashMap<>();
        if (lawfulBasis != null) edgeProps.put("lawfulBasis", lawfulBasis);
        if (onGranted != null) edgeProps.put("onGranted", onGranted);
        if (onDenied != null) edgeProps.put("onDenied", onDenied);

        graph.addEdge(
                "purpose",
                purposeName,
                "regulation",
                regulationName,
                edgeProps.isEmpty() ? null : edgeProps);

        publishInvalidation(
                tenantId, InvalidationAction.PURPOSE_CHANGED, null, null, null, purposeName);
        publishInvalidation(
                tenantId, InvalidationAction.REGULATION_CHANGED, null, null, null, null);
    }

    private void handlePurposeRegulationUnlinked(String tenantId, JsonNode node) {
        String purposeName = requiredText(node, "purposeName");
        String regulationName = requiredText(node, "regulationName");

        graph.removeEdge("purpose", purposeName, "regulation", regulationName);
        publishInvalidation(
                tenantId, InvalidationAction.PURPOSE_CHANGED, null, null, null, purposeName);
        publishInvalidation(
                tenantId, InvalidationAction.REGULATION_CHANGED, null, null, null, null);
    }

    private void handlePurposePolicyAdded(String tenantId, JsonNode node) {
        String purposeName = requiredText(node, "purposeName");
        String policyType = requiredText(node, "policyType").toUpperCase();
        String fieldName = textOrNull(node, "fieldName");
        String entityName = textOrNull(node, "entityName");
        String categoryName = textOrNull(node, "categoryName");

        String policyName =
                buildPolicyName(policyType, entityName, fieldName, categoryName, purposeName);

        Map<String, String> policyProps = new HashMap<>();
        policyProps.put("policyType", policyType);
        policyProps.put("purpose", purposeName);
        if ("FIELD".equals(policyType)) {
            String qualifiedField = entityName + "/" + fieldName;
            policyProps.put("field", qualifiedField);
            graph.putNode("policy", policyName, policyProps);
            graph.addEdge("purpose", purposeName, "policy", policyName, null);
            ensureNode(
                    "field",
                    qualifiedField,
                    Map.of("name", fieldName, "entity", entityName != null ? entityName : ""));
            graph.addEdge("policy", policyName, "field", qualifiedField, null);
        } else {
            policyProps.put("category", categoryName);
            graph.putNode("policy", policyName, policyProps);
            graph.addEdge("purpose", purposeName, "policy", policyName, null);
            ensureNode("category", categoryName, Map.of("name", categoryName));
            graph.addEdge("policy", policyName, "category", categoryName, null);
        }

        publishInvalidation(
                tenantId, InvalidationAction.PURPOSE_CHANGED, null, null, null, purposeName);
        publishInvalidation(
                tenantId, InvalidationAction.POLICY_CHANGED, null, null, null, purposeName);
    }

    private void handlePurposePolicyRemoved(String tenantId, JsonNode node) {
        String purposeName = requiredText(node, "purposeName");
        String policyType = requiredText(node, "policyType").toUpperCase();
        String fieldName = textOrNull(node, "fieldName");
        String entityName = textOrNull(node, "entityName");
        String categoryName = textOrNull(node, "categoryName");

        String policyName =
                buildPolicyName(policyType, entityName, fieldName, categoryName, purposeName);
        graph.removeNode("policy", policyName);

        publishInvalidation(
                tenantId, InvalidationAction.PURPOSE_CHANGED, null, null, null, purposeName);
        publishInvalidation(
                tenantId, InvalidationAction.POLICY_CHANGED, null, null, null, purposeName);
    }

    // -----------------------------------------------------------------------
    // Regulation Aggregate
    // -----------------------------------------------------------------------

    private void handleRegulationRegistered(String tenantId, JsonNode node) {
        String regulationName = requiredText(node, "regulationName");
        String description = textOrNull(node, "description");

        Map<String, String> props = new HashMap<>();
        props.put("name", regulationName);
        if (description != null) props.put("desc", description);
        graph.putNode("regulation", regulationName, props);

        publishInvalidation(
                tenantId, InvalidationAction.REGULATION_CHANGED, null, null, null, null);
    }

    private void handleRegulationUpdated(String tenantId, JsonNode node) {
        String regulationName = requiredText(node, "regulationName");
        String description = textOrNull(node, "description");

        Map<String, String> props = new HashMap<>();
        props.put("name", regulationName);
        if (description != null) props.put("desc", description);
        graph.putNode("regulation", regulationName, props);

        publishInvalidation(
                tenantId, InvalidationAction.REGULATION_CHANGED, null, null, null, null);
    }

    private void handleRegulationRemoved(String tenantId, JsonNode node) {
        String regulationName = requiredText(node, "regulationName");
        graph.removeNode("regulation", regulationName);
        publishInvalidation(
                tenantId, InvalidationAction.REGULATION_CHANGED, null, null, null, null);
    }

    private void handleRegulationJurisdictionAdded(String tenantId, JsonNode node) {
        String regulationName = requiredText(node, "regulationName");
        String jurisdictionName = requiredText(node, "jurisdictionName");

        ensureNode("jurisdiction", jurisdictionName, Map.of("name", jurisdictionName));
        graph.addEdge("regulation", regulationName, "jurisdiction", jurisdictionName, null);

        publishInvalidation(
                tenantId, InvalidationAction.REGULATION_CHANGED, null, null, null, null);
        publishInvalidation(
                tenantId, InvalidationAction.JURISDICTION_CHANGED, null, null, null, null);
    }

    private void handleRegulationJurisdictionRemoved(String tenantId, JsonNode node) {
        String regulationName = requiredText(node, "regulationName");
        String jurisdictionName = requiredText(node, "jurisdictionName");

        graph.removeEdge("regulation", regulationName, "jurisdiction", jurisdictionName);
        publishInvalidation(
                tenantId, InvalidationAction.REGULATION_CHANGED, null, null, null, null);
        publishInvalidation(
                tenantId, InvalidationAction.JURISDICTION_CHANGED, null, null, null, null);
    }

    // -----------------------------------------------------------------------
    // Jurisdiction Aggregate
    // -----------------------------------------------------------------------

    private void handleJurisdictionRegistered(String tenantId, JsonNode node) {
        String jurisdictionName = requiredText(node, "jurisdictionName");
        String description = textOrNull(node, "description");

        Map<String, String> props = new HashMap<>();
        props.put("name", jurisdictionName);
        if (description != null) props.put("desc", description);
        graph.putNode("jurisdiction", jurisdictionName, props);

        publishInvalidation(
                tenantId, InvalidationAction.JURISDICTION_CHANGED, null, null, null, null);
    }

    private void handleJurisdictionUpdated(String tenantId, JsonNode node) {
        String jurisdictionName = requiredText(node, "jurisdictionName");
        String description = textOrNull(node, "description");

        Map<String, String> props = new HashMap<>();
        props.put("name", jurisdictionName);
        if (description != null) props.put("desc", description);
        graph.putNode("jurisdiction", jurisdictionName, props);

        publishInvalidation(
                tenantId, InvalidationAction.JURISDICTION_CHANGED, null, null, null, null);
    }

    private void handleJurisdictionRemoved(String tenantId, JsonNode node) {
        String jurisdictionName = requiredText(node, "jurisdictionName");
        graph.removeNode("jurisdiction", jurisdictionName);
        publishInvalidation(
                tenantId, InvalidationAction.JURISDICTION_CHANGED, null, null, null, null);
    }

    // -----------------------------------------------------------------------
    // Subject Aggregate (entity lifecycle)
    // -----------------------------------------------------------------------

    private void handleSubjectRegistered(String tenantId, JsonNode node) {
        String subjectName = requiredText(node, "subjectName");
        String description = textOrNull(node, "description");

        Map<String, String> props = new HashMap<>();
        props.put("name", subjectName);
        if (description != null) props.put("desc", description);
        ensureNode("subject", subjectName, props);

        publishInvalidation(
                new CacheInvalidationMessage(
                        UUID.randomUUID().toString(),
                        tenantId,
                        subjectName,
                        null,
                        null,
                        null,
                        null,
                        InvalidationAction.SUBJECT_CHANGED));
    }

    private void handleSubjectUpdated(String tenantId, JsonNode node) {
        String subjectName = requiredText(node, "subjectName");
        String description = textOrNull(node, "description");

        Map<String, String> props = new HashMap<>();
        props.put("name", subjectName);
        if (description != null) props.put("desc", description);
        graph.putNode("subject", subjectName, props);

        publishInvalidation(
                new CacheInvalidationMessage(
                        UUID.randomUUID().toString(),
                        tenantId,
                        subjectName,
                        null,
                        null,
                        null,
                        null,
                        InvalidationAction.SUBJECT_CHANGED));
    }

    private void handleSubjectRemoved(String tenantId, JsonNode node) {
        String subjectName = requiredText(node, "subjectName");

        graph.removeNode("subject", subjectName);

        publishInvalidation(
                new CacheInvalidationMessage(
                        UUID.randomUUID().toString(),
                        tenantId,
                        subjectName,
                        null,
                        null,
                        null,
                        null,
                        InvalidationAction.SUBJECT_CHANGED));
    }

    // -----------------------------------------------------------------------
    // SubjectConsent Aggregate
    // -----------------------------------------------------------------------

    private void handleConsentGranted(String tenantId, JsonNode node) {
        upsertConsentEdge(tenantId, node, "GRANT");
    }

    private void handleConsentRevoked(String tenantId, JsonNode node) {
        upsertConsentEdge(tenantId, node, "REVOKE");
    }

    private void handleConsentExpired(String tenantId, JsonNode node) {
        upsertConsentEdge(tenantId, node, "EXPIRE");
    }

    private void upsertConsentEdge(String tenantId, JsonNode node, String action) {
        String subjectId = requiredText(node, "subjectId");
        String purposeName = requiredText(node, "purposeName");
        String policyType = requiredText(node, "policyType").toUpperCase();
        String fieldName = textOrNull(node, "fieldName");
        String entityName = textOrNull(node, "entityName");
        String categoryName = textOrNull(node, "categoryName");
        String actor = textOrNull(node, "actor");
        String ipAddress = textOrNull(node, "ipAddress");
        Instant expiresAt = instantOrNull(node, "expiresAt");

        String policyName =
                buildPolicyName(policyType, entityName, fieldName, categoryName, purposeName);

        boolean subjectCreated = !graph.nodeExists("subject", subjectId);
        ensureNode("subject", subjectId, Map.of("id", subjectId));

        Map<String, String> edgeProps = new HashMap<>();
        edgeProps.put("action", action);
        if (actor != null) edgeProps.put("actor", actor);
        if (ipAddress != null) edgeProps.put("ipAddress", ipAddress);
        edgeProps.put("updatedAt", Instant.now().toString());
        if (expiresAt != null) edgeProps.put("expiresAt", expiresAt.toString());

        graph.addEdge("subject", subjectId, "policy", policyName, edgeProps);

        InvalidationAction invAction =
                "GRANT".equals(action) ? InvalidationAction.GRANTED : InvalidationAction.REVOKED;
        publishInvalidation(
                new CacheInvalidationMessage(
                        UUID.randomUUID().toString(),
                        tenantId,
                        subjectId,
                        entityName,
                        fieldName,
                        categoryName,
                        purposeName,
                        invAction));
        publishInvalidation(
                new CacheInvalidationMessage(
                        UUID.randomUUID().toString(),
                        tenantId,
                        subjectId,
                        entityName,
                        fieldName,
                        categoryName,
                        purposeName,
                        InvalidationAction.CONSENT_ENTRY_CHANGED));
        if (subjectCreated) {
            publishInvalidation(
                    new CacheInvalidationMessage(
                            UUID.randomUUID().toString(),
                            tenantId,
                            subjectId,
                            null,
                            null,
                            null,
                            null,
                            InvalidationAction.SUBJECT_CHANGED));
        }
    }

    private void handleConsentAllRevoked(String tenantId, JsonNode node) {
        String subjectId = requiredText(node, "subjectId");
        String actor = textOrNull(node, "actor");
        String ipAddress = textOrNull(node, "ipAddress");

        // Batch update all subject→policy edges to REVOKE in single pipeline
        Set<String> outEdges = graph.getOutEdges("subject", subjectId);
        Map<String, Map<String, String>> batchProps = new HashMap<>();
        for (String member : outEdges) {
            String[] parts = ConsentGraphRedisRepository.splitEdgeMember(member);
            if (parts != null && "policy".equals(parts[0])) {
                Map<String, String> edgeProps = new HashMap<>();
                edgeProps.put("action", "REVOKE");
                if (actor != null) edgeProps.put("actor", actor);
                if (ipAddress != null) edgeProps.put("ipAddress", ipAddress);
                edgeProps.put("updatedAt", Instant.now().toString());
                String key = graph.epKey("subject", subjectId, "policy", parts[1]);
                batchProps.put(key, edgeProps);
            }
        }
        graph.batchSetEdgeProps(batchProps);

        publishInvalidation(
                new CacheInvalidationMessage(
                        UUID.randomUUID().toString(),
                        tenantId,
                        subjectId,
                        null,
                        null,
                        null,
                        null,
                        InvalidationAction.ALL_REVOKED));
        publishInvalidation(
                new CacheInvalidationMessage(
                        UUID.randomUUID().toString(),
                        tenantId,
                        subjectId,
                        null,
                        null,
                        null,
                        null,
                        InvalidationAction.CONSENT_ENTRY_CHANGED));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Builds a canonical policy node name. Format: {@code FIELD:{entity}/{field}:{purpose}} or
     * {@code CAT:{category}:{purpose}}
     */
    static String buildPolicyName(
            String policyType,
            String entityName,
            String fieldName,
            String categoryName,
            String purposeName) {
        if ("FIELD".equals(policyType)) {
            return "FIELD:"
                    + (entityName != null ? entityName : "_default_")
                    + "/"
                    + fieldName
                    + ":"
                    + purposeName;
        }
        return "CAT:" + categoryName + ":" + purposeName;
    }

    private void ensureNode(String type, String name, Map<String, String> props) {
        if (!graph.nodeExists(type, name)) {
            graph.putNode(type, name, props);
        }
    }

    private void syncFieldCategories(String qualifiedField, JsonNode snapshot) {
        JsonNode cats = snapshot.get("categoryNames");
        if (cats == null || !cats.isArray()) return;

        // Get current categories, remove edges not in snapshot, add new ones
        Set<String> currentOut = graph.getOutEdges("field", qualifiedField);
        Set<String> desiredCategories = new java.util.HashSet<>();
        for (JsonNode c : cats) {
            desiredCategories.add(c.asText());
        }

        // Remove stale category edges
        for (String member : currentOut) {
            String[] parts = ConsentGraphRedisRepository.splitEdgeMember(member);
            if (parts != null
                    && "category".equals(parts[0])
                    && !desiredCategories.contains(parts[1])) {
                graph.removeEdge("field", qualifiedField, "category", parts[1]);
            }
        }

        // Add new category edges
        for (String cat : desiredCategories) {
            ensureNode("category", cat, Map.of("name", cat));
            graph.addEdge("field", qualifiedField, "category", cat, null);
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return (child != null && !child.isNull()) ? child.asText() : null;
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child == null || child.isNull()) {
            throw new IllegalArgumentException("Missing required payload field: " + field);
        }
        return child.asText();
    }

    private static Instant instantOrNull(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child == null || child.isNull()) return null;
        try {
            if (child.isNumber()) {
                long epochSecond = child.longValue();
                int nano =
                        (int)
                                ((child.decimalValue()
                                                .remainder(java.math.BigDecimal.ONE)
                                                .movePointRight(9))
                                        .intValue());
                return Instant.ofEpochSecond(epochSecond, nano);
            }
            return Instant.parse(child.asText());
        } catch (Exception e) {
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Invalidation publishing
    // -----------------------------------------------------------------------

    private void publishInvalidation(
            String tenantId,
            InvalidationAction action,
            String entity,
            String field,
            String category,
            String purpose) {
        publishInvalidation(
                new CacheInvalidationMessage(
                        UUID.randomUUID().toString(),
                        tenantId,
                        null,
                        entity,
                        field,
                        category,
                        purpose,
                        action));
    }

    private void publishInvalidation(CacheInvalidationMessage message) {
        if (invalidationPublisher == null) return;
        try {
            invalidationPublisher.publish(message);
        } catch (Exception e) {
            log.warn(
                    "Failed to publish cache invalidation — messageId={}, action={}",
                    message.messageId(),
                    message.action(),
                    e);
        }
    }
}
