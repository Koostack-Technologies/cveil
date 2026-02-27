package com.koostack.operio.tenant.consent.projection.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.koostack.operio.core.data.consent.ConsentEventTypes;
import com.koostack.operio.core.data.consent.invalidation.CacheInvalidationMessage;
import com.koostack.operio.core.data.consent.invalidation.InvalidationAction;
import com.koostack.operio.core.data.consent.invalidation.InvalidationPublisher;
import com.koostack.operio.core.event.models.TenantEvent;
import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.plugin.consent.redis.ConsentPolicyRedisRepository;
import com.koostack.operio.plugin.consent.redis.ConsentPolicyRedisRepository.AffectedEntry;
import com.koostack.operio.tenant.event.jooq.StreamEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Stream projection handler that materializes tenant consent domain events
 * into the Redis decision cache using a Hash-per-subject schema with
 * reverse indices.
 *
 * <p>Switches on {@code eventType} and writes to Redis via
 * {@link ConsentPolicyRedisRepository} (Hash-based consent decisions with
 * reverse indices for O(1) invalidation).
 *
 * <p>Handles policy events (grant/revoke/bulk-revoke), metadata events
 * (field-category link/unlink), and structural deletion events (category
 * deleted, field removed, entity deleted, purpose deleted).
 *
 * <p>All writes are routed to the correct tenant's Redis key namespace via
 * {@link TenantContext}, set from the event's {@code tenantId} field.
 */
public class ConsentDecisionProjectionHandler implements StreamEventHandler {

    private static final Logger log = LoggerFactory.getLogger(ConsentDecisionProjectionHandler.class);

    private static final String WILDCARD = "*";

    private final ObjectMapper objectMapper;
    private final ConsentPolicyRedisRepository consentRepo;
    private final InvalidationPublisher invalidationPublisher;

    public ConsentDecisionProjectionHandler(ConsentPolicyRedisRepository consentRepo) {
        this(consentRepo, null, defaultObjectMapper());
    }

    public ConsentDecisionProjectionHandler(
        ConsentPolicyRedisRepository consentRepo,
        InvalidationPublisher invalidationPublisher
    ) {
        this(consentRepo, invalidationPublisher, defaultObjectMapper());
    }

    public ConsentDecisionProjectionHandler(
        ConsentPolicyRedisRepository consentRepo,
        InvalidationPublisher invalidationPublisher,
        ObjectMapper objectMapper
    ) {
        this.consentRepo = consentRepo;
        this.invalidationPublisher = invalidationPublisher;
        this.objectMapper = objectMapper;
    }

    private static ObjectMapper defaultObjectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
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

            // -- Field ↔ Category metadata (publish-only) --
            case ConsentEventTypes.ENTITY_FIELD_CATEGORY_LINKED -> handleFieldCategoryLinked(tenantId, node);
            case ConsentEventTypes.ENTITY_FIELD_CATEGORY_UNLINKED -> handleFieldCategoryUnlinked(tenantId, node);

            // -- Structural deletion (reverse-index cleanup + publish) --
            case ConsentEventTypes.CATEGORY_DELETED -> handleCategoryDeleted(tenantId, node);
            case ConsentEventTypes.ENTITY_FIELD_REMOVED -> handleEntityFieldRemoved(tenantId, node);
            case ConsentEventTypes.ENTITY_DELETED -> handleEntityDeleted(tenantId, node);
            case ConsentEventTypes.PURPOSE_DELETED -> handlePurposeDeleted(tenantId, node);

            // Events not relevant to the decision cache are silently ignored
            default -> {}
        }
    }

    // -----------------------------------------------------------------------
    // Consent Policy
    // -----------------------------------------------------------------------

    private void handlePolicyGrantedOrRevoked(String tenantId, JsonNode node) {
        String subjectId = requiredText(node, "subjectId");
        String purposeName = requiredText(node, "purposeName");
        boolean granted = requiredBoolean(node, "granted");

        String category = textOrNull(node, "category");
        String fieldName = textOrNull(node, "fieldName");
        String entityName = textOrNull(node, "entityName");

        if (!isWildcard(fieldName) && !isWildcard(entityName)) {
            // Field-level: (*, field, entity, purpose)
            consentRepo.setFieldConsent(subjectId, entityName, fieldName, purposeName, granted);
            publishInvalidation(new CacheInvalidationMessage(
                UUID.randomUUID().toString(), tenantId, subjectId,
                entityName, fieldName, null, purposeName,
                granted ? InvalidationAction.GRANTED : InvalidationAction.REVOKED
            ));
        } else if (!isWildcard(category)) {
            // Category-level: (category, *, *, purpose)
            consentRepo.setCategoryConsent(subjectId, category, purposeName, granted);
            publishInvalidation(new CacheInvalidationMessage(
                UUID.randomUUID().toString(), tenantId, subjectId,
                null, null, category, purposeName,
                granted ? InvalidationAction.GRANTED : InvalidationAction.REVOKED
            ));
        } else {
            log.warn("Policy event has wildcards in all dimensions — skipping");
        }
    }

    private void handlePolicyAllRevoked(String tenantId, JsonNode node) {
        String subjectId = requiredText(node, "subjectId");
        consentRepo.removeAllConsent(subjectId);
        publishInvalidation(new CacheInvalidationMessage(
            UUID.randomUUID().toString(), tenantId, subjectId,
            null, null, null, null, InvalidationAction.ALL_REVOKED
        ));
    }

    // -----------------------------------------------------------------------
    // Field ↔ Category metadata (publish-only)
    // -----------------------------------------------------------------------

    private void handleFieldCategoryLinked(String tenantId, JsonNode node) {
        String entityName = requiredText(node, "entityName");
        String fieldName = requiredText(node, "fieldName");
        String categoryName = requiredText(node, "categoryName");
        publishInvalidation(new CacheInvalidationMessage(
            UUID.randomUUID().toString(), tenantId, null,
            entityName, fieldName, categoryName, null, InvalidationAction.CATEGORY_CHANGED
        ));
    }

    private void handleFieldCategoryUnlinked(String tenantId, JsonNode node) {
        String entityName = requiredText(node, "entityName");
        String fieldName = requiredText(node, "fieldName");
        String categoryName = requiredText(node, "categoryName");
        publishInvalidation(new CacheInvalidationMessage(
            UUID.randomUUID().toString(), tenantId, null,
            entityName, fieldName, categoryName, null, InvalidationAction.CATEGORY_CHANGED
        ));
    }

    // -----------------------------------------------------------------------
    // Structural deletion — reverse-index cleanup + publish
    // -----------------------------------------------------------------------

    private void handleCategoryDeleted(String tenantId, JsonNode node) {
        String categoryName = requiredText(node, "categoryName");

        List<AffectedEntry> affected = consentRepo.deleteByCategory(categoryName);

        for (AffectedEntry entry : affected) {
            publishInvalidation(new CacheInvalidationMessage(
                UUID.randomUUID().toString(), tenantId, entry.subject(),
                null, null, categoryName, entry.purpose(),
                InvalidationAction.REVOKED
            ));
        }

        publishInvalidation(new CacheInvalidationMessage(
            UUID.randomUUID().toString(), tenantId, null,
            null, null, categoryName, null, InvalidationAction.CATEGORY_CHANGED
        ));
    }

    private void handleEntityFieldRemoved(String tenantId, JsonNode node) {
        String entityName = requiredText(node, "entityName");
        String fieldName = requiredText(node, "fieldName");
        String qualifiedField = ConsentPolicyRedisRepository.qualifyField(entityName, fieldName);

        List<AffectedEntry> affected = consentRepo.deleteByField(qualifiedField);

        for (AffectedEntry entry : affected) {
            publishInvalidation(new CacheInvalidationMessage(
                UUID.randomUUID().toString(), tenantId, entry.subject(),
                entityName, fieldName, null, entry.purpose(),
                InvalidationAction.REVOKED
            ));
        }

        publishInvalidation(new CacheInvalidationMessage(
            UUID.randomUUID().toString(), tenantId, null,
            entityName, fieldName, null, null, InvalidationAction.FIELD_REMOVED
        ));
    }

    private void handleEntityDeleted(String tenantId, JsonNode node) {
        String entityName = requiredText(node, "entityName");
        // The entity deletion event doesn't carry the field list.
        // Log and publish a broad invalidation — individual field cleanup
        // happens when each ENTITY_FIELD_REMOVED event is processed.
        log.info("Entity '{}' deleted — consent cleanup deferred to per-field removal events", entityName);
        publishInvalidation(new CacheInvalidationMessage(
            UUID.randomUUID().toString(), tenantId, null,
            entityName, null, null, null, InvalidationAction.FIELD_REMOVED
        ));
    }

    private void handlePurposeDeleted(String tenantId, JsonNode node) {
        String purposeName = requiredText(node, "purposeName");

        List<AffectedEntry> affected = consentRepo.deleteByPurpose(purposeName);

        for (AffectedEntry entry : affected) {
            publishInvalidation(new CacheInvalidationMessage(
                UUID.randomUUID().toString(), tenantId, entry.subject(),
                null, null, null, purposeName,
                InvalidationAction.REVOKED
            ));
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Treats both null and {@code "*"} as wildcard for backwards compatibility. */
    private static boolean isWildcard(String value) {
        return value == null || WILDCARD.equals(value);
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

    private static boolean requiredBoolean(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child == null || child.isNull()) {
            throw new IllegalArgumentException("Missing required payload field: " + field);
        }
        return child.asBoolean();
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
}
