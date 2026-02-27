package com.koostack.operio.tenant.consent.projection.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.koostack.operio.core.Operio;
import com.koostack.operio.core.data.consent.ConsentEventTypes;
import com.koostack.operio.core.data.consent.invalidation.CacheInvalidationMessage;
import com.koostack.operio.core.data.consent.invalidation.InvalidationAction;
import com.koostack.operio.core.data.consent.invalidation.InvalidationPublisher;
import com.koostack.operio.core.event.models.TenantEvent;
import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.plugin.consent.redis.ConsentPolicyRedisRepository;
import com.koostack.operio.plugin.consent.redis.FieldCategoryMappingRedisRepository;
import com.koostack.operio.tenant.event.jooq.StreamEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Stream projection handler that materializes tenant consent domain events
 * into the Redis decision cache.
 *
 * <p>Switches on {@code eventType} and writes to Redis via
 * {@link ConsentPolicyRedisRepository} (consent decisions) and
 * {@link FieldCategoryMappingRedisRepository} (field↔category metadata).
 *
 * <p>Handles policy events (grant/revoke/bulk-revoke) and metadata events
 * (field-category link/unlink, category deletion, field removal). Events
 * unrelated to the decision layer (e.g., entity registration, purpose/regulation
 * management) are silently ignored.
 *
 * <p>All writes are routed to the correct tenant's Redis key namespace via
 * {@link TenantContext}, set from the event's {@code tenantId} field.
 *
 * <h2>Thread Safety</h2>
 * <p>Safe for concurrent use — all state is final. {@link TenantContext}
 * provides per-thread isolation.
 */
public class ConsentDecisionProjectionHandler implements StreamEventHandler, Operio.AppNameAware {

    private static final Logger log = LoggerFactory.getLogger(ConsentDecisionProjectionHandler.class);

    private static final ObjectMapper objectMapper = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    private final ConsentPolicyRedisRepository consentRepo;
    private final FieldCategoryMappingRedisRepository mappingRepo;
    private final InvalidationPublisher invalidationPublisher;

    public ConsentDecisionProjectionHandler(
        ConsentPolicyRedisRepository consentRepo,
        FieldCategoryMappingRedisRepository mappingRepo
    ) {
        this(consentRepo, mappingRepo, null);
    }

    public ConsentDecisionProjectionHandler(
        ConsentPolicyRedisRepository consentRepo,
        FieldCategoryMappingRedisRepository mappingRepo,
        InvalidationPublisher invalidationPublisher
    ) {
        this.consentRepo = consentRepo;
        this.mappingRepo = mappingRepo;
        this.invalidationPublisher = invalidationPublisher;
    }

    @Override
    public void setAppName(String appName) {
        consentRepo.setAppName(appName);
        mappingRepo.setAppName(appName);
    }

    @Override
    public void onEvent(String streamKey, String messageId, Map<String, String> fields) {
        String tenantId = fields.get(TenantEvent.FIELD_TENANT_ID);
        String eventType = fields.get(TenantEvent.FIELD_EVENT_TYPE);
        String payload = fields.get(TenantEvent.FIELD_PAYLOAD);

        if (tenantId == null || eventType == null) {
            log.warn("Skipping event {} — missing tenantId or eventType", messageId);
            return;
        }

        TenantContext.runWhere(tenantId, () -> {
            try {
                JsonNode node = payload != null ? objectMapper.readTree(payload) : objectMapper.createObjectNode();
                dispatch(tenantId, eventType, node);
                log.debug("Decision-projected event {} [{}] for tenant {}", messageId, eventType, tenantId);
            } catch (Exception e) {
                log.error("Failed to decision-project event {} [{}] for tenant {}",
                    messageId, eventType, tenantId, e);
                throw new RuntimeException(e);
            }
        });
    }

    private void dispatch(String tenantId, String eventType, JsonNode node) {
        switch (eventType) {
            // -- Consent Policy --
            case ConsentEventTypes.POLICY_GRANTED -> handlePolicyGrantedOrRevoked(tenantId, node);
            case ConsentEventTypes.POLICY_REVOKED -> handlePolicyGrantedOrRevoked(tenantId, node);
            case ConsentEventTypes.POLICY_ALL_REVOKED -> handlePolicyAllRevoked(tenantId, node);

            // -- Field ↔ Category metadata --
            case ConsentEventTypes.ENTITY_FIELD_CATEGORY_LINKED -> handleFieldCategoryLinked(tenantId, node);
            case ConsentEventTypes.ENTITY_FIELD_CATEGORY_UNLINKED -> handleFieldCategoryUnlinked(tenantId, node);

            // -- Metadata cleanup --
            case ConsentEventTypes.CATEGORY_DELETED -> handleCategoryDeleted(tenantId, node);
            case ConsentEventTypes.ENTITY_FIELD_REMOVED -> handleEntityFieldRemoved(tenantId, node);

            // Events not relevant to the decision cache are silently ignored
            default -> {}
        }
    }

    // -----------------------------------------------------------------------
    // Consent Policy
    // -----------------------------------------------------------------------

    private void handlePolicyGrantedOrRevoked(String tenantId, JsonNode node) {
        String subjectId = node.get("subjectId").asText();
        String purposeName = node.get("purposeName").asText();
        String fieldName = textOrNull(node, "fieldName");
        String entityName = textOrNull(node, "entityName");
        String category = textOrNull(node, "category");
        boolean granted = node.get("granted").asBoolean();
        Instant expiresAt = instantOrNull(node, "expiresAt");

        if (fieldName != null && entityName != null) {
            // Field-level consent
            if (expiresAt != null) {
                consentRepo.setFieldConsent(subjectId, entityName, fieldName, purposeName, granted, expiresAt);
            } else {
                consentRepo.setFieldConsent(subjectId, entityName, fieldName, purposeName, granted);
            }
            publishInvalidation(new CacheInvalidationMessage(
                UUID.randomUUID().toString(), tenantId, subjectId, entityName, fieldName,
                null, purposeName, granted ? InvalidationAction.GRANTED : InvalidationAction.REVOKED
            ));
        } else if (category != null) {
            // Category-level consent — fan out to all fields in the category
            Set<String> qualifiedFields = mappingRepo.getFieldsForCategory(category);
            if (qualifiedFields.isEmpty()) {
                log.debug("No fields mapped to category '{}' — skipping category consent fan-out", category);
                // Still notify clients: they may have stale metadata showing fields for this category
                publishInvalidation(new CacheInvalidationMessage(
                    UUID.randomUUID().toString(), tenantId, subjectId,
                    null, null, category, purposeName,
                    granted ? InvalidationAction.GRANTED : InvalidationAction.REVOKED
                ));
                return;
            }
            if (expiresAt != null) {
                consentRepo.setCategoryConsentForFields(
                    subjectId, purposeName, granted, expiresAt, qualifiedFields
                );
            } else {
                consentRepo.setCategoryConsentForFields(
                    subjectId, purposeName, granted, qualifiedFields
                );
            }
            // Batch-publish one invalidation per field in the fan-out
            InvalidationAction action = granted ? InvalidationAction.GRANTED : InvalidationAction.REVOKED;
            List<CacheInvalidationMessage> batch = new ArrayList<>();
            for (String qualifiedField : qualifiedFields) {
                String[] parts = parseQualifiedField(qualifiedField);
                batch.add(new CacheInvalidationMessage(
                    UUID.randomUUID().toString(), tenantId, subjectId,
                    parts[0], parts[1], category, purposeName, action
                ));
            }
            publishBatchInvalidation(batch);
        } else {
            log.warn("Policy event has neither fieldName nor category — skipping");
        }
    }

    private void handlePolicyAllRevoked(String tenantId, JsonNode node) {
        String subjectId = node.get("subjectId").asText();
        long deleted = consentRepo.deleteAllCachedKeysForSubject(subjectId);
        log.debug("Deleted {} cached keys for subject '{}'", deleted, subjectId);
        publishInvalidation(new CacheInvalidationMessage(
            UUID.randomUUID().toString(), tenantId, subjectId,
            null, null, null, null, InvalidationAction.ALL_REVOKED
        ));
    }

    // -----------------------------------------------------------------------
    // Field ↔ Category metadata
    // -----------------------------------------------------------------------

    private void handleFieldCategoryLinked(String tenantId, JsonNode node) {
        String entityName = node.get("entityName").asText();
        String fieldName = node.get("fieldName").asText();
        String categoryName = node.get("categoryName").asText();
        mappingRepo.addFieldToCategory(entityName, fieldName, categoryName);
        publishInvalidation(new CacheInvalidationMessage(
            UUID.randomUUID().toString(), tenantId, null,
            entityName, fieldName, categoryName, null, InvalidationAction.CATEGORY_CHANGED
        ));
    }

    private void handleFieldCategoryUnlinked(String tenantId, JsonNode node) {
        String entityName = node.get("entityName").asText();
        String fieldName = node.get("fieldName").asText();
        String categoryName = node.get("categoryName").asText();
        mappingRepo.removeFieldFromCategory(entityName, fieldName, categoryName);
        publishInvalidation(new CacheInvalidationMessage(
            UUID.randomUUID().toString(), tenantId, null,
            entityName, fieldName, categoryName, null, InvalidationAction.CATEGORY_CHANGED
        ));
    }

    // -----------------------------------------------------------------------
    // Metadata cleanup
    // -----------------------------------------------------------------------

    private void handleCategoryDeleted(String tenantId, JsonNode node) {
        String categoryName = node.get("categoryName").asText();
        int removed = mappingRepo.removeAllMappingsForCategory(categoryName);
        log.debug("Removed {} field mappings for deleted category '{}'", removed, categoryName);
        publishInvalidation(new CacheInvalidationMessage(
            UUID.randomUUID().toString(), tenantId, null,
            null, null, categoryName, null, InvalidationAction.CATEGORY_CHANGED
        ));
    }

    private void handleEntityFieldRemoved(String tenantId, JsonNode node) {
        String entityName = node.get("entityName").asText();
        String fieldName = node.get("fieldName").asText();
        int removed = mappingRepo.removeAllMappingsForField(entityName, fieldName);
        log.debug("Removed {} category mappings for removed field '{}/{}'", removed, entityName, fieldName);
        publishInvalidation(new CacheInvalidationMessage(
            UUID.randomUUID().toString(), tenantId, null,
            entityName, fieldName, null, null, InvalidationAction.FIELD_REMOVED
        ));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String textOrNull(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return (child != null && !child.isNull()) ? child.asText() : null;
    }

    private static Instant instantOrNull(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child == null || child.isNull()) return null;
        return Instant.parse(child.asText());
    }

    // -----------------------------------------------------------------------
    // Invalidation publishing
    // -----------------------------------------------------------------------

    private void publishInvalidation(CacheInvalidationMessage message) {
        if (invalidationPublisher == null) return;
        try {
            invalidationPublisher.publish(message);
        } catch (Exception e) {
            log.warn("Failed to publish cache invalidation — messageId={}, action={}",
                message.messageId(), message.action(), e);
        }
    }

    private void publishBatchInvalidation(List<CacheInvalidationMessage> messages) {
        if (invalidationPublisher == null || messages.isEmpty()) return;
        try {
            invalidationPublisher.publishBatch(messages);
        } catch (Exception e) {
            log.warn("Failed to batch-publish {} cache invalidation messages", messages.size(), e);
        }
    }

    /**
     * Parses a qualified field string ({@code "entity/field"}) into its parts.
     * Falls back to {@code ["unknown", qualifiedField]} if no separator is found.
     */
    private static String[] parseQualifiedField(String qualifiedField) {
        int sep = qualifiedField.indexOf('/');
        if (sep > 0 && sep < qualifiedField.length() - 1) {
            return new String[]{ qualifiedField.substring(0, sep), qualifiedField.substring(sep + 1) };
        }
        log.warn("Malformed qualified field '{}' — expected 'entity/field' format", qualifiedField);
        return new String[]{ "unknown", qualifiedField };
    }
}
