package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.data.consent.ConsentAdminCommandService;
import com.koostack.operio.core.data.consent.ConsentEventTypes;
import com.koostack.operio.core.data.consent.aggregates.CategoryAggregate;
import com.koostack.operio.core.data.consent.aggregates.ConsentPolicyAggregate;
import com.koostack.operio.core.data.consent.aggregates.EntityAggregate;
import com.koostack.operio.core.data.consent.aggregates.PurposeAggregate;
import com.koostack.operio.core.data.consent.aggregates.RegulationAggregate;
import com.koostack.operio.core.data.consent.events.*;
import com.koostack.operio.core.data.consent.hydration.CategoryHydrationRepository;
import com.koostack.operio.core.data.consent.hydration.EntityHydrationRepository;
import com.koostack.operio.core.data.consent.hydration.PurposeHydrationRepository;
import com.koostack.operio.core.event.DomainEvent;
import com.koostack.operio.core.event.TenantEventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.event.models.RegisterEventTypeInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.models.ConsentScope;
import com.koostack.operio.core.models.TenantContext;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Event-sourced {@link ConsentAdminCommandService} implementation.
 *
 * <p>All write operations publish semantic domain events via
 * {@link TenantEventService} instead of writing directly to the database.
 * A separate projection is responsible for materializing the event stream
 * into the consent read model.
 */
public class JooqConsentAdminCommandService
    implements ConsentAdminCommandService<ConsentAdminCommandConfig>
{

    private static final Logger LOG = LoggerFactory.getLogger(
        JooqConsentAdminCommandService.class
    );

    private TenantEventService<?> eventService;
    private EntityHydrationRepository entityHydrationRepo;
    private CategoryHydrationRepository categoryHydrationRepo;
    private PurposeHydrationRepository purposeHydrationRepo;

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
        this.entityHydrationRepo = config.entityHydrationRepo();
        this.categoryHydrationRepo = config.categoryHydrationRepo();
        this.purposeHydrationRepo = config.purposeHydrationRepo();
        registerEventTypes();
        if (entityHydrationRepo == null && categoryHydrationRepo == null
                && purposeHydrationRepo == null) {
            LOG.warn("JooqConsentAdminCommandService configured WITHOUT hydration repos "
                + "— entity/category/purpose validation is DISABLED");
        }
        LOG.info("JooqConsentAdminCommandService configured (event-sourced)");
    }

    @Override
    public boolean isReady() {
        return eventService != null && eventService.isReady();
    }

    // -----------------------------------------------------------------------
    // ConsentAdminCommandService — core consent methods
    // -----------------------------------------------------------------------

    @Override
    public void grantConsent(String subject, ConsentScope accessRequest) {
        grantConsent(subject, accessRequest, null);
    }

    @Override
    public void revokeConsent(String subject, ConsentScope accessRequest) {
        revokeConsent(subject, accessRequest, null);
    }

    @Override
    public void grantConsent(
        String subject,
        ConsentScope accessRequest,
        Instant expiresAt
    ) {
        String tenantId = TenantContext.require();
        ensureReferencedEntitiesExist(accessRequest);

        UUID aggregateId = ConsentPolicyAggregate.deriveId(subject);
        var payload = new PolicyGrantedEvent(
            subject,
            accessRequest.purposeName(),
            wildcardIfNull(accessRequest.fieldName()),
            wildcardIfNull(accessRequest.entityName()),
            wildcardIfNull(accessRequest.category()),
            true,
            expiresAt
        );
        publishEvent(tenantId, ConsentEventTypes.POLICY_GRANTED,
            ConsentPolicyAggregate.AGGREGATE_TYPE, aggregateId, payload);
    }

    @Override
    public void revokeConsent(
        String subject,
        ConsentScope accessRequest,
        Instant expiresAt
    ) {
        String tenantId = TenantContext.require();
        ensureReferencedEntitiesExist(accessRequest);

        UUID aggregateId = ConsentPolicyAggregate.deriveId(subject);
        var payload = new PolicyRevokedEvent(
            subject,
            accessRequest.purposeName(),
            wildcardIfNull(accessRequest.fieldName()),
            wildcardIfNull(accessRequest.entityName()),
            wildcardIfNull(accessRequest.category()),
            false,
            expiresAt
        );
        publishEvent(tenantId, ConsentEventTypes.POLICY_REVOKED,
            ConsentPolicyAggregate.AGGREGATE_TYPE, aggregateId, payload);
    }

    @Override
    public void revokeAllConsent(String subject) {
        String tenantId = TenantContext.require();
        UUID aggregateId = ConsentPolicyAggregate.deriveId(subject);
        var payload = new PolicyAllRevokedEvent(subject);
        publishEvent(tenantId, ConsentEventTypes.POLICY_ALL_REVOKED,
            ConsentPolicyAggregate.AGGREGATE_TYPE, aggregateId, payload);
        LOG.info("Published bulk revoke event for subject: {}", subject);
    }

    @Override
    public int deleteAuditLogBySubject(String subject) {
        String tenantId = TenantContext.require();
        UUID aggregateId = ConsentPolicyAggregate.deriveId(subject);
        var payload = new PolicyAuditDeletedEvent(subject);
        publishEvent(tenantId, ConsentEventTypes.POLICY_AUDIT_DELETED,
            ConsentPolicyAggregate.AGGREGATE_TYPE, aggregateId, payload);
        LOG.info(
            "Published audit-log deletion event for subject: {} (actual deletion is eventual)",
            subject
        );
        return -1;
    }

    // -----------------------------------------------------------------------
    // Schema registration — SensitiveDiscovery pipeline
    // -----------------------------------------------------------------------

    @Override
    public void registerField(
        String fieldName,
        String entityName,
        String description
    ) {
        String tenantId = TenantContext.require();
        String normalizedEntity = EntityAggregate.normalizeEntityName(entityName);
        UUID aggregateId = EntityAggregate.deriveId(normalizedEntity);
        var payload = new EntityFieldRegisteredEvent(normalizedEntity, fieldName, description);
        publishEvent(tenantId, ConsentEventTypes.ENTITY_FIELD_REGISTERED,
            EntityAggregate.AGGREGATE_TYPE, aggregateId, payload);
    }

    @Override
    public void registerCategory(String categoryName, String description) {
        String tenantId = TenantContext.require();
        UUID aggregateId = CategoryAggregate.deriveId(categoryName);
        var payload = new CategoryRegisteredEvent(categoryName, description);
        publishEvent(tenantId, ConsentEventTypes.CATEGORY_REGISTERED,
            CategoryAggregate.AGGREGATE_TYPE, aggregateId, payload);
    }

    @Override
    public void linkFieldToCategory(
        String fieldName,
        String entityName,
        String categoryName
    ) {
        String tenantId = TenantContext.require();
        String normalizedEntity = EntityAggregate.normalizeEntityName(entityName);
        UUID aggregateId = EntityAggregate.deriveId(normalizedEntity);
        var payload = new FieldCategoryLinkedEvent(normalizedEntity, fieldName, categoryName);
        publishEvent(tenantId, ConsentEventTypes.ENTITY_FIELD_CATEGORY_LINKED,
            EntityAggregate.AGGREGATE_TYPE, aggregateId, payload);
    }

    // -----------------------------------------------------------------------
    // Schema registration — Purpose & Regulation
    // -----------------------------------------------------------------------

    @Override
    public void registerPurpose(String purposeName, String description) {
        String tenantId = TenantContext.require();
        UUID aggregateId = PurposeAggregate.deriveId(purposeName);
        var payload = new PurposeRegisteredEvent(purposeName, description);
        publishEvent(tenantId, ConsentEventTypes.PURPOSE_REGISTERED,
            PurposeAggregate.AGGREGATE_TYPE, aggregateId, payload);
    }

    @Override
    public void registerRegulation(
        String regulationName,
        String fullName,
        String jurisdiction,
        String description,
        String url
    ) {
        String tenantId = TenantContext.require();
        UUID aggregateId = RegulationAggregate.deriveId(regulationName);
        var payload = new RegulationRegisteredEvent(
            regulationName, fullName, jurisdiction, description, url
        );
        publishEvent(tenantId, ConsentEventTypes.REGULATION_REGISTERED,
            RegulationAggregate.AGGREGATE_TYPE, aggregateId, payload);
    }

    @Override
    public void linkPurposeToRegulation(
        String purposeName,
        String regulationName,
        String legalBasis
    ) {
        String tenantId = TenantContext.require();
        UUID aggregateId = PurposeAggregate.deriveId(purposeName);
        var payload = new PurposeRegulationLinkedEvent(purposeName, regulationName, legalBasis);
        publishEvent(tenantId, ConsentEventTypes.PURPOSE_REGULATION_LINKED,
            PurposeAggregate.AGGREGATE_TYPE, aggregateId, payload);
    }

    // -----------------------------------------------------------------------
    // Internal — validation + auto-create
    // -----------------------------------------------------------------------

    /**
     * Validates that all entities referenced by the consent scope exist,
     * auto-creating them if they don't. Skipped when hydration repos are
     * not configured (backward-compatible). Wildcard and null values are
     * silently skipped — they represent "all" rather than a specific entity.
     */
    private void ensureReferencedEntitiesExist(ConsentScope accessRequest) {
        ensurePurposeExists(accessRequest.purposeName());
        if (accessRequest.isCategoryScoped()) {
            ensureCategoryExists(accessRequest.category());
        }
        if (accessRequest.isFieldScoped()) {
            ensureEntityAndFieldExist(accessRequest.entityName(), accessRequest.fieldName());
        }
    }

    private static boolean isWildcardOrBlank(String value) {
        return value == null || value.isBlank() || ConsentScope.WILDCARD.equals(value);
    }

    private void ensurePurposeExists(String purposeName) {
        if (purposeHydrationRepo == null || isWildcardOrBlank(purposeName)) return;
        PurposeAggregate existing = purposeHydrationRepo.findByName(purposeName);
        if (existing == null || existing.purposeName() == null) {
            LOG.info("Auto-creating purpose '{}' referenced by consent grant/revoke", purposeName);
            registerPurpose(purposeName, null);
        }
    }

    private void ensureCategoryExists(String categoryName) {
        if (categoryHydrationRepo == null || isWildcardOrBlank(categoryName)) return;
        CategoryAggregate existing = categoryHydrationRepo.findByName(categoryName);
        if (existing == null || existing.categoryName() == null) {
            LOG.info("Auto-creating category '{}' referenced by consent grant/revoke", categoryName);
            registerCategory(categoryName, null);
        }
    }

    private void ensureEntityAndFieldExist(String entityName, String fieldName) {
        if (entityHydrationRepo == null || isWildcardOrBlank(fieldName)) return;
        if (isWildcardOrBlank(entityName)) return;
        EntityAggregate existing = entityHydrationRepo.findByName(entityName);
        if (existing == null || existing.entityName() == null) {
            LOG.info("Auto-creating entity '{}' and field '{}' referenced by consent grant/revoke",
                entityName, fieldName);
            registerField(fieldName, entityName, null);
        } else {
            boolean fieldExists = existing.fields().stream()
                .anyMatch(f -> fieldName.equals(f.fieldName()));
            if (!fieldExists) {
                LOG.info("Auto-creating field '{}' on entity '{}' referenced by consent grant/revoke",
                    fieldName, entityName);
                registerField(fieldName, entityName, null);
            }
        }
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

    private static String wildcardIfNull(String value) {
        return value != null ? value : ConsentScope.WILDCARD;
    }

    // -----------------------------------------------------------------------
    // Internal — event type registration
    // -----------------------------------------------------------------------

    private void registerEventTypes() {
        register(ConsentEventTypes.POLICY_GRANTED,
            "Consent Policy Granted", PolicyGrantedEvent.class);
        register(ConsentEventTypes.POLICY_REVOKED,
            "Consent Policy Revoked", PolicyRevokedEvent.class);
        register(ConsentEventTypes.POLICY_ALL_REVOKED,
            "All Consent Policies Revoked", PolicyAllRevokedEvent.class);
        register(ConsentEventTypes.POLICY_AUDIT_DELETED,
            "Consent Audit Log Deletion Requested", PolicyAuditDeletedEvent.class);
        register(ConsentEventTypes.ENTITY_FIELD_REGISTERED,
            "Consent Field Registered", EntityFieldRegisteredEvent.class);
        register(ConsentEventTypes.ENTITY_FIELD_CATEGORY_LINKED,
            "Field-Category Link Created", FieldCategoryLinkedEvent.class);
        register(ConsentEventTypes.CATEGORY_REGISTERED,
            "Consent Category Registered", CategoryRegisteredEvent.class);
        register(ConsentEventTypes.PURPOSE_REGISTERED,
            "Consent Purpose Registered", PurposeRegisteredEvent.class);
        register(ConsentEventTypes.PURPOSE_REGULATION_LINKED,
            "Purpose-Regulation Link Created", PurposeRegulationLinkedEvent.class);
        register(ConsentEventTypes.REGULATION_REGISTERED,
            "Consent Regulation Registered", RegulationRegisteredEvent.class);

        // Delete / unlink event types
        register(ConsentEventTypes.ENTITY_DELETED,
            "Consent Entity Deleted", EntityDeletedEvent.class);
        register(ConsentEventTypes.ENTITY_FIELD_REMOVED,
            "Consent Entity Field Removed", EntityFieldRemovedEvent.class);
        register(ConsentEventTypes.ENTITY_FIELD_CATEGORY_UNLINKED,
            "Field-Category Link Removed", FieldCategoryUnlinkedEvent.class);
        register(ConsentEventTypes.CATEGORY_DELETED,
            "Consent Category Deleted", CategoryDeletedEvent.class);
        register(ConsentEventTypes.PURPOSE_DELETED,
            "Consent Purpose Deleted", PurposeDeletedEvent.class);
        register(ConsentEventTypes.PURPOSE_REGULATION_UNLINKED,
            "Purpose-Regulation Link Removed", PurposeRegulationUnlinkedEvent.class);
        register(ConsentEventTypes.REGULATION_DELETED,
            "Consent Regulation Deleted", RegulationDeletedEvent.class);
    }

    private <T> void register(
        String name, String displayName, Class<T> eventClass
    ) {
        eventService.registerEventType(
            new RegisterEventTypeInput<>(name, displayName, eventClass)
        );
    }
}
