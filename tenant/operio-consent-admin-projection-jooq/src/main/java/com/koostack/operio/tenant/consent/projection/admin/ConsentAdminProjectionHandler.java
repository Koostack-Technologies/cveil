package com.koostack.operio.tenant.consent.projection.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.koostack.operio.core.tenant.consent.ConsentEventTypes;
import com.koostack.operio.core.event.models.TenantEvent;
import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.tenant.event.jooq.StreamEventHandler;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import static com.koostack.operio.plugin.consent.jooq.generated.Tables.*;

/**
 * Stream projection handler that materializes tenant consent domain events
 * into the consent admin relational schema.
 *
 * <p>Switches on {@code eventType} and executes idempotent SQL statements
 * (upsert via {@code ON CONFLICT DO UPDATE}, or {@code DELETE}) against the
 * consent admin tables: entity, field, category, field_category,
 * consent_policy, purpose, regulation, purpose_regulation, and
 * consent_audit_log.
 *
 * <p>All writes are routed to the correct per-tenant schema via
 * {@link TenantContext}, which is set from the event's {@code tenantId}
 * field before each dispatch.
 *
 * <h2>Thread Safety</h2>
 * <p>This class is safe for concurrent use — all state is final and the
 * underlying jOOQ {@link DSLContext} is thread-safe. {@link TenantContext}
 * is a {@code ScopedValue}, providing per-thread isolation.
 */
public class ConsentAdminProjectionHandler implements StreamEventHandler {

    private static final Logger log = LoggerFactory.getLogger(ConsentAdminProjectionHandler.class);

    private static final ObjectMapper objectMapper = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    private final DSLContext dsl;

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

        TenantContext.runWhere(tenantId, () -> {
            try {
                JsonNode node = payload != null ? objectMapper.readTree(payload) : objectMapper.createObjectNode();
                dispatch(eventType, node);
                log.debug("Projected event {} [{}] for tenant {}", messageId, eventType, tenantId);
            } catch (Exception e) {
                log.error("Failed to project event {} [{}] for tenant {}", messageId, eventType, tenantId, e);
                throw new RuntimeException(e);
            }
        });
    }

    private void dispatch(String eventType, JsonNode node) {
        switch (eventType) {
            // -- Entity / Field --
            case ConsentEventTypes.ENTITY_FIELD_REGISTERED -> handleEntityFieldRegistered(node);
            case ConsentEventTypes.ENTITY_DELETED -> handleEntityDeleted(node);
            case ConsentEventTypes.ENTITY_FIELD_REMOVED -> handleEntityFieldRemoved(node);

            // -- Field ↔ Category links --
            case ConsentEventTypes.ENTITY_FIELD_CATEGORY_LINKED -> handleFieldCategoryLinked(node);
            case ConsentEventTypes.ENTITY_FIELD_CATEGORY_UNLINKED -> handleFieldCategoryUnlinked(node);

            // -- Category --
            case ConsentEventTypes.CATEGORY_REGISTERED -> handleCategoryRegistered(node);
            case ConsentEventTypes.CATEGORY_DELETED -> handleCategoryDeleted(node);

            // -- Purpose --
            case ConsentEventTypes.PURPOSE_REGISTERED -> handlePurposeRegistered(node);
            case ConsentEventTypes.PURPOSE_DELETED -> handlePurposeDeleted(node);

            // -- Purpose ↔ Regulation links --
            case ConsentEventTypes.PURPOSE_REGULATION_LINKED -> handlePurposeRegulationLinked(node);
            case ConsentEventTypes.PURPOSE_REGULATION_UNLINKED -> handlePurposeRegulationUnlinked(node);

            // -- Regulation --
            case ConsentEventTypes.REGULATION_REGISTERED -> handleRegulationRegistered(node);
            case ConsentEventTypes.REGULATION_DELETED -> handleRegulationDeleted(node);

            // -- Consent Policy --
            case ConsentEventTypes.POLICY_GRANTED -> handlePolicyGranted(node);
            case ConsentEventTypes.POLICY_REVOKED -> handlePolicyRevoked(node);
            case ConsentEventTypes.POLICY_ALL_REVOKED -> handlePolicyAllRevoked(node);
            case ConsentEventTypes.POLICY_AUDIT_DELETED -> handlePolicyAuditDeleted(node);

            default -> log.debug("Ignoring unhandled event type: {}", eventType);
        }
    }

    // -----------------------------------------------------------------------
    // Entity / Field
    // -----------------------------------------------------------------------

    private void handleEntityFieldRegistered(JsonNode node) {
        String entityName = node.get("entityName").asText();
        String fieldName = node.get("fieldName").asText();
        String description = textOrNull(node, "description");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        // Upsert entity
        dsl.insertInto(ENTITY)
            .set(ENTITY.ENTITY_NAME, entityName)
            .set(ENTITY.CREATED_AT, now)
            .set(ENTITY.UPDATED_AT, now)
            .onConflict(ENTITY.ENTITY_NAME)
            .doUpdate()
            .set(ENTITY.UPDATED_AT, now)
            .execute();

        // Upsert field (resolve entity_id via subquery)
        Long entityId = dsl.select(ENTITY.ENTITY_ID)
            .from(ENTITY)
            .where(ENTITY.ENTITY_NAME.eq(entityName))
            .fetchOneInto(Long.class);

        if (entityId == null) {
            log.warn("Entity '{}' not found after upsert — skipping field registration", entityName);
            return;
        }

        dsl.insertInto(FIELD)
            .set(FIELD.FIELD_NAME, fieldName)
            .set(FIELD.ENTITY_ID, entityId)
            .set(FIELD.DESCRIPTION, description)
            .set(FIELD.CREATED_AT, now)
            .set(FIELD.UPDATED_AT, now)
            .onConflict(FIELD.FIELD_NAME, FIELD.ENTITY_ID)
            .doUpdate()
            .set(FIELD.DESCRIPTION, description)
            .set(FIELD.UPDATED_AT, now)
            .execute();
    }

    private void handleEntityDeleted(JsonNode node) {
        String entityName = node.get("entityName").asText();

        Long entityId = dsl.select(ENTITY.ENTITY_ID)
            .from(ENTITY)
            .where(ENTITY.ENTITY_NAME.eq(entityName))
            .fetchOneInto(Long.class);

        if (entityId != null) {
            var fieldIds = dsl.select(FIELD.FIELD_ID).from(FIELD).where(FIELD.ENTITY_ID.eq(entityId));

            // FK order: audit_log → consent_policy → field_category → field → entity
            dsl.deleteFrom(CONSENT_AUDIT_LOG)
                .where(CONSENT_AUDIT_LOG.FIELD_ID.in(fieldIds))
                .execute();

            dsl.deleteFrom(CONSENT_POLICY)
                .where(CONSENT_POLICY.FIELD_ID.in(fieldIds))
                .execute();

            dsl.deleteFrom(FIELD_CATEGORY)
                .where(FIELD_CATEGORY.FIELD_ID.in(fieldIds))
                .execute();

            dsl.deleteFrom(FIELD)
                .where(FIELD.ENTITY_ID.eq(entityId))
                .execute();
        }

        dsl.deleteFrom(ENTITY)
            .where(ENTITY.ENTITY_NAME.eq(entityName))
            .execute();
    }

    private void handleEntityFieldRemoved(JsonNode node) {
        String entityName = node.get("entityName").asText();
        String fieldName = node.get("fieldName").asText();

        Long entityId = dsl.select(ENTITY.ENTITY_ID)
            .from(ENTITY)
            .where(ENTITY.ENTITY_NAME.eq(entityName))
            .fetchOneInto(Long.class);

        if (entityId != null) {
            Long fieldId = dsl.select(FIELD.FIELD_ID)
                .from(FIELD)
                .where(FIELD.FIELD_NAME.eq(fieldName))
                .and(FIELD.ENTITY_ID.eq(entityId))
                .fetchOneInto(Long.class);

            if (fieldId != null) {
                // FK order: audit_log → consent_policy → field_category → field
                dsl.deleteFrom(CONSENT_AUDIT_LOG)
                    .where(CONSENT_AUDIT_LOG.FIELD_ID.eq(fieldId))
                    .execute();

                dsl.deleteFrom(CONSENT_POLICY)
                    .where(CONSENT_POLICY.FIELD_ID.eq(fieldId))
                    .execute();

                dsl.deleteFrom(FIELD_CATEGORY)
                    .where(FIELD_CATEGORY.FIELD_ID.eq(fieldId))
                    .execute();

                dsl.deleteFrom(FIELD)
                    .where(FIELD.FIELD_ID.eq(fieldId))
                    .execute();
            }
        }
    }

    // -----------------------------------------------------------------------
    // Field ↔ Category links
    // -----------------------------------------------------------------------

    private void handleFieldCategoryLinked(JsonNode node) {
        String entityName = node.get("entityName").asText();
        String fieldName = node.get("fieldName").asText();
        String categoryName = node.get("categoryName").asText();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        Long fieldId = resolveFieldId(entityName, fieldName);
        Long categoryId = resolveCategoryId(categoryName);

        if (fieldId == null || categoryId == null) {
            log.warn("Cannot link field '{}/{}' to category '{}' — field or category not found",
                entityName, fieldName, categoryName);
            return;
        }

        dsl.insertInto(FIELD_CATEGORY)
            .set(FIELD_CATEGORY.FIELD_ID, fieldId)
            .set(FIELD_CATEGORY.CATEGORY_ID, categoryId)
            .set(FIELD_CATEGORY.CREATED_AT, now)
            .set(FIELD_CATEGORY.UPDATED_AT, now)
            .onConflict(FIELD_CATEGORY.FIELD_ID, FIELD_CATEGORY.CATEGORY_ID)
            .doNothing()
            .execute();
    }

    private void handleFieldCategoryUnlinked(JsonNode node) {
        String entityName = node.get("entityName").asText();
        String fieldName = node.get("fieldName").asText();
        String categoryName = node.get("categoryName").asText();

        Long fieldId = resolveFieldId(entityName, fieldName);
        Long categoryId = resolveCategoryId(categoryName);

        if (fieldId != null && categoryId != null) {
            dsl.deleteFrom(FIELD_CATEGORY)
                .where(FIELD_CATEGORY.FIELD_ID.eq(fieldId))
                .and(FIELD_CATEGORY.CATEGORY_ID.eq(categoryId))
                .execute();
        }
    }

    // -----------------------------------------------------------------------
    // Category
    // -----------------------------------------------------------------------

    private void handleCategoryRegistered(JsonNode node) {
        String categoryName = node.get("categoryName").asText();
        String description = textOrNull(node, "description");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        dsl.insertInto(CATEGORY)
            .set(CATEGORY.CATEGORY_NAME, categoryName)
            .set(CATEGORY.DESCRIPTION, description)
            .set(CATEGORY.CREATED_AT, now)
            .set(CATEGORY.UPDATED_AT, now)
            .onConflict(CATEGORY.CATEGORY_NAME)
            .doUpdate()
            .set(CATEGORY.DESCRIPTION, description)
            .set(CATEGORY.UPDATED_AT, now)
            .execute();
    }

    private void handleCategoryDeleted(JsonNode node) {
        String categoryName = node.get("categoryName").asText();

        Long categoryId = resolveCategoryId(categoryName);
        if (categoryId != null) {
            // FK order: audit_log → consent_policy → field_category → category
            dsl.deleteFrom(CONSENT_AUDIT_LOG)
                .where(CONSENT_AUDIT_LOG.CATEGORY_ID.eq(categoryId))
                .execute();

            dsl.deleteFrom(CONSENT_POLICY)
                .where(CONSENT_POLICY.CATEGORY_ID.eq(categoryId))
                .execute();

            dsl.deleteFrom(FIELD_CATEGORY)
                .where(FIELD_CATEGORY.CATEGORY_ID.eq(categoryId))
                .execute();
        }

        dsl.deleteFrom(CATEGORY)
            .where(CATEGORY.CATEGORY_NAME.eq(categoryName))
            .execute();
    }

    // -----------------------------------------------------------------------
    // Purpose
    // -----------------------------------------------------------------------

    private void handlePurposeRegistered(JsonNode node) {
        String purposeName = node.get("purposeName").asText();
        String description = textOrNull(node, "description");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        dsl.insertInto(PURPOSE)
            .set(PURPOSE.PURPOSE_NAME, purposeName)
            .set(PURPOSE.DESCRIPTION, description)
            .set(PURPOSE.CREATED_AT, now)
            .set(PURPOSE.UPDATED_AT, now)
            .onConflict(PURPOSE.PURPOSE_NAME)
            .doUpdate()
            .set(PURPOSE.DESCRIPTION, description)
            .set(PURPOSE.UPDATED_AT, now)
            .execute();
    }

    private void handlePurposeDeleted(JsonNode node) {
        String purposeName = node.get("purposeName").asText();

        Long purposeId = resolvePurposeId(purposeName);
        if (purposeId != null) {
            // FK order: audit_log → consent_policy → purpose_regulation → purpose
            dsl.deleteFrom(CONSENT_AUDIT_LOG)
                .where(CONSENT_AUDIT_LOG.PURPOSE_ID.eq(purposeId))
                .execute();

            dsl.deleteFrom(CONSENT_POLICY)
                .where(CONSENT_POLICY.PURPOSE_ID.eq(purposeId))
                .execute();

            dsl.deleteFrom(PURPOSE_REGULATION)
                .where(PURPOSE_REGULATION.PURPOSE_ID.eq(purposeId))
                .execute();
        }

        dsl.deleteFrom(PURPOSE)
            .where(PURPOSE.PURPOSE_NAME.eq(purposeName))
            .execute();
    }

    // -----------------------------------------------------------------------
    // Purpose ↔ Regulation links
    // -----------------------------------------------------------------------

    private void handlePurposeRegulationLinked(JsonNode node) {
        String purposeName = node.get("purposeName").asText();
        String regulationName = node.get("regulationName").asText();
        String legalBasis = textOrNull(node, "legalBasis");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        Long purposeId = resolvePurposeId(purposeName);
        Long regulationId = resolveRegulationId(regulationName);

        if (purposeId == null || regulationId == null) {
            log.warn("Cannot link purpose '{}' to regulation '{}' — not found",
                purposeName, regulationName);
            return;
        }

        dsl.insertInto(PURPOSE_REGULATION)
            .set(PURPOSE_REGULATION.PURPOSE_ID, purposeId)
            .set(PURPOSE_REGULATION.REGULATION_ID, regulationId)
            .set(PURPOSE_REGULATION.LEGAL_BASIS, legalBasis)
            .set(PURPOSE_REGULATION.CREATED_AT, now)
            .set(PURPOSE_REGULATION.UPDATED_AT, now)
            .onConflict(PURPOSE_REGULATION.PURPOSE_ID, PURPOSE_REGULATION.REGULATION_ID)
            .doUpdate()
            .set(PURPOSE_REGULATION.LEGAL_BASIS, legalBasis)
            .set(PURPOSE_REGULATION.UPDATED_AT, now)
            .execute();
    }

    private void handlePurposeRegulationUnlinked(JsonNode node) {
        String purposeName = node.get("purposeName").asText();
        String regulationName = node.get("regulationName").asText();

        Long purposeId = resolvePurposeId(purposeName);
        Long regulationId = resolveRegulationId(regulationName);

        if (purposeId != null && regulationId != null) {
            dsl.deleteFrom(PURPOSE_REGULATION)
                .where(PURPOSE_REGULATION.PURPOSE_ID.eq(purposeId))
                .and(PURPOSE_REGULATION.REGULATION_ID.eq(regulationId))
                .execute();
        }
    }

    // -----------------------------------------------------------------------
    // Regulation
    // -----------------------------------------------------------------------

    private void handleRegulationRegistered(JsonNode node) {
        String regulationName = node.get("regulationName").asText();
        String fullName = textOrNull(node, "fullName");
        String jurisdiction = textOrNull(node, "jurisdiction");
        String description = textOrNull(node, "description");
        String url = textOrNull(node, "url");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        dsl.insertInto(REGULATION)
            .set(REGULATION.REGULATION_NAME, regulationName)
            .set(REGULATION.FULL_NAME, fullName)
            .set(REGULATION.JURISDICTION, jurisdiction)
            .set(REGULATION.DESCRIPTION, description)
            .set(REGULATION.URL, url)
            .set(REGULATION.CREATED_AT, now)
            .set(REGULATION.UPDATED_AT, now)
            .onConflict(REGULATION.REGULATION_NAME)
            .doUpdate()
            .set(REGULATION.FULL_NAME, fullName)
            .set(REGULATION.JURISDICTION, jurisdiction)
            .set(REGULATION.DESCRIPTION, description)
            .set(REGULATION.URL, url)
            .set(REGULATION.UPDATED_AT, now)
            .execute();
    }

    private void handleRegulationDeleted(JsonNode node) {
        String regulationName = node.get("regulationName").asText();

        Long regulationId = resolveRegulationId(regulationName);
        if (regulationId != null) {
            dsl.deleteFrom(PURPOSE_REGULATION)
                .where(PURPOSE_REGULATION.REGULATION_ID.eq(regulationId))
                .execute();
        }

        dsl.deleteFrom(REGULATION)
            .where(REGULATION.REGULATION_NAME.eq(regulationName))
            .execute();
    }

    // -----------------------------------------------------------------------
    // Consent Policy
    // -----------------------------------------------------------------------

    private void handlePolicyGranted(JsonNode node) {
        String subjectId = node.get("subjectId").asText();
        String purposeName = node.get("purposeName").asText();
        String fieldName = textOrNull(node, "fieldName");
        String entityName = textOrNull(node, "entityName");
        String category = textOrNull(node, "category");
        boolean granted = node.get("granted").asBoolean();
        LocalDateTime expiresAt = instantToLocal(node, "expiresAt");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        Long purposeId = resolvePurposeId(purposeName);
        if (purposeId == null) {
            log.warn("Purpose '{}' not found — cannot project policy grant for subject '{}'",
                purposeName, subjectId);
            return;
        }

        Long fieldId = null;
        Long categoryId = null;

        if (fieldName != null) {
            fieldId = resolveFieldId(entityName, fieldName);
            if (fieldId == null) {
                log.warn("Field '{}/{}' not found — cannot project policy grant", entityName, fieldName);
                return;
            }
            upsertFieldPolicy(subjectId, fieldId, purposeId, granted, expiresAt, now);
        } else if (category != null) {
            categoryId = resolveCategoryId(category);
            if (categoryId == null) {
                log.warn("Category '{}' not found — cannot project policy grant", category);
                return;
            }
            upsertCategoryPolicy(subjectId, categoryId, purposeId, granted, expiresAt, now);
        } else {
            return;
        }

        // Append audit trail entry
        dsl.insertInto(CONSENT_AUDIT_LOG)
            .set(CONSENT_AUDIT_LOG.SUBJECT_ID, subjectId)
            .set(CONSENT_AUDIT_LOG.FIELD_ID, fieldId)
            .set(CONSENT_AUDIT_LOG.CATEGORY_ID, categoryId)
            .set(CONSENT_AUDIT_LOG.PURPOSE_ID, purposeId)
            .set(CONSENT_AUDIT_LOG.ACTION, granted ? "GRANTED" : "REVOKED")
            .set(CONSENT_AUDIT_LOG.GRANTED, granted)
            .set(CONSENT_AUDIT_LOG.EXPIRES_AT, expiresAt)
            .set(CONSENT_AUDIT_LOG.CREATED_AT, now)
            .execute();
    }

    private void handlePolicyRevoked(JsonNode node) {
        // Revocation uses the same upsert path with granted=false
        handlePolicyGranted(node);
    }

    private void handlePolicyAllRevoked(JsonNode node) {
        String subjectId = node.get("subjectId").asText();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        dsl.deleteFrom(CONSENT_POLICY)
            .where(CONSENT_POLICY.SUBJECT_ID.eq(subjectId))
            .execute();

        // Audit the bulk revocation
        dsl.insertInto(CONSENT_AUDIT_LOG)
            .set(CONSENT_AUDIT_LOG.SUBJECT_ID, subjectId)
            .set(CONSENT_AUDIT_LOG.ACTION, "ALL_REVOKED")
            .set(CONSENT_AUDIT_LOG.GRANTED, false)
            .set(CONSENT_AUDIT_LOG.CREATED_AT, now)
            .execute();
    }

    private void handlePolicyAuditDeleted(JsonNode node) {
        String subjectId = node.get("subjectId").asText();

        dsl.deleteFrom(CONSENT_AUDIT_LOG)
            .where(CONSENT_AUDIT_LOG.SUBJECT_ID.eq(subjectId))
            .execute();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void upsertFieldPolicy(
        String subjectId, Long fieldId, Long purposeId,
        boolean granted, LocalDateTime expiresAt, LocalDateTime now
    ) {
        var insert = dsl.insertInto(CONSENT_POLICY)
            .set(CONSENT_POLICY.SUBJECT_ID, subjectId)
            .set(CONSENT_POLICY.FIELD_ID, fieldId)
            .set(CONSENT_POLICY.PURPOSE_ID, purposeId)
            .set(CONSENT_POLICY.GRANTED, granted)
            .set(CONSENT_POLICY.EXPIRES_AT, expiresAt)
            .set(CONSENT_POLICY.CREATED_AT, now)
            .set(CONSENT_POLICY.UPDATED_AT, now);

        if (granted) {
            insert.set(CONSENT_POLICY.GRANTED_AT, now);
        } else {
            insert.set(CONSENT_POLICY.REVOKED_AT, now);
        }

        insert.onConflict(CONSENT_POLICY.SUBJECT_ID, CONSENT_POLICY.FIELD_ID, CONSENT_POLICY.PURPOSE_ID)
            .doUpdate()
            .set(CONSENT_POLICY.GRANTED, granted)
            .set(CONSENT_POLICY.EXPIRES_AT, expiresAt)
            .set(CONSENT_POLICY.UPDATED_AT, now)
            .set(granted ? CONSENT_POLICY.GRANTED_AT : CONSENT_POLICY.REVOKED_AT, now)
            .execute();
    }

    private void upsertCategoryPolicy(
        String subjectId, Long categoryId, Long purposeId,
        boolean granted, LocalDateTime expiresAt, LocalDateTime now
    ) {
        var insert = dsl.insertInto(CONSENT_POLICY)
            .set(CONSENT_POLICY.SUBJECT_ID, subjectId)
            .set(CONSENT_POLICY.CATEGORY_ID, categoryId)
            .set(CONSENT_POLICY.PURPOSE_ID, purposeId)
            .set(CONSENT_POLICY.GRANTED, granted)
            .set(CONSENT_POLICY.EXPIRES_AT, expiresAt)
            .set(CONSENT_POLICY.CREATED_AT, now)
            .set(CONSENT_POLICY.UPDATED_AT, now);

        if (granted) {
            insert.set(CONSENT_POLICY.GRANTED_AT, now);
        } else {
            insert.set(CONSENT_POLICY.REVOKED_AT, now);
        }

        insert.onConflict(CONSENT_POLICY.SUBJECT_ID, CONSENT_POLICY.CATEGORY_ID, CONSENT_POLICY.PURPOSE_ID)
            .doUpdate()
            .set(CONSENT_POLICY.GRANTED, granted)
            .set(CONSENT_POLICY.EXPIRES_AT, expiresAt)
            .set(CONSENT_POLICY.UPDATED_AT, now)
            .set(granted ? CONSENT_POLICY.GRANTED_AT : CONSENT_POLICY.REVOKED_AT, now)
            .execute();
    }

    private Long resolveFieldId(String entityName, String fieldName) {
        if (entityName == null || fieldName == null) return null;
        return dsl.select(FIELD.FIELD_ID)
            .from(FIELD)
            .join(ENTITY).on(FIELD.ENTITY_ID.eq(ENTITY.ENTITY_ID))
            .where(FIELD.FIELD_NAME.eq(fieldName))
            .and(ENTITY.ENTITY_NAME.eq(entityName))
            .fetchOneInto(Long.class);
    }

    private Long resolveCategoryId(String categoryName) {
        if (categoryName == null) return null;
        return dsl.select(CATEGORY.CATEGORY_ID)
            .from(CATEGORY)
            .where(CATEGORY.CATEGORY_NAME.eq(categoryName))
            .fetchOneInto(Long.class);
    }

    private Long resolvePurposeId(String purposeName) {
        if (purposeName == null) return null;
        return dsl.select(PURPOSE.PURPOSE_ID)
            .from(PURPOSE)
            .where(PURPOSE.PURPOSE_NAME.eq(purposeName))
            .fetchOneInto(Long.class);
    }

    private Long resolveRegulationId(String regulationName) {
        if (regulationName == null) return null;
        return dsl.select(REGULATION.REGULATION_ID)
            .from(REGULATION)
            .where(REGULATION.REGULATION_NAME.eq(regulationName))
            .fetchOneInto(Long.class);
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return (child != null && !child.isNull()) ? child.asText() : null;
    }

    private static LocalDateTime instantToLocal(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child == null || child.isNull()) return null;
        return LocalDateTime.ofInstant(Instant.parse(child.asText()), ZoneOffset.UTC);
    }
}
