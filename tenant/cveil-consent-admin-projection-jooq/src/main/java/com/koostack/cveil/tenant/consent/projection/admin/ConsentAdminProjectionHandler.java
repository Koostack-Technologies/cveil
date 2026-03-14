package com.koostack.cveil.tenant.consent.projection.admin;

import static com.koostack.cveil.plugin.consent.jooq.generated.Tables.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.koostack.cveil.core.event.models.TenantEvent;
import com.koostack.cveil.core.models.TenantContext;
import com.koostack.cveil.core.tenant.consent.ConsentEventTypes;
import com.koostack.cveil.core.tenant.consent.aggregates.*;
import com.koostack.cveil.plugin.consent.jooq.generated.enums.ConsentAction;
import com.koostack.cveil.plugin.consent.jooq.generated.enums.MaskingType;
import com.koostack.cveil.plugin.consent.jooq.generated.enums.PolicyType;
import com.koostack.cveil.plugin.event.jooq.StreamEventHandler;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stream projection handler that materializes consent domain events into the consent admin
 * relational schema.
 *
 * <p>Handles events for all 8 aggregates: Entity, Category, Field, Purpose, Regulation,
 * Jurisdiction, SubjectConsent, and DSAR. All writes are idempotent (upsert or delete) and routed
 * to the correct per-tenant schema via {@link TenantContext}.
 *
 * <p>All FK references use deterministic UUIDs derived from aggregate {@code deriveId()} methods,
 * enabling pure writes with zero lookups. Consent ledger entries use the policy UUID carried
 * directly on the event, also avoiding any lookups.
 */
public class ConsentAdminProjectionHandler implements StreamEventHandler {

    private static final Logger log = LoggerFactory.getLogger(ConsentAdminProjectionHandler.class);

    private static final ObjectMapper objectMapper =
            new ObjectMapper().registerModule(new JavaTimeModule());

    private final DSLContext dsl;

    /**
     * Transactional DSL set during {@link #onEvent} — all handler methods use {@link #activeDsl()}
     * which returns this when inside a transaction, otherwise falls back to the root DSL.
     *
     * <p>Uses {@link ScopedValue} (not a volatile instance field) because the {@link
     * com.koostack.cveil.plugin.event.jooq.ShardedStreamConsumer} dispatches events for different
     * tenants concurrently on separate virtual threads sharing this single handler instance. A
     * volatile field would allow one tenant's virtual thread to overwrite another's transaction
     * context. ScopedValue is consistent with {@link TenantContext} and enforces structured
     * lifetime without manual cleanup.
     */
    private static final ScopedValue<DSLContext> TX_DSL = ScopedValue.newInstance();

    /**
     * Scoped stream message ID — bound per-event so handler methods can derive deterministic UUIDs
     * for projection rows that don't have a domain aggregate key (e.g. consent_ledger,
     * dsar_audit_log). Using the stream messageId guarantees replay-safe, idempotent UUIDs.
     */
    private static final ScopedValue<String> EVENT_MSG_ID = ScopedValue.newInstance();

    public ConsentAdminProjectionHandler(DSLContext dsl) {
        this.dsl = dsl;
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

        TenantContext.runWhere(
                tenantId,
                () -> {
                    try {
                        JsonNode node =
                                payload != null
                                        ? objectMapper.readTree(payload)
                                        : objectMapper.createObjectNode();
                        // Wrap in a transaction so all DB operations for this event
                        // use a single pooled connection. This avoids "statement has
                        // been closed" errors from connection recycling between
                        // individual statements and reduces SET app.tenant_id calls
                        // to one per event instead of one per SQL statement.
                        dsl.transaction(
                                txConfig -> {
                                    ScopedValue.where(TX_DSL, txConfig.dsl())
                                            .where(EVENT_MSG_ID, messageId)
                                            .run(() -> dispatch(eventType, node));
                                });
                    } catch (Exception e) {
                        log.error(
                                "Failed to project event {} [{}] for tenant {}",
                                messageId,
                                eventType,
                                tenantId,
                                e);
                        throw new RuntimeException(e);
                    }
                });
    }

    /** Returns the transactional DSL if inside a transaction, otherwise the root DSL. */
    private DSLContext activeDsl() {
        return TX_DSL.isBound() ? TX_DSL.get() : dsl;
    }

    /** Returns the current event's stream message ID (bound per-event via ScopedValue). */
    private String eventMessageId() {
        return EVENT_MSG_ID.get();
    }

    /** Derives a deterministic UUID v3 (MD5) from the given key string. */
    private static UUID deriveUuid(String key) {
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    private void dispatch(String eventType, JsonNode node) {
        switch (eventType) {
                // -- Entity Aggregate --
            case ConsentEventTypes.ENTITY_REGISTERED -> handleEntityRegistered(node);
            case ConsentEventTypes.ENTITY_UPDATED -> handleEntityUpdated(node);
            case ConsentEventTypes.ENTITY_REMOVED -> handleEntityRemoved(node);

                // -- Category Aggregate --
            case ConsentEventTypes.CATEGORY_REGISTERED -> handleCategoryRegistered(node);
            case ConsentEventTypes.CATEGORY_UPDATED -> handleCategoryUpdated(node);
            case ConsentEventTypes.CATEGORY_REMOVED -> handleCategoryRemoved(node);

                // -- Field Aggregate --
            case ConsentEventTypes.FIELD_REGISTERED -> handleFieldRegistered(node);
            case ConsentEventTypes.FIELD_UPDATED -> handleFieldUpdated(node);
            case ConsentEventTypes.FIELD_REMOVED -> handleFieldRemoved(node);
            case ConsentEventTypes.FIELD_CATEGORY_ASSIGNED -> handleFieldCategoryAssigned(node);
            case ConsentEventTypes.FIELD_CATEGORY_UNASSIGNED -> handleFieldCategoryUnassigned(node);

                // -- Purpose Aggregate --
            case ConsentEventTypes.PURPOSE_REGISTERED -> handlePurposeRegistered(node);
            case ConsentEventTypes.PURPOSE_UPDATED -> handlePurposeUpdated(node);
            case ConsentEventTypes.PURPOSE_REMOVED -> handlePurposeRemoved(node);
            case ConsentEventTypes.PURPOSE_REGULATION_LINKED -> handlePurposeRegulationLinked(node);
            case ConsentEventTypes.PURPOSE_REGULATION_UNLINKED -> handlePurposeRegulationUnlinked(
                    node);
            case ConsentEventTypes.PURPOSE_POLICY_ADDED -> handlePurposePolicyAdded(node);
            case ConsentEventTypes.PURPOSE_POLICY_REMOVED -> handlePurposePolicyRemoved(node);

                // -- Regulation Aggregate --
            case ConsentEventTypes.REGULATION_REGISTERED -> handleRegulationRegistered(node);
            case ConsentEventTypes.REGULATION_UPDATED -> handleRegulationUpdated(node);
            case ConsentEventTypes.REGULATION_REMOVED -> handleRegulationRemoved(node);
            case ConsentEventTypes
                    .REGULATION_JURISDICTION_ADDED -> handleRegulationJurisdictionAdded(node);
            case ConsentEventTypes
                    .REGULATION_JURISDICTION_REMOVED -> handleRegulationJurisdictionRemoved(node);

                // -- Jurisdiction Aggregate --
            case ConsentEventTypes.JURISDICTION_REGISTERED -> handleJurisdictionRegistered(node);
            case ConsentEventTypes.JURISDICTION_UPDATED -> handleJurisdictionUpdated(node);
            case ConsentEventTypes.JURISDICTION_REMOVED -> handleJurisdictionRemoved(node);

                // -- Subject Aggregate (entity lifecycle) --
            case ConsentEventTypes.SUBJECT_REGISTERED -> handleSubjectRegistered(node);
            case ConsentEventTypes.SUBJECT_UPDATED -> handleSubjectUpdated(node);
            case ConsentEventTypes.SUBJECT_REMOVED -> handleSubjectRemoved(node);

                // -- SubjectConsent Aggregate --
            case ConsentEventTypes.CONSENT_GRANTED -> handleConsentGranted(node);
            case ConsentEventTypes.CONSENT_REVOKED -> handleConsentRevoked(node);
            case ConsentEventTypes.CONSENT_EXPIRED -> handleConsentExpired(node);
            case ConsentEventTypes.CONSENT_ALL_REVOKED -> handleConsentAllRevoked(node);

                // -- DSAR Aggregate --
            case ConsentEventTypes.SUBJECT_AUDIT_DELETED -> handleSubjectAuditDeleted(node);

            default -> log.debug("Ignoring unhandled event type: {}", eventType);
        }
    }

    // -----------------------------------------------------------------------
    // Entity Aggregate
    // -----------------------------------------------------------------------

    private void handleEntityRegistered(JsonNode node) {
        String entityName = node.get("entityName").asText();
        String description = textOrNull(node, "description");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String tid = TenantContext.require();
        UUID entityUuid = EntityAggregate.deriveId(entityName);

        activeDsl()
                .insertInto(ENTITY)
                .set(ENTITY.ENTITY_UUID, entityUuid)
                .set(ENTITY.TENANT_ID, tid)
                .set(ENTITY.ENTITY_NAME, entityName)
                .set(ENTITY.DESCRIPTION, description)
                .set(ENTITY.CREATED_AT, now)
                .set(ENTITY.UPDATED_AT, now)
                .onConflict(ENTITY.TENANT_ID, ENTITY.ENTITY_NAME)
                .doUpdate()
                .set(ENTITY.DESCRIPTION, description)
                .set(ENTITY.UPDATED_AT, now)
                .execute();
    }

    private void handleEntityUpdated(JsonNode node) {
        handleEntityRegistered(node);
    }

    private void handleEntityRemoved(JsonNode node) {
        String entityName = node.get("entityName").asText();
        String tid = TenantContext.require();
        UUID entityUuid = EntityAggregate.deriveId(entityName);

        // Remove field_category links for all fields of this entity
        activeDsl()
                .deleteFrom(FIELD_CATEGORY)
                .where(FIELD_CATEGORY.TENANT_ID.eq(tid))
                .and(
                        FIELD_CATEGORY.FIELD_UUID.in(
                                activeDsl()
                                        .select(FIELD.FIELD_UUID)
                                        .from(FIELD)
                                        .where(FIELD.ENTITY_UUID.eq(entityUuid))
                                        .and(FIELD.TENANT_ID.eq(tid))))
                .execute();

        activeDsl()
                .deleteFrom(FIELD)
                .where(FIELD.ENTITY_UUID.eq(entityUuid))
                .and(FIELD.TENANT_ID.eq(tid))
                .execute();

        activeDsl()
                .deleteFrom(ENTITY)
                .where(ENTITY.ENTITY_UUID.eq(entityUuid))
                .and(ENTITY.TENANT_ID.eq(tid))
                .execute();
    }

    // -----------------------------------------------------------------------
    // Category Aggregate
    // -----------------------------------------------------------------------

    private void handleCategoryRegistered(JsonNode node) {
        String categoryName = node.get("categoryName").asText();
        String description = textOrNull(node, "description");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String tid = TenantContext.require();
        UUID categoryUuid = CategoryAggregate.deriveId(categoryName);

        activeDsl()
                .insertInto(CATEGORY)
                .set(CATEGORY.CATEGORY_UUID, categoryUuid)
                .set(CATEGORY.TENANT_ID, tid)
                .set(CATEGORY.CATEGORY_NAME, categoryName)
                .set(CATEGORY.DESCRIPTION, description)
                .set(CATEGORY.CREATED_AT, now)
                .set(CATEGORY.UPDATED_AT, now)
                .onConflict(CATEGORY.TENANT_ID, CATEGORY.CATEGORY_NAME)
                .doUpdate()
                .set(CATEGORY.DESCRIPTION, description)
                .set(CATEGORY.UPDATED_AT, now)
                .execute();
    }

    private void handleCategoryUpdated(JsonNode node) {
        handleCategoryRegistered(node);
    }

    private void handleCategoryRemoved(JsonNode node) {
        String categoryName = node.get("categoryName").asText();
        String tid = TenantContext.require();
        UUID categoryUuid = CategoryAggregate.deriveId(categoryName);

        activeDsl()
                .deleteFrom(FIELD_CATEGORY)
                .where(FIELD_CATEGORY.CATEGORY_UUID.eq(categoryUuid))
                .and(FIELD_CATEGORY.TENANT_ID.eq(tid))
                .execute();

        activeDsl()
                .deleteFrom(CATEGORY)
                .where(CATEGORY.CATEGORY_UUID.eq(categoryUuid))
                .and(CATEGORY.TENANT_ID.eq(tid))
                .execute();
    }

    // -----------------------------------------------------------------------
    // Jurisdiction Aggregate
    // -----------------------------------------------------------------------

    private void handleJurisdictionRegistered(JsonNode node) {
        String jurisdictionName = node.get("jurisdictionName").asText();
        String description = textOrNull(node, "description");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String tid = TenantContext.require();
        UUID jurisdictionUuid = JurisdictionAggregate.deriveId(jurisdictionName);

        activeDsl()
                .insertInto(JURISDICTION)
                .set(JURISDICTION.JURISDICTION_UUID, jurisdictionUuid)
                .set(JURISDICTION.TENANT_ID, tid)
                .set(JURISDICTION.JURISDICTION_NAME, jurisdictionName)
                .set(JURISDICTION.DESCRIPTION, description)
                .set(JURISDICTION.CREATED_AT, now)
                .set(JURISDICTION.UPDATED_AT, now)
                .onConflict(JURISDICTION.TENANT_ID, JURISDICTION.JURISDICTION_NAME)
                .doUpdate()
                .set(JURISDICTION.DESCRIPTION, description)
                .set(JURISDICTION.UPDATED_AT, now)
                .execute();
    }

    private void handleJurisdictionUpdated(JsonNode node) {
        handleJurisdictionRegistered(node);
    }

    private void handleJurisdictionRemoved(JsonNode node) {
        String jurisdictionName = node.get("jurisdictionName").asText();
        String tid = TenantContext.require();
        UUID jurisdictionUuid = JurisdictionAggregate.deriveId(jurisdictionName);

        activeDsl()
                .deleteFrom(REGULATION_JURISDICTION)
                .where(REGULATION_JURISDICTION.JURISDICTION_UUID.eq(jurisdictionUuid))
                .and(REGULATION_JURISDICTION.TENANT_ID.eq(tid))
                .execute();

        activeDsl()
                .deleteFrom(JURISDICTION)
                .where(JURISDICTION.JURISDICTION_UUID.eq(jurisdictionUuid))
                .and(JURISDICTION.TENANT_ID.eq(tid))
                .execute();
    }

    // -----------------------------------------------------------------------
    // Field Aggregate
    // -----------------------------------------------------------------------

    private void handleFieldRegistered(JsonNode node) {
        String entityName = node.get("entityName").asText();
        String fieldName = node.get("fieldName").asText();
        String description = textOrNull(node, "description");
        String entityDescription = textOrNull(node, "entityDescription");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String tid = TenantContext.require();
        UUID entityUuid = EntityAggregate.deriveId(entityName);
        UUID fieldUuid = FieldAggregate.deriveId(entityName, fieldName);

        // Upsert entity (auto-create if needed)
        activeDsl()
                .insertInto(ENTITY)
                .set(ENTITY.ENTITY_UUID, entityUuid)
                .set(ENTITY.TENANT_ID, tid)
                .set(ENTITY.ENTITY_NAME, entityName)
                .set(ENTITY.DESCRIPTION, entityDescription)
                .set(ENTITY.CREATED_AT, now)
                .set(ENTITY.UPDATED_AT, now)
                .onConflict(ENTITY.TENANT_ID, ENTITY.ENTITY_NAME)
                .doUpdate()
                .set(ENTITY.UPDATED_AT, now)
                .execute();

        // Upsert field with direct UUID FK to entity
        activeDsl()
                .insertInto(FIELD)
                .set(FIELD.FIELD_UUID, fieldUuid)
                .set(FIELD.TENANT_ID, tid)
                .set(FIELD.FIELD_NAME, fieldName)
                .set(FIELD.ENTITY_UUID, entityUuid)
                .set(FIELD.DESCRIPTION, description)
                .set(FIELD.CREATED_AT, now)
                .set(FIELD.UPDATED_AT, now)
                .onConflict(FIELD.TENANT_ID, FIELD.FIELD_NAME, FIELD.ENTITY_UUID)
                .doUpdate()
                .set(FIELD.DESCRIPTION, description)
                .set(FIELD.UPDATED_AT, now)
                .execute();
    }

    private void handleFieldUpdated(JsonNode node) {
        String entityName = node.get("entityName").asText();
        String fieldName = node.get("fieldName").asText();
        String description = textOrNull(node, "description");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String tid = TenantContext.require();
        UUID fieldUuid = FieldAggregate.deriveId(entityName, fieldName);

        activeDsl()
                .update(FIELD)
                .set(FIELD.DESCRIPTION, description)
                .set(FIELD.UPDATED_AT, now)
                .where(FIELD.FIELD_UUID.eq(fieldUuid))
                .and(FIELD.TENANT_ID.eq(tid))
                .execute();
    }

    private void handleFieldRemoved(JsonNode node) {
        String entityName = node.get("entityName").asText();
        String fieldName = node.get("fieldName").asText();
        String tid = TenantContext.require();
        UUID fieldUuid = FieldAggregate.deriveId(entityName, fieldName);

        // FK order: consent_ledger → policy → field_category → field
        activeDsl()
                .deleteFrom(CONSENT_LEDGER)
                .where(CONSENT_LEDGER.TENANT_ID.eq(tid))
                .and(
                        CONSENT_LEDGER.POLICY_UUID.in(
                                activeDsl()
                                        .select(POLICY.POLICY_UUID)
                                        .from(POLICY)
                                        .where(POLICY.FIELD_UUID.eq(fieldUuid))
                                        .and(POLICY.TENANT_ID.eq(tid))))
                .execute();

        activeDsl()
                .deleteFrom(POLICY)
                .where(POLICY.FIELD_UUID.eq(fieldUuid))
                .and(POLICY.TENANT_ID.eq(tid))
                .execute();

        activeDsl()
                .deleteFrom(FIELD_CATEGORY)
                .where(FIELD_CATEGORY.FIELD_UUID.eq(fieldUuid))
                .and(FIELD_CATEGORY.TENANT_ID.eq(tid))
                .execute();

        activeDsl()
                .deleteFrom(FIELD)
                .where(FIELD.FIELD_UUID.eq(fieldUuid))
                .and(FIELD.TENANT_ID.eq(tid))
                .execute();
    }

    private void handleFieldCategoryAssigned(JsonNode node) {
        String entityName = node.get("entityName").asText();
        String fieldName = node.get("fieldName").asText();
        String categoryName = node.get("categoryName").asText();
        String categoryDescription = textOrNull(node, "categoryDescription");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String tid = TenantContext.require();
        UUID fieldUuid = FieldAggregate.deriveId(entityName, fieldName);
        UUID categoryUuid = CategoryAggregate.deriveId(categoryName);

        // Auto-create category if needed
        activeDsl()
                .insertInto(CATEGORY)
                .set(CATEGORY.CATEGORY_UUID, categoryUuid)
                .set(CATEGORY.TENANT_ID, tid)
                .set(CATEGORY.CATEGORY_NAME, categoryName)
                .set(CATEGORY.DESCRIPTION, categoryDescription)
                .set(CATEGORY.CREATED_AT, now)
                .set(CATEGORY.UPDATED_AT, now)
                .onConflict(CATEGORY.TENANT_ID, CATEGORY.CATEGORY_NAME)
                .doUpdate()
                .set(CATEGORY.UPDATED_AT, now)
                .execute();

        // Direct insert with UUID FKs
        activeDsl()
                .insertInto(FIELD_CATEGORY)
                .set(
                        FIELD_CATEGORY.FIELD_CATEGORY_UUID,
                        deriveUuid("consent-field-category:" + fieldUuid + ":" + categoryUuid))
                .set(FIELD_CATEGORY.TENANT_ID, tid)
                .set(FIELD_CATEGORY.FIELD_UUID, fieldUuid)
                .set(FIELD_CATEGORY.CATEGORY_UUID, categoryUuid)
                .set(FIELD_CATEGORY.CREATED_AT, now)
                .set(FIELD_CATEGORY.UPDATED_AT, now)
                .onConflict(
                        FIELD_CATEGORY.TENANT_ID,
                        FIELD_CATEGORY.FIELD_UUID,
                        FIELD_CATEGORY.CATEGORY_UUID)
                .doNothing()
                .execute();
    }

    private void handleFieldCategoryUnassigned(JsonNode node) {
        String entityName = node.get("entityName").asText();
        String fieldName = node.get("fieldName").asText();
        String categoryName = node.get("categoryName").asText();
        String tid = TenantContext.require();
        UUID fieldUuid = FieldAggregate.deriveId(entityName, fieldName);
        UUID categoryUuid = CategoryAggregate.deriveId(categoryName);

        activeDsl()
                .deleteFrom(FIELD_CATEGORY)
                .where(FIELD_CATEGORY.FIELD_UUID.eq(fieldUuid))
                .and(FIELD_CATEGORY.CATEGORY_UUID.eq(categoryUuid))
                .and(FIELD_CATEGORY.TENANT_ID.eq(tid))
                .execute();
    }

    // -----------------------------------------------------------------------
    // Purpose Aggregate
    // -----------------------------------------------------------------------

    private void handlePurposeRegistered(JsonNode node) {
        String purposeName = node.get("purposeName").asText();
        String description = textOrNull(node, "description");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String tid = TenantContext.require();
        UUID purposeUuid = PurposeAggregate.deriveId(purposeName);

        activeDsl()
                .insertInto(PURPOSE)
                .set(PURPOSE.PURPOSE_UUID, purposeUuid)
                .set(PURPOSE.TENANT_ID, tid)
                .set(PURPOSE.PURPOSE_NAME, purposeName)
                .set(PURPOSE.DESCRIPTION, description)
                .set(PURPOSE.CREATED_AT, now)
                .set(PURPOSE.UPDATED_AT, now)
                .onConflict(PURPOSE.TENANT_ID, PURPOSE.PURPOSE_NAME)
                .doUpdate()
                .set(PURPOSE.DESCRIPTION, description)
                .set(PURPOSE.UPDATED_AT, now)
                .execute();
    }

    private void handlePurposeUpdated(JsonNode node) {
        handlePurposeRegistered(node);
    }

    private void handlePurposeRemoved(JsonNode node) {
        String purposeName = node.get("purposeName").asText();
        String tid = TenantContext.require();
        UUID purposeUuid = PurposeAggregate.deriveId(purposeName);

        // FK order: consent_ledger → policy → purpose_regulation → purpose
        activeDsl()
                .deleteFrom(CONSENT_LEDGER)
                .where(CONSENT_LEDGER.TENANT_ID.eq(tid))
                .and(
                        CONSENT_LEDGER.POLICY_UUID.in(
                                activeDsl()
                                        .select(POLICY.POLICY_UUID)
                                        .from(POLICY)
                                        .where(POLICY.PURPOSE_UUID.eq(purposeUuid))
                                        .and(POLICY.TENANT_ID.eq(tid))))
                .execute();

        activeDsl()
                .deleteFrom(POLICY)
                .where(POLICY.PURPOSE_UUID.eq(purposeUuid))
                .and(POLICY.TENANT_ID.eq(tid))
                .execute();

        activeDsl()
                .deleteFrom(PURPOSE_REGULATION)
                .where(PURPOSE_REGULATION.PURPOSE_UUID.eq(purposeUuid))
                .and(PURPOSE_REGULATION.TENANT_ID.eq(tid))
                .execute();

        activeDsl()
                .deleteFrom(PURPOSE)
                .where(PURPOSE.PURPOSE_UUID.eq(purposeUuid))
                .and(PURPOSE.TENANT_ID.eq(tid))
                .execute();
    }

    private void handlePurposeRegulationLinked(JsonNode node) {
        String purposeName = node.get("purposeName").asText();
        String regulationName = node.get("regulationName").asText();
        String lawfulBasis = textOrNull(node, "lawfulBasis");
        String onGranted = textOrNull(node, "onGranted");
        String onDenied = textOrNull(node, "onDenied");
        // DB columns are NOT NULL — apply defaults if absent or unrecognized
        if (onGranted == null || MaskingType.lookupLiteral(onGranted) == null)
            onGranted = "PLAINTEXT";
        if (onDenied == null || MaskingType.lookupLiteral(onDenied) == null)
            onDenied = "REDACT_ALL";
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String tid = TenantContext.require();
        UUID purposeUuid = PurposeAggregate.deriveId(purposeName);
        UUID regulationUuid = RegulationAggregate.deriveId(regulationName);

        activeDsl()
                .insertInto(PURPOSE_REGULATION)
                .set(
                        PURPOSE_REGULATION.PURPOSE_REGULATION_UUID,
                        deriveUuid(
                                "consent-purpose-regulation:" + purposeUuid + ":" + regulationUuid))
                .set(PURPOSE_REGULATION.TENANT_ID, tid)
                .set(PURPOSE_REGULATION.PURPOSE_UUID, purposeUuid)
                .set(PURPOSE_REGULATION.REGULATION_UUID, regulationUuid)
                .set(PURPOSE_REGULATION.LAWFUL_BASIS, lawfulBasis)
                .set(PURPOSE_REGULATION.ON_GRANTED, MaskingType.lookupLiteral(onGranted))
                .set(PURPOSE_REGULATION.ON_DENIED, MaskingType.lookupLiteral(onDenied))
                .set(PURPOSE_REGULATION.CREATED_AT, now)
                .set(PURPOSE_REGULATION.UPDATED_AT, now)
                .onConflict(
                        PURPOSE_REGULATION.TENANT_ID,
                        PURPOSE_REGULATION.PURPOSE_UUID,
                        PURPOSE_REGULATION.REGULATION_UUID)
                .doUpdate()
                .set(PURPOSE_REGULATION.LAWFUL_BASIS, lawfulBasis)
                .set(PURPOSE_REGULATION.ON_GRANTED, MaskingType.lookupLiteral(onGranted))
                .set(PURPOSE_REGULATION.ON_DENIED, MaskingType.lookupLiteral(onDenied))
                .set(PURPOSE_REGULATION.UPDATED_AT, now)
                .execute();
    }

    private void handlePurposeRegulationUnlinked(JsonNode node) {
        String purposeName = node.get("purposeName").asText();
        String regulationName = node.get("regulationName").asText();
        String tid = TenantContext.require();
        UUID purposeUuid = PurposeAggregate.deriveId(purposeName);
        UUID regulationUuid = RegulationAggregate.deriveId(regulationName);

        activeDsl()
                .deleteFrom(PURPOSE_REGULATION)
                .where(PURPOSE_REGULATION.PURPOSE_UUID.eq(purposeUuid))
                .and(PURPOSE_REGULATION.REGULATION_UUID.eq(regulationUuid))
                .and(PURPOSE_REGULATION.TENANT_ID.eq(tid))
                .execute();
    }

    private void handlePurposePolicyAdded(JsonNode node) {
        String purposeName = node.get("purposeName").asText();
        String policyType = node.get("policyType").asText().toUpperCase();
        String fieldName = textOrNull(node, "fieldName");
        String entityName = textOrNull(node, "entityName");
        String categoryName = textOrNull(node, "categoryName");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String tid = TenantContext.require();
        UUID purposeUuid = PurposeAggregate.deriveId(purposeName);

        if ("FIELD".equals(policyType)) {
            UUID fieldUuid = FieldAggregate.deriveId(entityName, fieldName);
            activeDsl()
                    .insertInto(POLICY)
                    .set(
                            POLICY.POLICY_UUID,
                            deriveUuid(
                                    "consent-policy:"
                                            + purposeName
                                            + ":FIELD:"
                                            + entityName
                                            + ":"
                                            + fieldName))
                    .set(POLICY.TENANT_ID, tid)
                    .set(POLICY.PURPOSE_UUID, purposeUuid)
                    .set(POLICY.FIELD_UUID, fieldUuid)
                    .set(POLICY.POLICY_TYPE, PolicyType.lookupLiteral(policyType))
                    .set(POLICY.CREATED_AT, now)
                    .set(POLICY.UPDATED_AT, now)
                    .onConflict(POLICY.TENANT_ID, POLICY.PURPOSE_UUID, POLICY.FIELD_UUID)
                    .doUpdate()
                    .set(POLICY.UPDATED_AT, now)
                    .execute();
        } else if ("CATEGORY".equals(policyType)) {
            UUID categoryUuid = CategoryAggregate.deriveId(categoryName);
            activeDsl()
                    .insertInto(POLICY)
                    .set(
                            POLICY.POLICY_UUID,
                            deriveUuid(
                                    "consent-policy:" + purposeName + ":CATEGORY:" + categoryName))
                    .set(POLICY.TENANT_ID, tid)
                    .set(POLICY.PURPOSE_UUID, purposeUuid)
                    .set(POLICY.CATEGORY_UUID, categoryUuid)
                    .set(POLICY.POLICY_TYPE, PolicyType.lookupLiteral(policyType))
                    .set(POLICY.CREATED_AT, now)
                    .set(POLICY.UPDATED_AT, now)
                    .onConflict(POLICY.TENANT_ID, POLICY.PURPOSE_UUID, POLICY.CATEGORY_UUID)
                    .doUpdate()
                    .set(POLICY.UPDATED_AT, now)
                    .execute();
        } else {
            log.warn(
                    "Unknown policyType '{}' for purpose '{}' — skipping", policyType, purposeName);
        }
    }

    private void handlePurposePolicyRemoved(JsonNode node) {
        String purposeName = node.get("purposeName").asText();
        String policyType = node.get("policyType").asText().toUpperCase();
        String fieldName = textOrNull(node, "fieldName");
        String entityName = textOrNull(node, "entityName");
        String categoryName = textOrNull(node, "categoryName");
        String tid = TenantContext.require();
        UUID purposeUuid = PurposeAggregate.deriveId(purposeName);

        if ("FIELD".equals(policyType)) {
            UUID fieldUuid = FieldAggregate.deriveId(entityName, fieldName);

            // Delete consent_ledger entries referencing this policy first
            activeDsl()
                    .deleteFrom(CONSENT_LEDGER)
                    .where(CONSENT_LEDGER.TENANT_ID.eq(tid))
                    .and(
                            CONSENT_LEDGER.POLICY_UUID.in(
                                    activeDsl()
                                            .select(POLICY.POLICY_UUID)
                                            .from(POLICY)
                                            .where(POLICY.PURPOSE_UUID.eq(purposeUuid))
                                            .and(POLICY.FIELD_UUID.eq(fieldUuid))
                                            .and(POLICY.TENANT_ID.eq(tid))))
                    .execute();

            activeDsl()
                    .deleteFrom(POLICY)
                    .where(POLICY.PURPOSE_UUID.eq(purposeUuid))
                    .and(POLICY.FIELD_UUID.eq(fieldUuid))
                    .and(POLICY.TENANT_ID.eq(tid))
                    .execute();
        } else if ("CATEGORY".equals(policyType)) {
            UUID categoryUuid = CategoryAggregate.deriveId(categoryName);

            activeDsl()
                    .deleteFrom(CONSENT_LEDGER)
                    .where(CONSENT_LEDGER.TENANT_ID.eq(tid))
                    .and(
                            CONSENT_LEDGER.POLICY_UUID.in(
                                    activeDsl()
                                            .select(POLICY.POLICY_UUID)
                                            .from(POLICY)
                                            .where(POLICY.PURPOSE_UUID.eq(purposeUuid))
                                            .and(POLICY.CATEGORY_UUID.eq(categoryUuid))
                                            .and(POLICY.TENANT_ID.eq(tid))))
                    .execute();

            activeDsl()
                    .deleteFrom(POLICY)
                    .where(POLICY.PURPOSE_UUID.eq(purposeUuid))
                    .and(POLICY.CATEGORY_UUID.eq(categoryUuid))
                    .and(POLICY.TENANT_ID.eq(tid))
                    .execute();
        }
    }

    // -----------------------------------------------------------------------
    // Regulation Aggregate
    // -----------------------------------------------------------------------

    private void handleRegulationRegistered(JsonNode node) {
        String regulationName = node.get("regulationName").asText();
        String description = textOrNull(node, "description");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String tid = TenantContext.require();
        UUID regulationUuid = RegulationAggregate.deriveId(regulationName);

        activeDsl()
                .insertInto(REGULATION)
                .set(REGULATION.REGULATION_UUID, regulationUuid)
                .set(REGULATION.TENANT_ID, tid)
                .set(REGULATION.REGULATION_NAME, regulationName)
                .set(REGULATION.DESCRIPTION, description)
                .set(REGULATION.CREATED_AT, now)
                .set(REGULATION.UPDATED_AT, now)
                .onConflict(REGULATION.TENANT_ID, REGULATION.REGULATION_NAME)
                .doUpdate()
                .set(REGULATION.DESCRIPTION, description)
                .set(REGULATION.UPDATED_AT, now)
                .execute();
    }

    private void handleRegulationUpdated(JsonNode node) {
        handleRegulationRegistered(node);
    }

    private void handleRegulationRemoved(JsonNode node) {
        String regulationName = node.get("regulationName").asText();
        String tid = TenantContext.require();
        UUID regulationUuid = RegulationAggregate.deriveId(regulationName);

        activeDsl()
                .deleteFrom(REGULATION_JURISDICTION)
                .where(REGULATION_JURISDICTION.REGULATION_UUID.eq(regulationUuid))
                .and(REGULATION_JURISDICTION.TENANT_ID.eq(tid))
                .execute();

        activeDsl()
                .deleteFrom(PURPOSE_REGULATION)
                .where(PURPOSE_REGULATION.REGULATION_UUID.eq(regulationUuid))
                .and(PURPOSE_REGULATION.TENANT_ID.eq(tid))
                .execute();

        activeDsl()
                .deleteFrom(REGULATION)
                .where(REGULATION.REGULATION_UUID.eq(regulationUuid))
                .and(REGULATION.TENANT_ID.eq(tid))
                .execute();
    }

    private void handleRegulationJurisdictionAdded(JsonNode node) {
        String regulationName = node.get("regulationName").asText();
        String jurisdictionName = node.get("jurisdictionName").asText();
        String description = textOrNull(node, "description");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String tid = TenantContext.require();
        UUID regulationUuid = RegulationAggregate.deriveId(regulationName);
        UUID jurisdictionUuid = JurisdictionAggregate.deriveId(jurisdictionName);

        // Upsert jurisdiction
        activeDsl()
                .insertInto(JURISDICTION)
                .set(JURISDICTION.JURISDICTION_UUID, jurisdictionUuid)
                .set(JURISDICTION.TENANT_ID, tid)
                .set(JURISDICTION.JURISDICTION_NAME, jurisdictionName)
                .set(JURISDICTION.DESCRIPTION, description)
                .set(JURISDICTION.CREATED_AT, now)
                .set(JURISDICTION.UPDATED_AT, now)
                .onConflict(JURISDICTION.TENANT_ID, JURISDICTION.JURISDICTION_NAME)
                .doUpdate()
                .set(JURISDICTION.UPDATED_AT, now)
                .execute();

        // Link regulation ↔ jurisdiction with direct UUID FKs
        activeDsl()
                .insertInto(REGULATION_JURISDICTION)
                .set(
                        REGULATION_JURISDICTION.REGULATION_JURISDICTION_UUID,
                        deriveUuid(
                                "consent-regulation-jurisdiction:"
                                        + regulationUuid
                                        + ":"
                                        + jurisdictionUuid))
                .set(REGULATION_JURISDICTION.TENANT_ID, tid)
                .set(REGULATION_JURISDICTION.REGULATION_UUID, regulationUuid)
                .set(REGULATION_JURISDICTION.JURISDICTION_UUID, jurisdictionUuid)
                .set(REGULATION_JURISDICTION.CREATED_AT, now)
                .set(REGULATION_JURISDICTION.UPDATED_AT, now)
                .onConflict(
                        REGULATION_JURISDICTION.TENANT_ID,
                        REGULATION_JURISDICTION.REGULATION_UUID,
                        REGULATION_JURISDICTION.JURISDICTION_UUID)
                .doNothing()
                .execute();
    }

    private void handleRegulationJurisdictionRemoved(JsonNode node) {
        String regulationName = node.get("regulationName").asText();
        String jurisdictionName = node.get("jurisdictionName").asText();
        String tid = TenantContext.require();
        UUID regulationUuid = RegulationAggregate.deriveId(regulationName);
        UUID jurisdictionUuid = JurisdictionAggregate.deriveId(jurisdictionName);

        activeDsl()
                .deleteFrom(REGULATION_JURISDICTION)
                .where(REGULATION_JURISDICTION.REGULATION_UUID.eq(regulationUuid))
                .and(REGULATION_JURISDICTION.JURISDICTION_UUID.eq(jurisdictionUuid))
                .and(REGULATION_JURISDICTION.TENANT_ID.eq(tid))
                .execute();
    }

    // -----------------------------------------------------------------------
    // Subject Aggregate (entity lifecycle)
    // -----------------------------------------------------------------------

    private void handleSubjectRegistered(JsonNode node) {
        String subjectName = node.get("subjectName").asText();
        String description = textOrNull(node, "description");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String tid = TenantContext.require();
        UUID subjectUuid = SubjectAggregate.deriveId(subjectName);

        activeDsl()
                .insertInto(SUBJECT)
                .set(SUBJECT.SUBJECT_UUID, subjectUuid)
                .set(SUBJECT.TENANT_ID, tid)
                .set(SUBJECT.SUBJECT_NAME, subjectName)
                .set(SUBJECT.DESCRIPTION, description)
                .set(SUBJECT.CREATED_AT, now)
                .set(SUBJECT.UPDATED_AT, now)
                .onConflict(SUBJECT.TENANT_ID, SUBJECT.SUBJECT_NAME)
                .doUpdate()
                .set(SUBJECT.DESCRIPTION, description)
                .set(SUBJECT.UPDATED_AT, now)
                .execute();
    }

    private void handleSubjectUpdated(JsonNode node) {
        String subjectName = node.get("subjectName").asText();
        String description = textOrNull(node, "description");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        activeDsl()
                .update(SUBJECT)
                .set(SUBJECT.DESCRIPTION, description)
                .set(SUBJECT.UPDATED_AT, now)
                .where(SUBJECT.SUBJECT_NAME.eq(subjectName))
                .and(SUBJECT.TENANT_ID.eq(TenantContext.require()))
                .execute();
    }

    private void handleSubjectRemoved(JsonNode node) {
        String subjectName = node.get("subjectName").asText();
        String tid = TenantContext.require();

        // Delete consent_ledger entries first (FK references subject.subject_name)
        activeDsl()
                .deleteFrom(CONSENT_LEDGER)
                .where(
                        CONSENT_LEDGER
                                .SUBJECT_ID
                                .eq(subjectName)
                                .and(CONSENT_LEDGER.TENANT_ID.eq(tid)))
                .execute();
        activeDsl()
                .deleteFrom(SUBJECT)
                .where(SUBJECT.SUBJECT_NAME.eq(subjectName).and(SUBJECT.TENANT_ID.eq(tid)))
                .execute();
    }

    // -----------------------------------------------------------------------
    // SubjectConsent Aggregate (consent ledger — append-only historical record)
    // -----------------------------------------------------------------------

    private void handleConsentGranted(JsonNode node) {
        insertConsentLedger(node, "GRANT");
    }

    private void handleConsentRevoked(JsonNode node) {
        insertConsentLedger(node, "REVOKE");
    }

    private void handleConsentExpired(JsonNode node) {
        insertConsentLedger(node, "EXPIRE");
    }

    /**
     * Inserts a consent ledger entry. The event carries the policy UUID directly, so we use a
     * straight INSERT rather than an INSERT…SELECT lookup.
     */
    private void insertConsentLedger(JsonNode node, String action) {
        String subjectId = node.get("subjectId").asText();
        String policyIdText = textOrNull(node, "policyId");
        String actor = textOrNull(node, "actor");
        String ipAddress = textOrNull(node, "ipAddress");
        String userAgent = textOrNull(node, "userAgent");
        LocalDateTime expiresAt = instantToLocal(node, "expiresAt");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        ConsentAction consentAction = ConsentAction.lookupLiteral(action);
        String tid = TenantContext.require();

        if (policyIdText == null) {
            log.warn(
                    "Consent {} event for subject '{}' has no policyId — skipping",
                    action,
                    subjectId);
            return;
        }
        UUID policyUuid = UUID.fromString(policyIdText);

        // Ensure the subject row exists (consent events may arrive before the
        // SUBJECT_REGISTERED projection when both are published in quick succession).
        UUID subjectUuid = SubjectAggregate.deriveId(subjectId);
        activeDsl()
                .insertInto(SUBJECT)
                .set(SUBJECT.SUBJECT_UUID, subjectUuid)
                .set(SUBJECT.TENANT_ID, tid)
                .set(SUBJECT.SUBJECT_NAME, subjectId)
                .set(SUBJECT.CREATED_AT, now)
                .set(SUBJECT.UPDATED_AT, now)
                .onConflict(SUBJECT.TENANT_ID, SUBJECT.SUBJECT_NAME)
                .doNothing()
                .execute();

        activeDsl()
                .insertInto(CONSENT_LEDGER)
                .set(
                        CONSENT_LEDGER.CONSENT_LEDGER_UUID,
                        deriveUuid("consent-ledger:" + eventMessageId()))
                .set(CONSENT_LEDGER.TENANT_ID, tid)
                .set(CONSENT_LEDGER.POLICY_UUID, policyUuid)
                .set(CONSENT_LEDGER.SUBJECT_ID, subjectId)
                .set(CONSENT_LEDGER.ACTION, consentAction)
                .set(CONSENT_LEDGER.ACTOR, actor)
                .set(CONSENT_LEDGER.IP_ADDRESS, ipAddress)
                .set(CONSENT_LEDGER.USER_AGENT, userAgent)
                .set(CONSENT_LEDGER.CREATED_AT, now)
                .set(CONSENT_LEDGER.EXPIRES_AT, expiresAt)
                .onConflict(CONSENT_LEDGER.CONSENT_LEDGER_UUID)
                .doNothing()
                .execute();
    }

    private void handleConsentAllRevoked(JsonNode node) {
        String subjectId = node.get("subjectId").asText();
        String actor = textOrNull(node, "actor");
        String ipAddress = textOrNull(node, "ipAddress");
        String userAgent = textOrNull(node, "userAgent");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String tid = TenantContext.require();

        // Find currently-granted policies via latest entry per subject+policy
        org.jooq.Field<Integer> rn =
                org.jooq
                        .impl
                        .DSL
                        .rowNumber()
                        .over(
                                org.jooq
                                        .impl
                                        .DSL
                                        .partitionBy(CONSENT_LEDGER.POLICY_UUID)
                                        .orderBy(CONSENT_LEDGER.CREATED_AT.desc()))
                        .as("rn");

        var ranked =
                activeDsl()
                        .select(CONSENT_LEDGER.POLICY_UUID, CONSENT_LEDGER.ACTION, rn)
                        .from(CONSENT_LEDGER)
                        .where(CONSENT_LEDGER.SUBJECT_ID.eq(subjectId))
                        .and(CONSENT_LEDGER.TENANT_ID.eq(tid))
                        .asTable("ranked");

        var latestGranted =
                activeDsl()
                        .select(ranked.field(CONSENT_LEDGER.POLICY_UUID))
                        .from(ranked)
                        .where(ranked.field("rn", Integer.class).eq(1))
                        .and(ranked.field(CONSENT_LEDGER.ACTION).eq(ConsentAction.GRANT))
                        .fetchInto(UUID.class);

        // Fan out one REVOKE row per active grant
        for (UUID policyUuid : latestGranted) {
            activeDsl()
                    .insertInto(CONSENT_LEDGER)
                    .set(
                            CONSENT_LEDGER.CONSENT_LEDGER_UUID,
                            deriveUuid("consent-ledger:" + eventMessageId() + ":" + policyUuid))
                    .set(CONSENT_LEDGER.TENANT_ID, tid)
                    .set(CONSENT_LEDGER.POLICY_UUID, policyUuid)
                    .set(CONSENT_LEDGER.SUBJECT_ID, subjectId)
                    .set(CONSENT_LEDGER.ACTION, ConsentAction.REVOKE)
                    .set(CONSENT_LEDGER.ACTOR, actor)
                    .set(CONSENT_LEDGER.IP_ADDRESS, ipAddress)
                    .set(CONSENT_LEDGER.USER_AGENT, userAgent)
                    .set(CONSENT_LEDGER.CREATED_AT, now)
                    .set(CONSENT_LEDGER.EXPIRES_AT, (LocalDateTime) null)
                    .onConflict(CONSENT_LEDGER.CONSENT_LEDGER_UUID)
                    .doNothing()
                    .execute();
        }
    }

    // -----------------------------------------------------------------------
    // DSAR Aggregate
    // -----------------------------------------------------------------------

    private void handleSubjectAuditDeleted(JsonNode node) {
        String subjectId = node.get("subjectId").asText();
        String deletionRequestId = node.get("deletionRequestId").asText();
        String actor = textOrNull(node, "actor");
        String ipAddress = textOrNull(node, "ipAddress");
        String userAgent = textOrNull(node, "userAgent");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String tid = TenantContext.require();

        // Count what will be deleted
        int ledgerCount =
                activeDsl()
                        .selectCount()
                        .from(CONSENT_LEDGER)
                        .where(CONSENT_LEDGER.SUBJECT_ID.eq(subjectId))
                        .and(CONSENT_LEDGER.TENANT_ID.eq(tid))
                        .fetchOneInto(int.class);

        // Delete consent ledger entries for this subject
        activeDsl()
                .deleteFrom(CONSENT_LEDGER)
                .where(
                        CONSENT_LEDGER
                                .SUBJECT_ID
                                .eq(subjectId)
                                .and(CONSENT_LEDGER.TENANT_ID.eq(tid)))
                .execute();

        // Record the DSAR action
        activeDsl()
                .insertInto(DSAR_AUDIT_LOG)
                .set(DSAR_AUDIT_LOG.DSAR_AUDIT_UUID, deriveUuid("consent-dsar:" + eventMessageId()))
                .set(DSAR_AUDIT_LOG.TENANT_ID, tid)
                .set(DSAR_AUDIT_LOG.SUBJECT_ID, subjectId)
                .set(DSAR_AUDIT_LOG.DELETION_REQUEST_ID, deletionRequestId)
                .set(DSAR_AUDIT_LOG.POLICIES_DELETED, ledgerCount)
                .set(DSAR_AUDIT_LOG.STATUS, "SUCCESS")
                .set(DSAR_AUDIT_LOG.ACTOR, actor)
                .set(DSAR_AUDIT_LOG.IP_ADDRESS, ipAddress)
                .set(DSAR_AUDIT_LOG.USER_AGENT, userAgent)
                .set(DSAR_AUDIT_LOG.CREATED_AT, now)
                .onConflict(DSAR_AUDIT_LOG.DSAR_AUDIT_UUID)
                .doNothing()
                .execute();
    }

    // -----------------------------------------------------------------------
    // JSON Helpers
    // -----------------------------------------------------------------------

    private static String textOrNull(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return (child != null && !child.isNull()) ? child.asText() : null;
    }

    private static LocalDateTime instantToLocal(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child == null || child.isNull()) return null;
        Instant instant;
        if (child.isNumber()) {
            // JavaTimeModule default: epoch seconds as numeric
            long epochSecond = child.longValue();
            int nano =
                    (int)
                            ((child.decimalValue()
                                            .remainder(java.math.BigDecimal.ONE)
                                            .movePointRight(9))
                                    .intValue());
            instant = Instant.ofEpochSecond(epochSecond, nano);
        } else {
            instant = Instant.parse(child.asText());
        }
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
