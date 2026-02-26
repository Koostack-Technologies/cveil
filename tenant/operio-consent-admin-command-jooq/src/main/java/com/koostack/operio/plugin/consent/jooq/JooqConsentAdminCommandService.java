package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.data.consent.ConsentAdminCommandService;
import com.koostack.operio.core.data.consent.ConsentEventTypes;
import com.koostack.operio.core.data.consent.aggregates.CategoryAggregate;
import com.koostack.operio.core.data.consent.aggregates.ConsentPolicyAggregate;
import com.koostack.operio.core.data.consent.aggregates.ConsentPolicyAggregate.PolicyEntry;
import com.koostack.operio.core.data.consent.aggregates.EntityAggregate;
import com.koostack.operio.core.data.consent.aggregates.PurposeAggregate;
import com.koostack.operio.core.data.consent.aggregates.RegulationAggregate;
import com.koostack.operio.core.event.PlatformEventService;
import com.koostack.operio.core.event.models.PublishTransformInput;
import com.koostack.operio.core.event.models.RegisterEventTypeInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.models.AggregateRoot;
import com.koostack.operio.core.models.ConsentScope;
import com.koostack.operio.core.models.TenantContext;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Event-sourced {@link ConsentAdminCommandService} implementation.
 *
 * <p>All write operations publish domain events via {@link PlatformEventService}
 * instead of writing directly to the database. A separate projection is
 * responsible for materializing the event stream into the consent read model.
 */
public class JooqConsentAdminCommandService
    implements ConsentAdminCommandService<ConsentAdminCommandConfig>
{

    private static final Logger LOG = LoggerFactory.getLogger(
        JooqConsentAdminCommandService.class
    );

    private PlatformEventService<?> eventService;

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
        registerEventTypes();
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
        publishPolicyChange(subject, accessRequest, true, expiresAt,
            ConsentEventTypes.POLICY_GRANTED);
    }

    @Override
    public void revokeConsent(
        String subject,
        ConsentScope accessRequest,
        Instant expiresAt
    ) {
        publishPolicyChange(subject, accessRequest, false, expiresAt,
            ConsentEventTypes.POLICY_REVOKED);
    }

    @Override
    public void revokeAllConsent(String subject) {
        String tenantId = TenantContext.require();
        publishTransform(
            tenantId,
            ConsentEventTypes.POLICY_ALL_REVOKED,
            ConsentPolicyAggregate.AGGREGATE_TYPE,
            ConsentPolicyAggregate.deriveId(subject),
            () -> ConsentPolicyAggregate.create(tenantId, subject),
            ConsentPolicyAggregate::withAllRevoked,
            ConsentPolicyAggregate.class
        );
        LOG.info("Published bulk revoke event for subject: {}", subject);
    }

    @Override
    public int deleteAuditLogBySubject(String subject) {
        String tenantId = TenantContext.require();
        publishTransform(
            tenantId,
            ConsentEventTypes.POLICY_AUDIT_DELETED,
            ConsentPolicyAggregate.AGGREGATE_TYPE,
            ConsentPolicyAggregate.deriveId(subject),
            () -> ConsentPolicyAggregate.create(tenantId, subject),
            Function.identity(),
            ConsentPolicyAggregate.class
        );
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

        publishTransform(
            tenantId,
            ConsentEventTypes.ENTITY_FIELD_REGISTERED,
            EntityAggregate.AGGREGATE_TYPE,
            aggregateId,
            () -> EntityAggregate.create(tenantId, entityName),
            current -> current.withField(fieldName, description),
            EntityAggregate.class
        );
    }

    @Override
    public void registerCategory(String categoryName, String description) {
        String tenantId = TenantContext.require();
        UUID aggregateId = CategoryAggregate.deriveId(categoryName);

        publishTransform(
            tenantId,
            ConsentEventTypes.CATEGORY_REGISTERED,
            CategoryAggregate.AGGREGATE_TYPE,
            aggregateId,
            () -> CategoryAggregate.create(tenantId, categoryName, description),
            current -> current.withDescription(description),
            CategoryAggregate.class
        );
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

        publishTransform(
            tenantId,
            ConsentEventTypes.ENTITY_FIELD_CATEGORY_LINKED,
            EntityAggregate.AGGREGATE_TYPE,
            aggregateId,
            () -> EntityAggregate.create(tenantId, entityName),
            current -> current.withFieldCategoryLink(fieldName, categoryName),
            EntityAggregate.class
        );
    }

    // -----------------------------------------------------------------------
    // Schema registration — Purpose & Regulation
    // -----------------------------------------------------------------------

    @Override
    public void registerPurpose(String purposeName, String description) {
        String tenantId = TenantContext.require();
        UUID aggregateId = PurposeAggregate.deriveId(purposeName);

        publishTransform(
            tenantId,
            ConsentEventTypes.PURPOSE_REGISTERED,
            PurposeAggregate.AGGREGATE_TYPE,
            aggregateId,
            () -> PurposeAggregate.create(tenantId, purposeName, description),
            current -> current.withDescription(description),
            PurposeAggregate.class
        );
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

        publishTransform(
            tenantId,
            ConsentEventTypes.REGULATION_REGISTERED,
            RegulationAggregate.AGGREGATE_TYPE,
            aggregateId,
            () -> RegulationAggregate.create(
                tenantId, regulationName, fullName,
                jurisdiction, description, url
            ),
            current -> current.withDetails(fullName, jurisdiction, description, url),
            RegulationAggregate.class
        );
    }

    @Override
    public void linkPurposeToRegulation(
        String purposeName,
        String regulationName,
        String legalBasis
    ) {
        String tenantId = TenantContext.require();
        UUID aggregateId = PurposeAggregate.deriveId(purposeName);

        publishTransform(
            tenantId,
            ConsentEventTypes.PURPOSE_REGULATION_LINKED,
            PurposeAggregate.AGGREGATE_TYPE,
            aggregateId,
            () -> PurposeAggregate.create(tenantId, purposeName, null),
            current -> current.withRegulationLink(regulationName, legalBasis),
            PurposeAggregate.class
        );
    }

    // -----------------------------------------------------------------------
    // Internal — event publishing helpers
    // -----------------------------------------------------------------------

    private void publishPolicyChange(
        String subject,
        ConsentScope scope,
        boolean granted,
        Instant expiresAt,
        String eventType
    ) {
        String tenantId = TenantContext.require();
        UUID aggregateId = ConsentPolicyAggregate.deriveId(subject);

        var entry = new PolicyEntry(
            scope.purposeName(),
            scope.fieldName(),
            scope.entityName(),
            scope.category(),
            granted,
            expiresAt
        );

        publishTransform(
            tenantId,
            eventType,
            ConsentPolicyAggregate.AGGREGATE_TYPE,
            aggregateId,
            () -> ConsentPolicyAggregate.create(tenantId, subject),
            current -> current.withPolicy(entry),
            ConsentPolicyAggregate.class
        );
    }

    private <T extends AggregateRoot> void publishTransform(
        String tenantId,
        String eventType,
        String aggregateType,
        UUID aggregateId,
        Supplier<T> defaultState,
        Function<T, T> transform,
        Class<T> type
    ) {
        AggregateKey key = new AggregateKey(aggregateId, aggregateType, tenantId);
        eventService.publishTransform(
            PublishTransformInput.of(tenantId, eventType, key, defaultState, transform, type)
        );
    }

    // -----------------------------------------------------------------------
    // Internal — event type registration
    // -----------------------------------------------------------------------

    private void registerEventTypes() {
        register(ConsentEventTypes.POLICY_GRANTED,
            "Consent Policy Granted", ConsentPolicyAggregate.class);
        register(ConsentEventTypes.POLICY_REVOKED,
            "Consent Policy Revoked", ConsentPolicyAggregate.class);
        register(ConsentEventTypes.POLICY_ALL_REVOKED,
            "All Consent Policies Revoked", ConsentPolicyAggregate.class);
        register(ConsentEventTypes.POLICY_AUDIT_DELETED,
            "Consent Audit Log Deletion Requested", ConsentPolicyAggregate.class);
        register(ConsentEventTypes.ENTITY_FIELD_REGISTERED,
            "Consent Field Registered", EntityAggregate.class);
        register(ConsentEventTypes.ENTITY_FIELD_CATEGORY_LINKED,
            "Field-Category Link Created", EntityAggregate.class);
        register(ConsentEventTypes.CATEGORY_REGISTERED,
            "Consent Category Registered", CategoryAggregate.class);
        register(ConsentEventTypes.PURPOSE_REGISTERED,
            "Consent Purpose Registered", PurposeAggregate.class);
        register(ConsentEventTypes.PURPOSE_REGULATION_LINKED,
            "Purpose-Regulation Link Created", PurposeAggregate.class);
        register(ConsentEventTypes.REGULATION_REGISTERED,
            "Consent Regulation Registered", RegulationAggregate.class);
    }

    private <T> void register(
        String name, String displayName, Class<T> eventClass
    ) {
        eventService.registerEventType(
            new RegisterEventTypeInput<>(name, displayName, eventClass)
        );
    }
}
