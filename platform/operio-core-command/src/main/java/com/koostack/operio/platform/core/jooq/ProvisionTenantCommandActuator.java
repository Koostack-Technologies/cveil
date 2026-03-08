package com.koostack.operio.platform.core.jooq;

import com.koostack.operio.core.PlatformConstants;
import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.platform.core.CoreEventTypes;
import com.koostack.operio.core.platform.core.aggregates.TenantAggregate;
import com.koostack.operio.core.platform.core.events.TenantProvisionedEvent;
import java.util.UUID;

/**
 * Actuator that handles the "provision tenant" command.
 *
 * <p>Publishes a {@link TenantProvisionedEvent} to the platform event store. Typically called by
 * the tenant-manager projection handler once Postgres schemas, Redis ACLs, and Flyway migrations
 * have succeeded.
 */
public class ProvisionTenantCommandActuator
        implements Actuator<ProvisionTenantCommandActuator.ProvisionTenantCommand, Void> {

    public record ProvisionTenantCommand(
            String tenantId, String schemaPrefix, String redisAclUser) {}

    @Override
    public Void execute(EventService<?> publisher, ProvisionTenantCommand command) {
        UUID aggregateId = TenantAggregate.deriveId(command.tenantId());
        AggregateKey key =
                new AggregateKey(
                        aggregateId,
                        TenantAggregate.AGGREGATE_TYPE,
                        PlatformConstants.PLATFORM_TENANT_ID);

        var payload =
                new TenantProvisionedEvent(
                        command.tenantId(), command.schemaPrefix(), command.redisAclUser());
        publisher.publishEvent(
                PublishDomainEventInput.of(
                        PlatformConstants.PLATFORM_TENANT_ID,
                        CoreEventTypes.TENANT_PROVISIONED,
                        key,
                        payload));
        return null;
    }
}
