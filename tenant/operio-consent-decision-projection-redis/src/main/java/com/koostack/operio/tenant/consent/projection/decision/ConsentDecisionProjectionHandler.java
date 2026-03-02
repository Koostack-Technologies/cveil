package com.koostack.operio.tenant.consent.projection.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.koostack.operio.core.tenant.consent.ConsentEventTypes;
import com.koostack.operio.core.tenant.consent.invalidation.CacheInvalidationMessage;
import com.koostack.operio.core.tenant.consent.invalidation.InvalidationAction;
import com.koostack.operio.core.tenant.consent.invalidation.InvalidationPublisher;
import com.koostack.operio.core.event.models.TenantEvent;
import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.plugin.consent.redis.ConsentPolicyRedisRepository;
import com.koostack.operio.plugin.consent.redis.ConsentPolicyRedisRepository.AffectedEntry;
import com.koostack.operio.tenant.event.jooq.StreamEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
            // -- SubjectConsent Aggregate (consent ledger events) --
            case ConsentEventTypes.CONSENT_GRANTED -> handleConsentGrantedOrRevoked(tenantId, node, true);
            case ConsentEventTypes.CONSENT_REVOKED -> handleConsentGrantedOrRevoked(tenantId, node, false);
            case ConsentEventTypes.CONSENT_EXPIRED -> handleConsentGrantedOrRevoked(tenantId, node, false);
            case ConsentEventTypes.CONSENT_ALL_REVOKED -> handleConsentAllRevoked(tenantId, node);

            // -- Field ↔ Category metadata (publish-only) --
            case ConsentEventTypes.FIELD_CATEGORY_ASSIGNED -> handleFieldCategoryChanged(tenantId, node);
            case ConsentEventTypes.FIELD_CATEGORY_UNASSIGNED -> handleFieldCategoryChanged(tenantId, node);

            // -- Structural deletion (reverse-index cleanup + publish) --
            case ConsentEventTypes.FIELD_REMOVED -> handleFieldRemoved(tenantId, node);
            case ConsentEventTypes.PURPOSE_REMOVED -> handlePurposeRemoved(tenantId, node);

            // Events not relevant to the decision cache are silently ignored
            default -> {}
        }
    }

    // -----------------------------------------------------------------------
    // SubjectConsent Aggregate (consent ledger events)
    // -----------------------------------------------------------------------

    private void handleConsentGrantedOrRevoked(String tenantId, JsonNode node, boolean granted) {
        String subjectId = requiredText(node, "subjectId");
        String purposeName = requiredText(node, "purposeName");
        String policyType = requiredText(node, "policyType");

        String fieldName = textOrNull(node, "fieldName");
        String entityName = textOrNull(node, "entityName");
        String categoryName = textOrNull(node, "categoryName");

        if ("FIELD".equals(policyType)) {
            consentRepo.setFieldConsent(subjectId, entityName, fieldName, purposeName, granted);
            publishInvalidation(new CacheInvalidationMessage(
                UUID.randomUUID().toString(), tenantId, subjectId,
                entityName, fieldName, null, purposeName,
                granted ? InvalidationAction.GRANTED : InvalidationAction.REVOKED
            ));
        } else if ("CATEGORY".equals(policyType)) {
            consentRepo.setCategoryConsent(subjectId, categoryName, purposeName, granted);
            publishInvalidation(new CacheInvalidationMessage(
                UUID.randomUUID().toString(), tenantId, subjectId,
                null, null, categoryName, purposeName,
                granted ? InvalidationAction.GRANTED : InvalidationAction.REVOKED
            ));
        } else {
            log.warn("Unknown policyType '{}' in consent event — skipping", policyType);
        }
    }

    private void handleConsentAllRevoked(String tenantId, JsonNode node) {
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

    private void handleFieldCategoryChanged(String tenantId, JsonNode node) {
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

    private void handleFieldRemoved(String tenantId, JsonNode node) {
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

    private void handlePurposeRemoved(String tenantId, JsonNode node) {
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
