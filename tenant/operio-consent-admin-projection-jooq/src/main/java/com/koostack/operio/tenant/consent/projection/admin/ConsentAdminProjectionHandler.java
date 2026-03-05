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
import com.koostack.operio.plugin.consent.jooq.generated.enums.ConsentAction;
import com.koostack.operio.plugin.consent.jooq.generated.enums.MaskingType;
import com.koostack.operio.plugin.consent.jooq.generated.enums.PolicyType;

/**
 * Stream projection handler that materializes consent domain events
 * into the consent admin relational schema.
 *
 * <p>Handles events for all 8 aggregates: Entity, Category, Field, Purpose,
 * Regulation, Jurisdiction, SubjectConsent, and DSAR. All writes are
 * idempotent (upsert or delete) and routed to the correct per-tenant
 * schema via {@link TenantContext}.
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
                JsonNode node = payload != null
                    ? objectMapper.readTree(payload)
                    : objectMapper.createObjectNode();
                dispatch(eventType, node);
                log.debug("Projected event {} [{}] for tenant {}", messageId, eventType, tenantId);
            } catch (Exception e) {
                log.error("Failed to project event {} [{}] for tenant {}",
                    messageId, eventType, tenantId, e);
                throw new RuntimeException(e);
            }
        });
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
            case ConsentEventTypes.PURPOSE_REGULATION_UNLINKED -> handlePurposeRegulationUnlinked(node);
            case ConsentEventTypes.PURPOSE_POLICY_ADDED -> handlePurposePolicyAdded(node);
            case ConsentEventTypes.PURPOSE_POLICY_REMOVED -> handlePurposePolicyRemoved(node);

            // -- Regulation Aggregate --
            case ConsentEventTypes.REGULATION_REGISTERED -> handleRegulationRegistered(node);
            case ConsentEventTypes.REGULATION_UPDATED -> handleRegulationUpdated(node);
            case ConsentEventTypes.REGULATION_REMOVED -> handleRegulationRemoved(node);
            case ConsentEventTypes.REGULATION_JURISDICTION_ADDED -> handleRegulationJurisdictionAdded(node);
            case ConsentEventTypes.REGULATION_JURISDICTION_REMOVED -> handleRegulationJurisdictionRemoved(node);

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

        dsl.insertInto(ENTITY)
            .set(ENTITY.ENTITY_NAME, entityName)
            .set(ENTITY.DESCRIPTION, description)
            .set(ENTITY.CREATED_AT, now)
            .set(ENTITY.UPDATED_AT, now)
            .onConflict(ENTITY.ENTITY_NAME)
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

        Long entityId = resolveEntityId(entityName);
        if (entityId != null) {
            // Remove field_category links for all fields of this entity
            dsl.deleteFrom(FIELD_CATEGORY)
                .where(FIELD_CATEGORY.FIELD_ID.in(
                    dsl.select(FIELD.FIELD_ID).from(FIELD)
                        .where(FIELD.ENTITY_ID.eq(entityId))
                ))
                .execute();
            dsl.deleteFrom(FIELD)
                .where(FIELD.ENTITY_ID.eq(entityId))
                .execute();
        }

        dsl.deleteFrom(ENTITY)
            .where(ENTITY.ENTITY_NAME.eq(entityName))
            .execute();
    }

    // -----------------------------------------------------------------------
    // Category Aggregate
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

    private void handleCategoryUpdated(JsonNode node) {
        handleCategoryRegistered(node);
    }

    private void handleCategoryRemoved(JsonNode node) {
        String categoryName = node.get("categoryName").asText();

        Long categoryId = resolveCategoryId(categoryName);
        if (categoryId != null) {
            dsl.deleteFrom(FIELD_CATEGORY)
                .where(FIELD_CATEGORY.CATEGORY_ID.eq(categoryId))
                .execute();
        }

        dsl.deleteFrom(CATEGORY)
            .where(CATEGORY.CATEGORY_NAME.eq(categoryName))
            .execute();
    }

    // -----------------------------------------------------------------------
    // Jurisdiction Aggregate
    // -----------------------------------------------------------------------

    private void handleJurisdictionRegistered(JsonNode node) {
        String jurisdictionName = node.get("jurisdictionName").asText();
        String description = textOrNull(node, "description");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        dsl.insertInto(JURISDICTION)
            .set(JURISDICTION.JURISDICTION_NAME, jurisdictionName)
            .set(JURISDICTION.DESCRIPTION, description)
            .set(JURISDICTION.CREATED_AT, now)
            .set(JURISDICTION.UPDATED_AT, now)
            .onConflict(JURISDICTION.JURISDICTION_NAME)
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

        Long jurisdictionId = resolveJurisdictionId(jurisdictionName);
        if (jurisdictionId != null) {
            dsl.deleteFrom(REGULATION_JURISDICTION)
                .where(REGULATION_JURISDICTION.JURISDICTION_ID.eq(jurisdictionId))
                .execute();
        }

        dsl.deleteFrom(JURISDICTION)
            .where(JURISDICTION.JURISDICTION_NAME.eq(jurisdictionName))
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

        // Upsert entity (auto-create if needed)
        dsl.insertInto(ENTITY)
            .set(ENTITY.ENTITY_NAME, entityName)
            .set(ENTITY.DESCRIPTION, entityDescription)
            .set(ENTITY.CREATED_AT, now)
            .set(ENTITY.UPDATED_AT, now)
            .onConflict(ENTITY.ENTITY_NAME)
            .doUpdate()
            .set(ENTITY.UPDATED_AT, now)
            .execute();

        Long entityId = resolveEntityId(entityName);
        if (entityId == null) {
            log.warn("Entity '{}' not found after upsert — skipping field registration", entityName);
            return;
        }

        // Upsert field
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

    private void handleFieldUpdated(JsonNode node) {
        String entityName = node.get("entityName").asText();
        String fieldName = node.get("fieldName").asText();
        String description = textOrNull(node, "description");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        Long entityId = resolveEntityId(entityName);
        if (entityId != null) {
            dsl.update(FIELD)
                .set(FIELD.DESCRIPTION, description)
                .set(FIELD.UPDATED_AT, now)
                .where(FIELD.FIELD_NAME.eq(fieldName))
                .and(FIELD.ENTITY_ID.eq(entityId))
                .execute();
        }
    }

    private void handleFieldRemoved(JsonNode node) {
        String entityName = node.get("entityName").asText();
        String fieldName = node.get("fieldName").asText();

        Long fieldId = resolveFieldId(entityName, fieldName);
        if (fieldId != null) {
            // FK order: consent_ledger → policy → field_category → field
            dsl.deleteFrom(CONSENT_LEDGER)
                .where(CONSENT_LEDGER.POLICY_ID.in(
                    dsl.select(POLICY.POLICY_ID).from(POLICY)
                        .where(POLICY.FIELD_ID.eq(fieldId))
                ))
                .execute();
            dsl.deleteFrom(POLICY)
                .where(POLICY.FIELD_ID.eq(fieldId))
                .execute();
            dsl.deleteFrom(FIELD_CATEGORY)
                .where(FIELD_CATEGORY.FIELD_ID.eq(fieldId))
                .execute();
            dsl.deleteFrom(FIELD)
                .where(FIELD.FIELD_ID.eq(fieldId))
                .execute();
        }
    }

    private void handleFieldCategoryAssigned(JsonNode node) {
        String entityName = node.get("entityName").asText();
        String fieldName = node.get("fieldName").asText();
        String categoryName = node.get("categoryName").asText();
        String categoryDescription = textOrNull(node, "categoryDescription");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        // Auto-create category if needed
        dsl.insertInto(CATEGORY)
            .set(CATEGORY.CATEGORY_NAME, categoryName)
            .set(CATEGORY.DESCRIPTION, categoryDescription)
            .set(CATEGORY.CREATED_AT, now)
            .set(CATEGORY.UPDATED_AT, now)
            .onConflict(CATEGORY.CATEGORY_NAME)
            .doUpdate()
            .set(CATEGORY.UPDATED_AT, now)
            .execute();

        Long fieldId = resolveFieldId(entityName, fieldName);
        Long categoryId = resolveCategoryId(categoryName);

        if (fieldId == null || categoryId == null) {
            log.warn("Cannot assign category '{}' to field '{}/{}' — not found",
                categoryName, entityName, fieldName);
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

    private void handleFieldCategoryUnassigned(JsonNode node) {
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
    // Purpose Aggregate
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

    private void handlePurposeUpdated(JsonNode node) {
        handlePurposeRegistered(node);
    }

    private void handlePurposeRemoved(JsonNode node) {
        String purposeName = node.get("purposeName").asText();

        Long purposeId = resolvePurposeId(purposeName);
        if (purposeId != null) {
            // FK order: consent_ledger → policy → purpose_regulation → purpose
            dsl.deleteFrom(CONSENT_LEDGER)
                .where(CONSENT_LEDGER.POLICY_ID.in(
                    dsl.select(POLICY.POLICY_ID).from(POLICY)
                        .where(POLICY.PURPOSE_ID.eq(purposeId))
                ))
                .execute();
            dsl.deleteFrom(POLICY)
                .where(POLICY.PURPOSE_ID.eq(purposeId))
                .execute();
            dsl.deleteFrom(PURPOSE_REGULATION)
                .where(PURPOSE_REGULATION.PURPOSE_ID.eq(purposeId))
                .execute();
        }

        dsl.deleteFrom(PURPOSE)
            .where(PURPOSE.PURPOSE_NAME.eq(purposeName))
            .execute();
    }

    private void handlePurposeRegulationLinked(JsonNode node) {
        String purposeName = node.get("purposeName").asText();
        String regulationName = node.get("regulationName").asText();
        String lawfulBasis = textOrNull(node, "lawfulBasis");
        String onGranted = textOrNull(node, "onGranted");
        String onDenied = textOrNull(node, "onDenied");
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
            .set(PURPOSE_REGULATION.LAWFUL_BASIS, lawfulBasis)
            .set(PURPOSE_REGULATION.ON_GRANTED, MaskingType.lookupLiteral(onGranted))
            .set(PURPOSE_REGULATION.ON_DENIED, MaskingType.lookupLiteral(onDenied))
            .set(PURPOSE_REGULATION.CREATED_AT, now)
            .set(PURPOSE_REGULATION.UPDATED_AT, now)
            .onConflict(PURPOSE_REGULATION.PURPOSE_ID, PURPOSE_REGULATION.REGULATION_ID)
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

        Long purposeId = resolvePurposeId(purposeName);
        Long regulationId = resolveRegulationId(regulationName);

        if (purposeId != null && regulationId != null) {
            dsl.deleteFrom(PURPOSE_REGULATION)
                .where(PURPOSE_REGULATION.PURPOSE_ID.eq(purposeId))
                .and(PURPOSE_REGULATION.REGULATION_ID.eq(regulationId))
                .execute();
        }
    }

    private void handlePurposePolicyAdded(JsonNode node) {
        String purposeName = node.get("purposeName").asText();
        String policyType = node.get("policyType").asText();
        String fieldName = textOrNull(node, "fieldName");
        String entityName = textOrNull(node, "entityName");
        String categoryName = textOrNull(node, "categoryName");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        Long purposeId = resolvePurposeId(purposeName);
        if (purposeId == null) {
            log.warn("Purpose '{}' not found — cannot add policy", purposeName);
            return;
        }

        Long fieldId = null;
        Long categoryId = null;

        if ("FIELD".equals(policyType)) {
            fieldId = resolveFieldId(entityName, fieldName);
            if (fieldId == null) {
                log.warn("Field '{}/{}' not found — cannot add policy", entityName, fieldName);
                return;
            }
            dsl.insertInto(POLICY)
                .set(POLICY.PURPOSE_ID, purposeId)
                .set(POLICY.FIELD_ID, fieldId)
                .set(POLICY.POLICY_TYPE, PolicyType.lookupLiteral(policyType))
                .set(POLICY.CREATED_AT, now)
                .set(POLICY.UPDATED_AT, now)
                .onConflict(POLICY.PURPOSE_ID, POLICY.FIELD_ID)
                .doUpdate()
                .set(POLICY.UPDATED_AT, now)
                .execute();
        } else if ("CATEGORY".equals(policyType)) {
            categoryId = resolveCategoryId(categoryName);
            if (categoryId == null) {
                log.warn("Category '{}' not found — cannot add policy", categoryName);
                return;
            }
            dsl.insertInto(POLICY)
                .set(POLICY.PURPOSE_ID, purposeId)
                .set(POLICY.CATEGORY_ID, categoryId)
                .set(POLICY.POLICY_TYPE, PolicyType.lookupLiteral(policyType))
                .set(POLICY.CREATED_AT, now)
                .set(POLICY.UPDATED_AT, now)
                .onConflict(POLICY.PURPOSE_ID, POLICY.CATEGORY_ID)
                .doUpdate()
                .set(POLICY.UPDATED_AT, now)
                .execute();
        }
    }

    private void handlePurposePolicyRemoved(JsonNode node) {
        String purposeName = node.get("purposeName").asText();
        String policyType = node.get("policyType").asText();
        String fieldName = textOrNull(node, "fieldName");
        String entityName = textOrNull(node, "entityName");
        String categoryName = textOrNull(node, "categoryName");

        Long purposeId = resolvePurposeId(purposeName);
        if (purposeId == null) return;

        if ("FIELD".equals(policyType)) {
            Long fieldId = resolveFieldId(entityName, fieldName);
            if (fieldId != null) {
                // Delete consent_ledger entries referencing this policy first
                dsl.deleteFrom(CONSENT_LEDGER)
                    .where(CONSENT_LEDGER.POLICY_ID.in(
                        dsl.select(POLICY.POLICY_ID).from(POLICY)
                            .where(POLICY.PURPOSE_ID.eq(purposeId))
                            .and(POLICY.FIELD_ID.eq(fieldId))
                    ))
                    .execute();
                dsl.deleteFrom(POLICY)
                    .where(POLICY.PURPOSE_ID.eq(purposeId))
                    .and(POLICY.FIELD_ID.eq(fieldId))
                    .execute();
            }
        } else if ("CATEGORY".equals(policyType)) {
            Long categoryId = resolveCategoryId(categoryName);
            if (categoryId != null) {
                dsl.deleteFrom(CONSENT_LEDGER)
                    .where(CONSENT_LEDGER.POLICY_ID.in(
                        dsl.select(POLICY.POLICY_ID).from(POLICY)
                            .where(POLICY.PURPOSE_ID.eq(purposeId))
                            .and(POLICY.CATEGORY_ID.eq(categoryId))
                    ))
                    .execute();
                dsl.deleteFrom(POLICY)
                    .where(POLICY.PURPOSE_ID.eq(purposeId))
                    .and(POLICY.CATEGORY_ID.eq(categoryId))
                    .execute();
            }
        }
    }

    // -----------------------------------------------------------------------
    // Regulation Aggregate
    // -----------------------------------------------------------------------

    private void handleRegulationRegistered(JsonNode node) {
        String regulationName = node.get("regulationName").asText();
        String description = textOrNull(node, "description");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        dsl.insertInto(REGULATION)
            .set(REGULATION.REGULATION_NAME, regulationName)
            .set(REGULATION.DESCRIPTION, description)
            .set(REGULATION.CREATED_AT, now)
            .set(REGULATION.UPDATED_AT, now)
            .onConflict(REGULATION.REGULATION_NAME)
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

        Long regulationId = resolveRegulationId(regulationName);
        if (regulationId != null) {
            dsl.deleteFrom(REGULATION_JURISDICTION)
                .where(REGULATION_JURISDICTION.REGULATION_ID.eq(regulationId))
                .execute();
            dsl.deleteFrom(PURPOSE_REGULATION)
                .where(PURPOSE_REGULATION.REGULATION_ID.eq(regulationId))
                .execute();
        }

        dsl.deleteFrom(REGULATION)
            .where(REGULATION.REGULATION_NAME.eq(regulationName))
            .execute();
    }

    private void handleRegulationJurisdictionAdded(JsonNode node) {
        String regulationName = node.get("regulationName").asText();
        String jurisdictionName = node.get("jurisdictionName").asText();
        String description = textOrNull(node, "description");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        Long regulationId = resolveRegulationId(regulationName);
        if (regulationId == null) {
            log.warn("Regulation '{}' not found — cannot add jurisdiction", regulationName);
            return;
        }

        // Upsert jurisdiction
        dsl.insertInto(JURISDICTION)
            .set(JURISDICTION.JURISDICTION_NAME, jurisdictionName)
            .set(JURISDICTION.DESCRIPTION, description)
            .set(JURISDICTION.CREATED_AT, now)
            .set(JURISDICTION.UPDATED_AT, now)
            .onConflict(JURISDICTION.JURISDICTION_NAME)
            .doUpdate()
            .set(JURISDICTION.UPDATED_AT, now)
            .execute();

        Long jurisdictionId = resolveJurisdictionId(jurisdictionName);
        if (jurisdictionId == null) return;

        // Link regulation ↔ jurisdiction
        dsl.insertInto(REGULATION_JURISDICTION)
            .set(REGULATION_JURISDICTION.REGULATION_ID, regulationId)
            .set(REGULATION_JURISDICTION.JURISDICTION_ID, jurisdictionId)
            .set(REGULATION_JURISDICTION.CREATED_AT, now)
            .set(REGULATION_JURISDICTION.UPDATED_AT, now)
            .onConflict(REGULATION_JURISDICTION.REGULATION_ID,
                        REGULATION_JURISDICTION.JURISDICTION_ID)
            .doNothing()
            .execute();
    }

    private void handleRegulationJurisdictionRemoved(JsonNode node) {
        String regulationName = node.get("regulationName").asText();
        String jurisdictionName = node.get("jurisdictionName").asText();

        Long regulationId = resolveRegulationId(regulationName);
        Long jurisdictionId = resolveJurisdictionId(jurisdictionName);

        if (regulationId != null && jurisdictionId != null) {
            dsl.deleteFrom(REGULATION_JURISDICTION)
                .where(REGULATION_JURISDICTION.REGULATION_ID.eq(regulationId))
                .and(REGULATION_JURISDICTION.JURISDICTION_ID.eq(jurisdictionId))
                .execute();
        }
    }

    // -----------------------------------------------------------------------
    // Subject Aggregate (entity lifecycle)
    // -----------------------------------------------------------------------

    private void handleSubjectRegistered(JsonNode node) {
        String subjectName = node.get("subjectName").asText();
        String description = textOrNull(node, "description");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        dsl.insertInto(SUBJECT)
            .set(SUBJECT.SUBJECT_NAME, subjectName)
            .set(SUBJECT.DESCRIPTION, description)
            .set(SUBJECT.CREATED_AT, now)
            .set(SUBJECT.UPDATED_AT, now)
            .onConflict(SUBJECT.SUBJECT_NAME)
            .doUpdate()
            .set(SUBJECT.DESCRIPTION, description)
            .set(SUBJECT.UPDATED_AT, now)
            .execute();
    }

    private void handleSubjectUpdated(JsonNode node) {
        String subjectName = node.get("subjectName").asText();
        String description = textOrNull(node, "description");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        dsl.update(SUBJECT)
            .set(SUBJECT.DESCRIPTION, description)
            .set(SUBJECT.UPDATED_AT, now)
            .where(SUBJECT.SUBJECT_NAME.eq(subjectName))
            .execute();
    }

    private void handleSubjectRemoved(JsonNode node) {
        String subjectName = node.get("subjectName").asText();

        dsl.deleteFrom(SUBJECT)
            .where(SUBJECT.SUBJECT_NAME.eq(subjectName))
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

    private void insertConsentLedger(JsonNode node, String action) {
        String subjectId = node.get("subjectId").asText();
        long policyId = node.get("policyId").asLong();
        String actor = textOrNull(node, "actor");
        String ipAddress = textOrNull(node, "ipAddress");
        String userAgent = textOrNull(node, "userAgent");
        LocalDateTime expiresAt = instantToLocal(node, "expiresAt");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        ConsentAction consentAction = ConsentAction.lookupLiteral(action);

        dsl.insertInto(CONSENT_LEDGER)
            .set(CONSENT_LEDGER.POLICY_ID, policyId)
            .set(CONSENT_LEDGER.SUBJECT_ID, subjectId)
            .set(CONSENT_LEDGER.ACTION, consentAction)
            .set(CONSENT_LEDGER.ACTOR, actor)
            .set(CONSENT_LEDGER.IP_ADDRESS, ipAddress)
            .set(CONSENT_LEDGER.USER_AGENT, userAgent)
            .set(CONSENT_LEDGER.CREATED_AT, now)
            .set(CONSENT_LEDGER.EXPIRES_AT, expiresAt)
            .execute();
    }

    private void handleConsentAllRevoked(JsonNode node) {
        String subjectId = node.get("subjectId").asText();
        String actor = textOrNull(node, "actor");
        String ipAddress = textOrNull(node, "ipAddress");
        String userAgent = textOrNull(node, "userAgent");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        // Find currently-granted policies via latest entry per subject+policy
        org.jooq.Field<Integer> rn = org.jooq.impl.DSL.rowNumber()
            .over(org.jooq.impl.DSL.partitionBy(CONSENT_LEDGER.POLICY_ID)
                .orderBy(CONSENT_LEDGER.CREATED_AT.desc()))
            .as("rn");

        var ranked = dsl.select(CONSENT_LEDGER.POLICY_ID, CONSENT_LEDGER.ACTION, rn)
                .from(CONSENT_LEDGER)
                .where(CONSENT_LEDGER.SUBJECT_ID.eq(subjectId))
                .asTable("ranked");

        var latestGranted = dsl.select(ranked.field(CONSENT_LEDGER.POLICY_ID))
            .from(ranked)
            .where(ranked.field("rn", Integer.class).eq(1))
            .and(ranked.field(CONSENT_LEDGER.ACTION).eq(ConsentAction.GRANT))
            .fetchInto(Long.class);

        // Fan out one REVOKE row per active grant
        for (Long policyId : latestGranted) {
            dsl.insertInto(CONSENT_LEDGER)
                .set(CONSENT_LEDGER.POLICY_ID, policyId)
                .set(CONSENT_LEDGER.SUBJECT_ID, subjectId)
                .set(CONSENT_LEDGER.ACTION, ConsentAction.REVOKE)
                .set(CONSENT_LEDGER.ACTOR, actor)
                .set(CONSENT_LEDGER.IP_ADDRESS, ipAddress)
                .set(CONSENT_LEDGER.USER_AGENT, userAgent)
                .set(CONSENT_LEDGER.CREATED_AT, now)
                .set(CONSENT_LEDGER.EXPIRES_AT, (LocalDateTime) null)
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

        // Count what will be deleted
        int ledgerCount = dsl.selectCount().from(CONSENT_LEDGER)
            .where(CONSENT_LEDGER.SUBJECT_ID.eq(subjectId))
            .fetchOneInto(int.class);

        // Delete consent ledger entries for this subject
        dsl.deleteFrom(CONSENT_LEDGER)
            .where(CONSENT_LEDGER.SUBJECT_ID.eq(subjectId))
            .execute();

        // Record the DSAR action
        dsl.insertInto(DSAR_AUDIT_LOG)
            .set(DSAR_AUDIT_LOG.SUBJECT_ID, subjectId)
            .set(DSAR_AUDIT_LOG.DELETION_REQUEST_ID, deletionRequestId)
            .set(DSAR_AUDIT_LOG.POLICIES_DELETED, ledgerCount)
            .set(DSAR_AUDIT_LOG.STATUS, "SUCCESS")
            .set(DSAR_AUDIT_LOG.ACTOR, actor)
            .set(DSAR_AUDIT_LOG.IP_ADDRESS, ipAddress)
            .set(DSAR_AUDIT_LOG.USER_AGENT, userAgent)
            .set(DSAR_AUDIT_LOG.CREATED_AT, now)
            .execute();
    }

    // -----------------------------------------------------------------------
    // ID Resolution Helpers
    // -----------------------------------------------------------------------

    private Long resolveEntityId(String entityName) {
        if (entityName == null) return null;
        return dsl.select(ENTITY.ENTITY_ID)
            .from(ENTITY)
            .where(ENTITY.ENTITY_NAME.eq(entityName))
            .fetchOneInto(Long.class);
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

    private Long resolveJurisdictionId(String jurisdictionName) {
        if (jurisdictionName == null) return null;
        return dsl.select(JURISDICTION.JURISDICTION_ID)
            .from(JURISDICTION)
            .where(JURISDICTION.JURISDICTION_NAME.eq(jurisdictionName))
            .fetchOneInto(Long.class);
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
        return LocalDateTime.ofInstant(Instant.parse(child.asText()), ZoneOffset.UTC);
    }
}
