package com.koostack.operio.platform.core.jooq;

import com.koostack.operio.core.PlatformConstants;
import com.koostack.operio.core.event.DomainEvent;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.event.models.RegisterEventTypeInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.platform.core.CoreCommandService;
import com.koostack.operio.core.platform.core.CoreEventTypes;
import com.koostack.operio.core.platform.core.aggregates.TenantAggregate;
import com.koostack.operio.core.platform.core.events.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

public class JooqCoreCommandService implements CoreCommandService<JooqCoreCommandConfig> {

    private static final Logger LOG = LoggerFactory.getLogger(JooqCoreCommandService.class);

    private EventService<?> eventService;

    @Override public String getName() { return "jooq-core-command"; }
    @Override public int getPriority() { return 100; }
    @Override public Class<JooqCoreCommandConfig> getConfigurationType() { return JooqCoreCommandConfig.class; }

    @Override
    public void configure(JooqCoreCommandConfig config) {
        this.eventService = config.eventService();
        registerEventTypes();
        LOG.info("JooqCoreCommandService configured");
    }

    // -- Commands -------------------------------------------------------------

    @Override
    public void createTenant(String tenantId, String displayName, String plan) {
        var payload = new TenantCreatedEvent(tenantId, displayName, plan);
        publishEvent(CoreEventTypes.TENANT_CREATED, tenantId, payload);
    }

    @Override
    public void updateTenantDisplayName(String tenantId, String displayName) {
        var payload = new TenantDisplayNameUpdatedEvent(tenantId, displayName);
        publishEvent(CoreEventTypes.TENANT_DISPLAY_NAME_UPDATED, tenantId, payload);
    }

    @Override
    public void changeTenantPlan(String tenantId, String newPlan) {
        var aggregate = getAggregate(tenantId);
        String oldPlan = aggregate != null ? aggregate.plan() : null;
        var payload = new TenantPlanChangedEvent(tenantId, oldPlan, newPlan);
        publishEvent(CoreEventTypes.TENANT_PLAN_CHANGED, tenantId, payload);
    }

    @Override
    public void suspendTenant(String tenantId, String reason) {
        var payload = new TenantSuspendedEvent(tenantId, reason);
        publishEvent(CoreEventTypes.TENANT_SUSPENDED, tenantId, payload);
    }

    @Override
    public void activateTenant(String tenantId) {
        var payload = new TenantActivatedEvent(tenantId);
        publishEvent(CoreEventTypes.TENANT_ACTIVATED, tenantId, payload);
    }

    @Override
    public void deleteTenant(String tenantId) {
        var payload = new TenantDeletedEvent(tenantId);
        publishEvent(CoreEventTypes.TENANT_DELETED, tenantId, payload);
    }

    @Override
    public void provisionTenant(String tenantId, String schemaPrefix, String redisAclUser) {
        var payload = new TenantProvisionedEvent(tenantId, schemaPrefix, redisAclUser);
        publishEvent(CoreEventTypes.TENANT_PROVISIONED, tenantId, payload);
    }

    @Override
    public void emitProvisioningStarted(String tenantId, String displayName, String plan) {
        var payload = new TenantProvisioningStartedEvent(tenantId, displayName, plan);
        publishEvent(CoreEventTypes.TENANT_PROVISIONING_STARTED, tenantId, payload);
    }

    // -- Internal helpers -----------------------------------------------------

    private TenantAggregate getAggregate(String tenantId) {
        UUID aggregateId = TenantAggregate.deriveId(tenantId);
        AggregateKey key = new AggregateKey(
            aggregateId, TenantAggregate.AGGREGATE_TYPE, PlatformConstants.PLATFORM_TENANT_ID);
        return eventService.getAggregate(key, TenantAggregate.class);
    }

    private void publishEvent(String eventType, String tenantId, DomainEvent payload) {
        UUID aggregateId = TenantAggregate.deriveId(tenantId);
        AggregateKey key = new AggregateKey(
            aggregateId, TenantAggregate.AGGREGATE_TYPE, PlatformConstants.PLATFORM_TENANT_ID);
        eventService.publishEvent(
            PublishDomainEventInput.of(PlatformConstants.PLATFORM_TENANT_ID, eventType, key, payload));
    }

    private void registerEventTypes() {
        register(CoreEventTypes.TENANT_CREATED, "Tenant Created", TenantCreatedEvent.class);
        register(CoreEventTypes.TENANT_DISPLAY_NAME_UPDATED, "Tenant Display Name Updated", TenantDisplayNameUpdatedEvent.class);
        register(CoreEventTypes.TENANT_PLAN_CHANGED, "Tenant Plan Changed", TenantPlanChangedEvent.class);
        register(CoreEventTypes.TENANT_SUSPENDED, "Tenant Suspended", TenantSuspendedEvent.class);
        register(CoreEventTypes.TENANT_ACTIVATED, "Tenant Activated", TenantActivatedEvent.class);
        register(CoreEventTypes.TENANT_DELETED, "Tenant Deleted", TenantDeletedEvent.class);
        register(CoreEventTypes.TENANT_PROVISIONED, "Tenant Provisioned", TenantProvisionedEvent.class);
        register(CoreEventTypes.TENANT_PROVISIONING_STARTED, "Tenant Provisioning Started", TenantProvisioningStartedEvent.class);
    }

    private <U> void register(String name, String displayName, Class<U> eventClass) {
        eventService.registerEventType(new RegisterEventTypeInput<>(name, displayName, eventClass));
    }
}
