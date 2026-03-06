package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.tenant.consent.ConsentAdminCommandService;
import com.koostack.operio.core.tenant.consent.ConsentEventTypes;
import com.koostack.operio.core.tenant.consent.aggregates.*;
import com.koostack.operio.core.tenant.consent.events.*;
import com.koostack.operio.core.event.DomainEvent;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.models.TenantContext;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Event-sourced {@link ConsentAdminCommandService} implementation.
 *
 * <p>All write operations publish semantic domain events via
 * {@link EventService} instead of writing directly to the database.
 * Separate projections materialize the event stream into the consent
 * read model (admin DB) and Redis decision cache.
 */
public class JooqConsentAdminCommandService
    implements ConsentAdminCommandService<ConsentAdminCommandConfig>
{

    private static final Logger LOG = LoggerFactory.getLogger(
        JooqConsentAdminCommandService.class
    );

    private EventService<?> eventService;

    // -----------------------------------------------------------------------
    // Plugin SPI
    // -----------------------------------------------------------------------

    @Override
    public String getName() {
        return "jooq-consent-admin-command";
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public Class<ConsentAdminCommandConfig> getConfigurationType() {
        return ConsentAdminCommandConfig.class;
    }

    @Override
    public void configure(ConsentAdminCommandConfig config) {
        this.eventService = config.eventService();
        LOG.info("JooqConsentAdminCommandService configured (event-sourced)");
    }

    @Override
    public boolean isReady() {
        return eventService != null && eventService.isReady();
    }

    // -----------------------------------------------------------------------
    // Entity Aggregate
    // -----------------------------------------------------------------------

    @Override
    public void registerEntityCommand(String entityName, String description) {
        Objects.requireNonNull(entityName, "entityName must not be null");
        String tenantId = TenantContext.require();
        UUID aggregateId = EntityAggregate.deriveId(entityName);
        var payload = new EntityRegisteredEvent(entityName, description);
        publishEvent(tenantId, ConsentEventTypes.ENTITY_REGISTERED,
            EntityAggregate.AGGREGATE_TYPE, aggregateId, payload);
    }

    @Override
    public void updateEntityCommand(String entityName, String description) {
        Objects.requireNonNull(entityName, "entityName must not be null");
        String tenantId = TenantContext.require();
        UUID aggregateId = EntityAggregate.deriveId(entityName);
        var payload = new EntityUpdatedEvent(entityName, description);
        publishEvent(tenantId, ConsentEventTypes.ENTITY_UPDATED,
            EntityAggregate.AGGREGATE_TYPE, aggregateId, payload);
    }

    @Override
    public void removeEntityCommand(String entityName) {
        Objects.requireNonNull(entityName, "entityName must not be null");
        String tenantId = TenantContext.require();
        UUID aggregateId = EntityAggregate.deriveId(entityName);
        var payload = new EntityRemovedEvent(entityName);
        publishEvent(tenantId, ConsentEventTypes.ENTITY_REMOVED,
            EntityAggregate.AGGREGATE_TYPE, aggregateId, payload);
    }

    // -----------------------------------------------------------------------
    // Category Aggregate
    // -----------------------------------------------------------------------

    @Override
    public void registerCategoryCommand(String categoryName, String description) {
        Objects.requireNonNull(categoryName, "categoryName must not be null");
        String tenantId = TenantContext.require();
        UUID aggregateId = CategoryAggregate.deriveId(categoryName);
        var payload = new CategoryRegisteredEvent(categoryName, description);
        publishEvent(tenantId, ConsentEventTypes.CATEGORY_REGISTERED,
            CategoryAggregate.AGGREGATE_TYPE, aggregateId, payload);
    }

    @Override
    public void updateCategoryCommand(String categoryName, String description) {
        Objects.requireNonNull(categoryName, "categoryName must not be null");
        String tenantId = TenantContext.require();
        UUID aggregateId = CategoryAggregate.deriveId(categoryName);
        var payload = new CategoryUpdatedEvent(categoryName, description);
        publishEvent(tenantId, ConsentEventTypes.CATEGORY_UPDATED,
            CategoryAggregate.AGGREGATE_TYPE, aggregateId, payload);
    }

    @Override
    public void removeCategoryCommand(String categoryName) {
        Objects.requireNonNull(categoryName, "categoryName must not be null");
        String tenantId = TenantContext.require();
        UUID aggregateId = CategoryAggregate.deriveId(categoryName);
        var payload = new CategoryRemovedEvent(categoryName);
        publishEvent(tenantId, ConsentEventTypes.CATEGORY_REMOVED,
            CategoryAggregate.AGGREGATE_TYPE, aggregateId, payload);
    }

    // -----------------------------------------------------------------------
    // Field Aggregate
    // -----------------------------------------------------------------------

    @Override
    public void registerFieldCommand(String fieldName, String entityName,
                                     String description, String entityDescription) {
        Objects.requireNonNull(fieldName, "fieldName must not be null");
        String tenantId = TenantContext.require();
        String normalized = FieldAggregate.normalizeEntityName(entityName);
        UUID aggregateId = FieldAggregate.deriveId(normalized, fieldName);
        var payload = new FieldRegisteredEvent(normalized, fieldName, description, entityDescription);
        publishEvent(tenantId, ConsentEventTypes.FIELD_REGISTERED,
            FieldAggregate.AGGREGATE_TYPE, aggregateId, payload);
    }

    @Override
    public void removeFieldCommand(String fieldName, String entityName) {
        Objects.requireNonNull(fieldName, "fieldName must not be null");
        String tenantId = TenantContext.require();
        String normalized = FieldAggregate.normalizeEntityName(entityName);
        UUID aggregateId = FieldAggregate.deriveId(normalized, fieldName);
        var payload = new FieldRemovedEvent(normalized, fieldName);
        publishEvent(tenantId, ConsentEventTypes.FIELD_REMOVED,
            FieldAggregate.AGGREGATE_TYPE, aggregateId, payload);
    }

    @Override
    public void updateFieldCommand(String fieldName, String entityName,
                                   String description, FieldSnapshot snapshot) {
        Objects.requireNonNull(fieldName, "fieldName must not be null");
        String tenantId = TenantContext.require();
        String normalized = FieldAggregate.normalizeEntityName(entityName);
        UUID aggregateId = FieldAggregate.deriveId(normalized, fieldName);
        var payload = new FieldUpdatedEvent(normalized, fieldName, description, snapshot);
        publishEvent(tenantId, ConsentEventTypes.FIELD_UPDATED,
            FieldAggregate.AGGREGATE_TYPE, aggregateId, payload);
    }

    @Override
    public void assignFieldCategoryCommand(String fieldName, String entityName,
                                           String categoryName, String categoryDescription,
                                           FieldSnapshot snapshot) {
        Objects.requireNonNull(fieldName, "fieldName must not be null");
        Objects.requireNonNull(categoryName, "categoryName must not be null");
        String tenantId = TenantContext.require();
        String normalized = FieldAggregate.normalizeEntityName(entityName);
        UUID aggregateId = FieldAggregate.deriveId(normalized, fieldName);
        var payload = new FieldCategoryAssignedEvent(
            normalized, fieldName, categoryName, categoryDescription, snapshot);
        publishEvent(tenantId, ConsentEventTypes.FIELD_CATEGORY_ASSIGNED,
            FieldAggregate.AGGREGATE_TYPE, aggregateId, payload);
    }

    @Override
    public void unassignFieldCategoryCommand(String fieldName, String entityName,
                                             String categoryName, FieldSnapshot snapshot) {
        Objects.requireNonNull(fieldName, "fieldName must not be null");
        Objects.requireNonNull(categoryName, "categoryName must not be null");
        String tenantId = TenantContext.require();
        String normalized = FieldAggregate.normalizeEntityName(entityName);
        UUID aggregateId = FieldAggregate.deriveId(normalized, fieldName);
        var payload = new FieldCategoryUnassignedEvent(normalized, fieldName, categoryName, snapshot);
        publishEvent(tenantId, ConsentEventTypes.FIELD_CATEGORY_UNASSIGNED,
            FieldAggregate.AGGREGATE_TYPE, aggregateId, payload);
    }

    // -----------------------------------------------------------------------
    // Purpose Aggregate
    // -----------------------------------------------------------------------

    @Override
    public void registerPurposeCommand(String purposeName, String description) {
        Objects.requireNonNull(purposeName, "purposeName must not be null");
        String tenantId = TenantContext.require();
        UUID aggregateId = PurposeAggregate.deriveId(purposeName);
        var payload = new PurposeRegisteredEvent(purposeName, description);
        publishEvent(tenantId, ConsentEventTypes.PURPOSE_REGISTERED,
            PurposeAggregate.AGGREGATE_TYPE, aggregateId, payload);
    }

    @Override
    public void removePurposeCommand(String purposeName) {
        Objects.requireNonNull(purposeName, "purposeName must not be null");
        String tenantId = TenantContext.require();
        UUID aggregateId = PurposeAggregate.deriveId(purposeName);
        var payload = new PurposeRemovedEvent(purposeName);
        publishEvent(tenantId, ConsentEventTypes.PURPOSE_REMOVED,
            PurposeAggregate.AGGREGATE_TYPE, aggregateId, payload);
    }

    @Override
    public void updatePurposeCommand(String purposeName, String description,
                                     PurposeSnapshot snapshot) {
        Objects.requireNonNull(purposeName, "purposeName must not be null");
        String tenantId = TenantContext.require();
        UUID aggregateId = PurposeAggregate.deriveId(purposeName);
        var payload = new PurposeUpdatedEvent(purposeName, description, snapshot);
        publishEvent(tenantId, ConsentEventTypes.PURPOSE_UPDATED,
            PurposeAggregate.AGGREGATE_TYPE, aggregateId, payload);
    }

    @Override
    public void linkPurposeToRegulationCommand(String purposeName, String regulationName,
                                               String lawfulBasis, String onGranted,
                                               String onDenied, PurposeSnapshot snapshot) {
        Objects.requireNonNull(purposeName, "purposeName must not be null");
        Objects.requireNonNull(regulationName, "regulationName must not be null");
        String tenantId = TenantContext.require();
        UUID aggregateId = PurposeAggregate.deriveId(purposeName);
        var payload = new PurposeRegulationLinkedEvent(
            purposeName, regulationName, lawfulBasis, onGranted, onDenied, snapshot);
        publishEvent(tenantId, ConsentEventTypes.PURPOSE_REGULATION_LINKED,
            PurposeAggregate.AGGREGATE_TYPE, aggregateId, payload);
    }

    @Override
    public void unlinkPurposeFromRegulationCommand(String purposeName, String regulationName,
                                                   PurposeSnapshot snapshot) {
        Objects.requireNonNull(purposeName, "purposeName must not be null");
        Objects.requireNonNull(regulationName, "regulationName must not be null");
        String tenantId = TenantContext.require();
        UUID aggregateId = PurposeAggregate.deriveId(purposeName);
        var payload = new PurposeRegulationUnlinkedEvent(purposeName, regulationName, snapshot);
        publishEvent(tenantId, ConsentEventTypes.PURPOSE_REGULATION_UNLINKED,
            PurposeAggregate.AGGREGATE_TYPE, aggregateId, payload);
    }

    @Override
    public void addPurposePolicyCommand(String purposeName, String policyType,
                                        String fieldName, String entityName,
                                        String categoryName, PurposeSnapshot snapshot) {
        Objects.requireNonNull(purposeName, "purposeName must not be null");
        Objects.requireNonNull(policyType, "policyType must not be null");
        String tenantId = TenantContext.require();
        UUID aggregateId = PurposeAggregate.deriveId(purposeName);
        var payload = new PurposePolicyAddedEvent(
            purposeName, policyType, fieldName, entityName, categoryName, snapshot);
        publishEvent(tenantId, ConsentEventTypes.PURPOSE_POLICY_ADDED,
            PurposeAggregate.AGGREGATE_TYPE, aggregateId, payload);
    }

    @Override
    public void removePurposePolicyCommand(String purposeName, String policyType,
                                           String fieldName, String entityName,
                                           String categoryName, PurposeSnapshot snapshot) {
        Objects.requireNonNull(purposeName, "purposeName must not be null");
        Objects.requireNonNull(policyType, "policyType must not be null");
        String tenantId = TenantContext.require();
        UUID aggregateId = PurposeAggregate.deriveId(purposeName);
        var payload = new PurposePolicyRemovedEvent(
            purposeName, policyType, fieldName, entityName, categoryName, snapshot);
        publishEvent(tenantId, ConsentEventTypes.PURPOSE_POLICY_REMOVED,
            PurposeAggregate.AGGREGATE_TYPE, aggregateId, payload);
    }

    // -----------------------------------------------------------------------
    // Regulation Aggregate
    // -----------------------------------------------------------------------

    @Override
    public void registerRegulationCommand(String regulationName, String description) {
        Objects.requireNonNull(regulationName, "regulationName must not be null");
        String tenantId = TenantContext.require();
        UUID aggregateId = RegulationAggregate.deriveId(regulationName);
        var payload = new RegulationRegisteredEvent(regulationName, description);
        publishEvent(tenantId, ConsentEventTypes.REGULATION_REGISTERED,
            RegulationAggregate.AGGREGATE_TYPE, aggregateId, payload);
    }

    @Override
    public void removeRegulationCommand(String regulationName) {
        Objects.requireNonNull(regulationName, "regulationName must not be null");
        String tenantId = TenantContext.require();
        UUID aggregateId = RegulationAggregate.deriveId(regulationName);
        var payload = new RegulationRemovedEvent(regulationName);
        publishEvent(tenantId, ConsentEventTypes.REGULATION_REMOVED,
            RegulationAggregate.AGGREGATE_TYPE, aggregateId, payload);
    }

    @Override
    public void updateRegulationCommand(String regulationName, String description,
                                        RegulationSnapshot snapshot) {
        Objects.requireNonNull(regulationName, "regulationName must not be null");
        String tenantId = TenantContext.require();
        UUID aggregateId = RegulationAggregate.deriveId(regulationName);
        var payload = new RegulationUpdatedEvent(regulationName, description, snapshot);
        publishEvent(tenantId, ConsentEventTypes.REGULATION_UPDATED,
            RegulationAggregate.AGGREGATE_TYPE, aggregateId, payload);
    }

    @Override
    public void addRegulationJurisdictionCommand(String regulationName,
                                                 String jurisdictionName,
                                                 String description,
                                                 RegulationSnapshot snapshot) {
        Objects.requireNonNull(regulationName, "regulationName must not be null");
        Objects.requireNonNull(jurisdictionName, "jurisdictionName must not be null");
        String tenantId = TenantContext.require();
        UUID aggregateId = RegulationAggregate.deriveId(regulationName);
        var payload = new RegulationJurisdictionAddedEvent(
            regulationName, jurisdictionName, description, snapshot);
        publishEvent(tenantId, ConsentEventTypes.REGULATION_JURISDICTION_ADDED,
            RegulationAggregate.AGGREGATE_TYPE, aggregateId, payload);
    }

    @Override
    public void removeRegulationJurisdictionCommand(String regulationName,
                                                    String jurisdictionName,
                                                    RegulationSnapshot snapshot) {
        Objects.requireNonNull(regulationName, "regulationName must not be null");
        Objects.requireNonNull(jurisdictionName, "jurisdictionName must not be null");
        String tenantId = TenantContext.require();
        UUID aggregateId = RegulationAggregate.deriveId(regulationName);
        var payload = new RegulationJurisdictionRemovedEvent(regulationName, jurisdictionName, snapshot);
        publishEvent(tenantId, ConsentEventTypes.REGULATION_JURISDICTION_REMOVED,
            RegulationAggregate.AGGREGATE_TYPE, aggregateId, payload);
    }

    // -----------------------------------------------------------------------
    // Jurisdiction Aggregate
    // -----------------------------------------------------------------------

    @Override
    public void registerJurisdictionCommand(String jurisdictionName, String description) {
        Objects.requireNonNull(jurisdictionName, "jurisdictionName must not be null");
        String tenantId = TenantContext.require();
        UUID aggregateId = JurisdictionAggregate.deriveId(jurisdictionName);
        var payload = new JurisdictionRegisteredEvent(jurisdictionName, description);
        publishEvent(tenantId, ConsentEventTypes.JURISDICTION_REGISTERED,
            JurisdictionAggregate.AGGREGATE_TYPE, aggregateId, payload);
    }

    @Override
    public void updateJurisdictionCommand(String jurisdictionName, String description) {
        Objects.requireNonNull(jurisdictionName, "jurisdictionName must not be null");
        String tenantId = TenantContext.require();
        UUID aggregateId = JurisdictionAggregate.deriveId(jurisdictionName);
        var payload = new JurisdictionUpdatedEvent(jurisdictionName, description);
        publishEvent(tenantId, ConsentEventTypes.JURISDICTION_UPDATED,
            JurisdictionAggregate.AGGREGATE_TYPE, aggregateId, payload);
    }

    @Override
    public void removeJurisdictionCommand(String jurisdictionName) {
        Objects.requireNonNull(jurisdictionName, "jurisdictionName must not be null");
        String tenantId = TenantContext.require();
        UUID aggregateId = JurisdictionAggregate.deriveId(jurisdictionName);
        var payload = new JurisdictionRemovedEvent(jurisdictionName);
        publishEvent(tenantId, ConsentEventTypes.JURISDICTION_REMOVED,
            JurisdictionAggregate.AGGREGATE_TYPE, aggregateId, payload);
    }

    // -----------------------------------------------------------------------
    // Subject Aggregate (entity lifecycle)
    // -----------------------------------------------------------------------

    @Override
    public void registerSubjectCommand(String subjectName,
                                       String description) {
        Objects.requireNonNull(subjectName, "subjectName must not be null");
        String tenantId = TenantContext.require();
        UUID aggregateId = SubjectAggregate.deriveId(subjectName);
        var payload = new SubjectRegisteredEvent(subjectName, description);
        publishEvent(tenantId, ConsentEventTypes.SUBJECT_REGISTERED,
            SubjectAggregate.AGGREGATE_TYPE, aggregateId, payload);
    }

    @Override
    public void updateSubjectCommand(String subjectName, String description) {
        Objects.requireNonNull(subjectName, "subjectName must not be null");
        String tenantId = TenantContext.require();
        UUID aggregateId = SubjectAggregate.deriveId(subjectName);
        var payload = new SubjectUpdatedEvent(subjectName, description);
        publishEvent(tenantId, ConsentEventTypes.SUBJECT_UPDATED,
            SubjectAggregate.AGGREGATE_TYPE, aggregateId, payload);
    }

    @Override
    public void removeSubjectCommand(String subjectName) {
        Objects.requireNonNull(subjectName, "subjectName must not be null");
        String tenantId = TenantContext.require();
        UUID aggregateId = SubjectAggregate.deriveId(subjectName);
        var payload = new SubjectRemovedEvent(subjectName);
        publishEvent(tenantId, ConsentEventTypes.SUBJECT_REMOVED,
            SubjectAggregate.AGGREGATE_TYPE, aggregateId, payload);
    }

    // -----------------------------------------------------------------------
    // SubjectConsent Aggregate
    // -----------------------------------------------------------------------

    @Override
    public void grantConsentCommand(String subjectId, long policyId,
                                    String purposeName, String policyType,
                                    String fieldName, String entityName,
                                    String categoryName, String actor,
                                    String ipAddress, String userAgent,
                                    Instant expiresAt) {
        Objects.requireNonNull(subjectId, "subjectId must not be null");
        String tenantId = TenantContext.require();
        UUID aggregateId = SubjectConsentAggregate.deriveId(subjectId);
        var payload = new ConsentGrantedEvent(
            subjectId, policyId, purposeName, policyType,
            fieldName, entityName, categoryName,
            actor, ipAddress, userAgent, expiresAt);
        publishEvent(tenantId, ConsentEventTypes.CONSENT_GRANTED,
            SubjectConsentAggregate.AGGREGATE_TYPE, aggregateId, payload);
    }

    @Override
    public void revokeConsentCommand(String subjectId, long policyId,
                                     String purposeName, String policyType,
                                     String fieldName, String entityName,
                                     String categoryName, String actor,
                                     String ipAddress, String userAgent) {
        Objects.requireNonNull(subjectId, "subjectId must not be null");
        String tenantId = TenantContext.require();
        UUID aggregateId = SubjectConsentAggregate.deriveId(subjectId);
        var payload = new ConsentRevokedEvent(
            subjectId, policyId, purposeName, policyType,
            fieldName, entityName, categoryName,
            actor, ipAddress, userAgent);
        publishEvent(tenantId, ConsentEventTypes.CONSENT_REVOKED,
            SubjectConsentAggregate.AGGREGATE_TYPE, aggregateId, payload);
    }

    @Override
    public void expireConsentCommand(String subjectId, long policyId,
                                     String purposeName, String policyType,
                                     String fieldName, String entityName,
                                     String categoryName) {
        Objects.requireNonNull(subjectId, "subjectId must not be null");
        String tenantId = TenantContext.require();
        UUID aggregateId = SubjectConsentAggregate.deriveId(subjectId);
        var payload = new ConsentExpiredEvent(
            subjectId, policyId, purposeName, policyType,
            fieldName, entityName, categoryName);
        publishEvent(tenantId, ConsentEventTypes.CONSENT_EXPIRED,
            SubjectConsentAggregate.AGGREGATE_TYPE, aggregateId, payload);
    }

    @Override
    public void revokeAllConsentCommand(String subjectId, String actor,
                                        String ipAddress, String userAgent) {
        Objects.requireNonNull(subjectId, "subjectId must not be null");
        String tenantId = TenantContext.require();
        UUID aggregateId = SubjectConsentAggregate.deriveId(subjectId);
        var payload = new ConsentAllRevokedEvent(subjectId, actor, ipAddress, userAgent);
        publishEvent(tenantId, ConsentEventTypes.CONSENT_ALL_REVOKED,
            SubjectConsentAggregate.AGGREGATE_TYPE, aggregateId, payload);
        LOG.info("Published bulk revoke event for subject: {}", subjectId);
    }

    // -----------------------------------------------------------------------
    // DSAR Aggregate
    // -----------------------------------------------------------------------

    @Override
    public void deleteSubjectAuditCommand(String subjectId, String deletionRequestId,
                                          String actor, String ipAddress,
                                          String userAgent) {
        Objects.requireNonNull(subjectId, "subjectId must not be null");
        Objects.requireNonNull(deletionRequestId, "deletionRequestId must not be null");
        String tenantId = TenantContext.require();
        UUID aggregateId = DsarAggregate.deriveId(subjectId);
        var payload = new SubjectAuditDeletedEvent(
            subjectId, deletionRequestId, actor, ipAddress, userAgent);
        publishEvent(tenantId, ConsentEventTypes.SUBJECT_AUDIT_DELETED,
            DsarAggregate.AGGREGATE_TYPE, aggregateId, payload);
        LOG.info("Published DSAR audit deletion event for subject: {}", subjectId);
    }

    // -----------------------------------------------------------------------
    // Internal — event publishing helper
    // -----------------------------------------------------------------------

    private void publishEvent(
        String tenantId,
        String eventType,
        String aggregateType,
        UUID aggregateId,
        DomainEvent payload
    ) {
        AggregateKey key = new AggregateKey(aggregateId, aggregateType, tenantId);
        eventService.publishEvent(
            PublishDomainEventInput.of(tenantId, eventType, key, payload)
        );
    }

}
