package com.koostack.operio.tenant.consent.projection.admin.redis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.koostack.operio.core.models.FieldView;
import com.koostack.operio.core.models.PurposeView;
import com.koostack.operio.core.models.RegulationView;
import com.koostack.operio.core.models.SubjectConsentView;
import com.koostack.operio.core.tenant.consent.ConsentEventTypes;
import com.koostack.operio.core.tenant.consent.invalidation.CacheInvalidationMessage;
import com.koostack.operio.core.tenant.consent.invalidation.InvalidationAction;
import com.koostack.operio.core.tenant.consent.invalidation.InvalidationPublisher;
import com.koostack.operio.core.event.models.TenantEvent;
import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.tenant.event.jooq.StreamEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Stream projection handler that materializes consent admin domain events
 * into Redis for fast reads.
 *
 * <p>Handles events for 4 aggregates: Field, Purpose, Regulation, and
 * SubjectConsent. DSAR events are intentionally excluded.
 *
 * <p>Views stored in Redis use {@code 0} for SQL auto-increment IDs since
 * this projection is independent of the relational schema. Aggregates are
 * referenced by name in the API, not by ID.
 *
 * <p>After each mutation, a {@link CacheInvalidationMessage} is published
 * to the admin invalidation channel.
 */
public class ConsentAdminRedisProjectionHandler implements StreamEventHandler {

    private static final Logger log = LoggerFactory.getLogger(ConsentAdminRedisProjectionHandler.class);

    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private final ConsentAdminRedisRepository repository;
    private final InvalidationPublisher invalidationPublisher;

    public ConsentAdminRedisProjectionHandler(
            ConsentAdminRedisRepository repository,
            InvalidationPublisher invalidationPublisher) {
        this.repository = repository;
        this.invalidationPublisher = invalidationPublisher;
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
                JsonNode node = payload != null
                        ? objectMapper.readTree(payload)
                        : objectMapper.createObjectNode();
                dispatch(tenantId, eventType, node);
                log.debug("Projected event {} [{}] for tenant {} to Redis",
                        messageId, eventType, tenantId);
            } catch (Exception e) {
                log.error("Failed to project event {} [{}] for tenant {} to Redis",
                        messageId, eventType, tenantId, e);
                throw new RuntimeException(e);
            }
        });
    }

    private void dispatch(String tenantId, String eventType, JsonNode node) {
        switch (eventType) {
            // -- Field Aggregate --
            case ConsentEventTypes.FIELD_REGISTERED -> handleFieldRegistered(tenantId, node);
            case ConsentEventTypes.FIELD_UPDATED -> handleFieldUpdated(tenantId, node);
            case ConsentEventTypes.FIELD_REMOVED -> handleFieldRemoved(tenantId, node);
            case ConsentEventTypes.FIELD_CATEGORY_ASSIGNED -> handleFieldCategoryAssigned(tenantId, node);
            case ConsentEventTypes.FIELD_CATEGORY_UNASSIGNED -> handleFieldCategoryUnassigned(tenantId, node);

            // -- Purpose Aggregate --
            case ConsentEventTypes.PURPOSE_REGISTERED -> handlePurposeRegistered(tenantId, node);
            case ConsentEventTypes.PURPOSE_UPDATED -> handlePurposeUpdated(tenantId, node);
            case ConsentEventTypes.PURPOSE_REMOVED -> handlePurposeRemoved(tenantId, node);
            case ConsentEventTypes.PURPOSE_REGULATION_LINKED -> handlePurposeRegulationLinked(tenantId, node);
            case ConsentEventTypes.PURPOSE_REGULATION_UNLINKED -> handlePurposeRegulationUnlinked(tenantId, node);
            case ConsentEventTypes.PURPOSE_POLICY_ADDED -> handlePurposePolicyAdded(tenantId, node);
            case ConsentEventTypes.PURPOSE_POLICY_REMOVED -> handlePurposePolicyRemoved(tenantId, node);

            // -- Regulation Aggregate --
            case ConsentEventTypes.REGULATION_REGISTERED -> handleRegulationRegistered(tenantId, node);
            case ConsentEventTypes.REGULATION_UPDATED -> handleRegulationUpdated(tenantId, node);
            case ConsentEventTypes.REGULATION_REMOVED -> handleRegulationRemoved(tenantId, node);
            case ConsentEventTypes.REGULATION_JURISDICTION_ADDED -> handleRegulationJurisdictionAdded(tenantId, node);
            case ConsentEventTypes.REGULATION_JURISDICTION_REMOVED -> handleRegulationJurisdictionRemoved(tenantId, node);

            // -- SubjectConsent Aggregate --
            case ConsentEventTypes.CONSENT_GRANTED -> handleConsentLedgerEntry(tenantId, node);
            case ConsentEventTypes.CONSENT_REVOKED -> handleConsentLedgerEntry(tenantId, node);
            case ConsentEventTypes.CONSENT_EXPIRED -> handleConsentLedgerEntry(tenantId, node);
            case ConsentEventTypes.CONSENT_ALL_REVOKED -> handleConsentAllRevoked(tenantId, node);

            // DSAR intentionally excluded
            default -> log.trace("Ignoring event type: {}", eventType);
        }
    }

    // ── Field Aggregate ────────────────────────────────────────────────────

    private void handleFieldRegistered(String tenantId, JsonNode node) {
        String entityName = requiredText(node, "entityName");
        String fieldName = requiredText(node, "fieldName");
        String description = textOrNull(node, "description");

        FieldView view = new FieldView(
                0L, fieldName, description,
                new FieldView.EntityView(0L, entityName),
                List.of());
        repository.saveField(view);
        publishInvalidation(tenantId, InvalidationAction.FIELD_CHANGED, entityName, fieldName, null, null);
    }

    private void handleFieldUpdated(String tenantId, JsonNode node) {
        String entityName = requiredText(node, "entityName");
        String fieldName = requiredText(node, "fieldName");
        JsonNode snapshotNode = node.get("snapshot");
        if (snapshotNode == null || snapshotNode.isNull()) {
            log.warn("Field update for '{}/{}' missing snapshot — skipping Redis projection", entityName, fieldName);
            return;
        }
        repository.saveField(fieldViewFromSnapshot(snapshotNode));
        publishInvalidation(tenantId, InvalidationAction.FIELD_CHANGED, entityName, fieldName, null, null);
    }

    private void handleFieldRemoved(String tenantId, JsonNode node) {
        String entityName = requiredText(node, "entityName");
        String fieldName = requiredText(node, "fieldName");

        repository.removeField(entityName, fieldName);
        publishInvalidation(tenantId, InvalidationAction.FIELD_CHANGED, entityName, fieldName, null, null);
    }

    private void handleFieldCategoryAssigned(String tenantId, JsonNode node) {
        String entityName = requiredText(node, "entityName");
        String fieldName = requiredText(node, "fieldName");
        String categoryName = requiredText(node, "categoryName");
        JsonNode snapshotNode = node.get("snapshot");
        if (snapshotNode == null || snapshotNode.isNull()) {
            log.warn("Field category assign for '{}/{}' missing snapshot — skipping", entityName, fieldName);
            return;
        }
        repository.saveField(fieldViewFromSnapshot(snapshotNode));
        publishInvalidation(tenantId, InvalidationAction.FIELD_CHANGED, entityName, fieldName, categoryName, null);
    }

    private void handleFieldCategoryUnassigned(String tenantId, JsonNode node) {
        String entityName = requiredText(node, "entityName");
        String fieldName = requiredText(node, "fieldName");
        String categoryName = requiredText(node, "categoryName");
        JsonNode snapshotNode = node.get("snapshot");
        if (snapshotNode == null || snapshotNode.isNull()) {
            log.warn("Field category unassign for '{}/{}' missing snapshot — skipping", entityName, fieldName);
            return;
        }
        repository.saveField(fieldViewFromSnapshot(snapshotNode));
        publishInvalidation(tenantId, InvalidationAction.FIELD_CHANGED, entityName, fieldName, categoryName, null);
    }

    // ── Purpose Aggregate ──────────────────────────────────────────────────

    private void handlePurposeRegistered(String tenantId, JsonNode node) {
        String purposeName = requiredText(node, "purposeName");
        String description = textOrNull(node, "description");

        PurposeView view = new PurposeView(
                0L, purposeName, description, List.of(), List.of());
        repository.savePurpose(view);
        publishInvalidation(tenantId, InvalidationAction.PURPOSE_CHANGED, null, null, null, purposeName);
    }

    private void handlePurposeUpdated(String tenantId, JsonNode node) {
        String purposeName = requiredText(node, "purposeName");
        JsonNode snapshotNode = node.get("snapshot");
        if (snapshotNode == null || snapshotNode.isNull()) {
            log.warn("Purpose update for '{}' missing snapshot — skipping", purposeName);
            return;
        }
        repository.savePurpose(purposeViewFromSnapshot(snapshotNode));
        publishInvalidation(tenantId, InvalidationAction.PURPOSE_CHANGED, null, null, null, purposeName);
    }

    private void handlePurposeRemoved(String tenantId, JsonNode node) {
        String purposeName = requiredText(node, "purposeName");
        repository.removePurpose(purposeName);
        publishInvalidation(tenantId, InvalidationAction.PURPOSE_CHANGED, null, null, null, purposeName);
    }

    private void handlePurposeRegulationLinked(String tenantId, JsonNode node) {
        String purposeName = requiredText(node, "purposeName");
        JsonNode snapshotNode = node.get("snapshot");
        if (snapshotNode == null || snapshotNode.isNull()) {
            log.warn("Purpose regulation link for '{}' missing snapshot — skipping", purposeName);
            return;
        }
        repository.savePurpose(purposeViewFromSnapshot(snapshotNode));
        publishInvalidation(tenantId, InvalidationAction.PURPOSE_CHANGED, null, null, null, purposeName);
    }

    private void handlePurposeRegulationUnlinked(String tenantId, JsonNode node) {
        String purposeName = requiredText(node, "purposeName");
        JsonNode snapshotNode = node.get("snapshot");
        if (snapshotNode == null || snapshotNode.isNull()) {
            log.warn("Purpose regulation unlink for '{}' missing snapshot — skipping", purposeName);
            return;
        }
        repository.savePurpose(purposeViewFromSnapshot(snapshotNode));
        publishInvalidation(tenantId, InvalidationAction.PURPOSE_CHANGED, null, null, null, purposeName);
    }

    private void handlePurposePolicyAdded(String tenantId, JsonNode node) {
        String purposeName = requiredText(node, "purposeName");
        JsonNode snapshotNode = node.get("snapshot");
        if (snapshotNode == null || snapshotNode.isNull()) {
            log.warn("Purpose policy add for '{}' missing snapshot — skipping", purposeName);
            return;
        }
        repository.savePurpose(purposeViewFromSnapshot(snapshotNode));
        publishInvalidation(tenantId, InvalidationAction.PURPOSE_CHANGED, null, null, null, purposeName);
    }

    private void handlePurposePolicyRemoved(String tenantId, JsonNode node) {
        String purposeName = requiredText(node, "purposeName");
        JsonNode snapshotNode = node.get("snapshot");
        if (snapshotNode == null || snapshotNode.isNull()) {
            log.warn("Purpose policy remove for '{}' missing snapshot — skipping", purposeName);
            return;
        }
        repository.savePurpose(purposeViewFromSnapshot(snapshotNode));
        publishInvalidation(tenantId, InvalidationAction.PURPOSE_CHANGED, null, null, null, purposeName);
    }

    // ── Regulation Aggregate ───────────────────────────────────────────────

    private void handleRegulationRegistered(String tenantId, JsonNode node) {
        String regulationName = requiredText(node, "regulationName");
        String description = textOrNull(node, "description");

        RegulationView view = new RegulationView(0L, regulationName, description, List.of());
        repository.saveRegulation(view);
        publishInvalidation(tenantId, InvalidationAction.REGULATION_CHANGED, null, null, null, null);
    }

    private void handleRegulationUpdated(String tenantId, JsonNode node) {
        String regulationName = requiredText(node, "regulationName");
        JsonNode snapshotNode = node.get("snapshot");
        if (snapshotNode == null || snapshotNode.isNull()) {
            log.warn("Regulation update for '{}' missing snapshot — skipping", regulationName);
            return;
        }
        repository.saveRegulation(regulationViewFromSnapshot(snapshotNode));
        publishInvalidation(tenantId, InvalidationAction.REGULATION_CHANGED, null, null, null, null);
    }

    private void handleRegulationRemoved(String tenantId, JsonNode node) {
        String regulationName = requiredText(node, "regulationName");
        repository.removeRegulation(regulationName);
        publishInvalidation(tenantId, InvalidationAction.REGULATION_CHANGED, null, null, null, null);
    }

    private void handleRegulationJurisdictionAdded(String tenantId, JsonNode node) {
        String regulationName = requiredText(node, "regulationName");
        JsonNode snapshotNode = node.get("snapshot");
        if (snapshotNode == null || snapshotNode.isNull()) {
            log.warn("Regulation jurisdiction add for '{}' missing snapshot — skipping", regulationName);
            return;
        }
        repository.saveRegulation(regulationViewFromSnapshot(snapshotNode));
        publishInvalidation(tenantId, InvalidationAction.REGULATION_CHANGED, null, null, null, null);
    }

    private void handleRegulationJurisdictionRemoved(String tenantId, JsonNode node) {
        String regulationName = requiredText(node, "regulationName");
        JsonNode snapshotNode = node.get("snapshot");
        if (snapshotNode == null || snapshotNode.isNull()) {
            log.warn("Regulation jurisdiction remove for '{}' missing snapshot — skipping", regulationName);
            return;
        }
        repository.saveRegulation(regulationViewFromSnapshot(snapshotNode));
        publishInvalidation(tenantId, InvalidationAction.REGULATION_CHANGED, null, null, null, null);
    }

    // ── SubjectConsent Aggregate ───────────────────────────────────────────

    private void handleConsentLedgerEntry(String tenantId, JsonNode node) {
        String subjectId = requiredText(node, "subjectId");
        long policyId = requiredLong(node, "policyId");
        String action = textOrNull(node, "action");
        String actor = textOrNull(node, "actor");
        String ipAddress = textOrNull(node, "ipAddress");
        String userAgent = textOrNull(node, "userAgent");
        String purposeName = textOrNull(node, "purposeName");
        String policyType = textOrNull(node, "policyType");
        String fieldName = textOrNull(node, "fieldName");
        String entityName = textOrNull(node, "entityName");
        String categoryName = textOrNull(node, "categoryName");
        Instant expiresAt = instantOrNull(node, "expiresAt");
        Instant now = Instant.now();

        SubjectConsentView existing = repository.getSubjectConsent(subjectId);
        List<SubjectConsentView.ConsentLedgerEntryView> entries = existing != null
                ? new ArrayList<>(existing.entries()) : new ArrayList<>();

        // Upsert by subjectId + policyId
        entries.removeIf(e -> e.policyId() == policyId);
        entries.add(new SubjectConsentView.ConsentLedgerEntryView(
                0L, subjectId, policyId, purposeName, policyType,
                fieldName, entityName, categoryName,
                action, actor, ipAddress, userAgent,
                now, now, expiresAt));

        repository.saveSubjectConsent(new SubjectConsentView(subjectId, List.copyOf(entries)));
        publishInvalidation(tenantId, InvalidationAction.CONSENT_ENTRY_CHANGED, null, null, null, null);
    }

    private void handleConsentAllRevoked(String tenantId, JsonNode node) {
        String subjectId = requiredText(node, "subjectId");
        String actor = textOrNull(node, "actor");
        String ipAddress = textOrNull(node, "ipAddress");
        String userAgent = textOrNull(node, "userAgent");
        Instant now = Instant.now();

        SubjectConsentView existing = repository.getSubjectConsent(subjectId);
        if (existing == null || existing.entries().isEmpty()) {
            repository.removeSubjectConsent(subjectId);
        } else {
            // Mark all entries as REVOKE
            List<SubjectConsentView.ConsentLedgerEntryView> updated = existing.entries().stream()
                    .map(e -> new SubjectConsentView.ConsentLedgerEntryView(
                            e.consentLedgerId(), e.subjectId(), e.policyId(),
                            e.purposeName(), e.policyType(), e.fieldName(),
                            e.entityName(), e.categoryName(),
                            "REVOKE", actor, ipAddress, userAgent,
                            e.createdAt(), now, null))
                    .toList();
            repository.saveSubjectConsent(new SubjectConsentView(subjectId, updated));
        }
        publishInvalidation(tenantId, InvalidationAction.CONSENT_ENTRY_CHANGED, null, null, null, null);
    }

    // ── Invalidation Publishing ────────────────────────────────────────────

    private void publishInvalidation(String tenantId, InvalidationAction action,
                                     String entity, String field, String category, String purpose) {
        if (invalidationPublisher == null) return;
        try {
            CacheInvalidationMessage msg = new CacheInvalidationMessage(
                    UUID.randomUUID().toString(), tenantId, null,
                    entity, field, category, purpose, action);
            invalidationPublisher.publish(msg);
        } catch (Exception e) {
            log.warn("Failed to publish admin invalidation — action={}", action, e);
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static String requiredText(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child == null || child.isNull()) {
            throw new IllegalArgumentException("Missing required event field: " + field);
        }
        return child.asText();
    }

    private static long requiredLong(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child == null || child.isNull()) {
            throw new IllegalArgumentException("Missing required event field: " + field);
        }
        return child.asLong();
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return (child != null && !child.isNull()) ? child.asText() : null;
    }

    private static Instant instantOrNull(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child == null || child.isNull()) return null;
        try {
            return Instant.parse(child.asText());
        } catch (Exception e) {
            return null;
        }
    }

    // ── Snapshot → View converters ──────────────────────────────────────────

    private static FieldView fieldViewFromSnapshot(JsonNode s) {
        String fieldName = requiredText(s, "fieldName");
        String entityName = requiredText(s, "entityName");
        String description = textOrNull(s, "description");
        List<FieldView.CategoryView> categories = new ArrayList<>();
        JsonNode cats = s.get("categoryNames");
        if (cats != null && cats.isArray()) {
            for (JsonNode c : cats) {
                categories.add(new FieldView.CategoryView(0L, c.asText()));
            }
        }
        return new FieldView(0L, fieldName, description,
                new FieldView.EntityView(0L, entityName), List.copyOf(categories));
    }

    private static PurposeView purposeViewFromSnapshot(JsonNode s) {
        String purposeName = requiredText(s, "purposeName");
        String description = textOrNull(s, "description");
        List<PurposeView.RegulationLinkView> regs = new ArrayList<>();
        JsonNode regsNode = s.get("regulations");
        if (regsNode != null && regsNode.isArray()) {
            for (JsonNode r : regsNode) {
                regs.add(new PurposeView.RegulationLinkView(
                        requiredText(r, "regulationName"),
                        textOrNull(r, "lawfulBasis"),
                        textOrNull(r, "onGranted"),
                        textOrNull(r, "onDenied")));
            }
        }
        List<PurposeView.PolicyView> policies = new ArrayList<>();
        JsonNode polsNode = s.get("policies");
        if (polsNode != null && polsNode.isArray()) {
            for (JsonNode p : polsNode) {
                String policyType = requiredText(p, "policyType");
                String fieldName = textOrNull(p, "fieldName");
                String entityName = textOrNull(p, "entityName");
                String categoryName = textOrNull(p, "categoryName");
                PurposeView.CategoryRef catRef = categoryName != null
                        ? new PurposeView.CategoryRef(0L, categoryName) : null;
                PurposeView.FieldRef fieldRef = fieldName != null
                        ? new PurposeView.FieldRef(0L, fieldName, entityName) : null;
                policies.add(new PurposeView.PolicyView(0L, policyType, catRef, fieldRef));
            }
        }
        return new PurposeView(0L, purposeName, description,
                List.copyOf(regs), List.copyOf(policies));
    }

    private static RegulationView regulationViewFromSnapshot(JsonNode s) {
        String regulationName = requiredText(s, "regulationName");
        String description = textOrNull(s, "description");
        List<RegulationView.JurisdictionView> jurs = new ArrayList<>();
        JsonNode jursNode = s.get("jurisdictionNames");
        if (jursNode != null && jursNode.isArray()) {
            for (JsonNode j : jursNode) {
                jurs.add(new RegulationView.JurisdictionView(0L, j.asText()));
            }
        }
        return new RegulationView(0L, regulationName, description, List.copyOf(jurs));
    }
}
