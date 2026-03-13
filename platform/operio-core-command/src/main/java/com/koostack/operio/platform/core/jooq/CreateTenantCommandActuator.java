package com.koostack.operio.platform.core.jooq;

import com.koostack.operio.core.PlatformConstants;
import com.koostack.operio.core.cqrs.CommandActuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.exception.OperioServiceException;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.platform.core.CoreEventTypes;
import com.koostack.operio.core.platform.core.aggregates.TenantAggregate;
import com.koostack.operio.core.platform.core.events.TenantCreatedEvent;
import java.util.UUID;

/**
 * Actuator that handles the "create tenant" command.
 *
 * <p>Hydrates the {@link TenantAggregate} from the event store to enforce uniqueness — if an
 * aggregate already exists for the given tenant ID the command is rejected. On success, publishes a
 * {@link TenantCreatedEvent} to the platform event store.
 */
public class CreateTenantCommandActuator
        implements CommandActuator<CreateTenantCommandActuator.CreateTenantCommand, Void> {

    private final EventService<?> eventService;

    public CreateTenantCommandActuator(EventService<?> eventService) {
        this.eventService = eventService;
    }

    public record CreateTenantCommand(
            String tenantId, String displayName, String plan, String tenantType) {}

    @Override
    public Void execute(CreateTenantCommand command) {
        UUID aggregateId = TenantAggregate.deriveId(command.tenantId());
        AggregateKey key =
                new AggregateKey(
                        aggregateId,
                        TenantAggregate.AGGREGATE_TYPE,
                        PlatformConstants.PLATFORM_TENANT_ID);

        // Hydrate existing aggregate — reject if already present
        TenantAggregate existing = eventPublisher().hydrateAggregate(key, TenantAggregate.class);
        if (existing != null) {
            throw new OperioServiceException("Tenant '" + command.tenantId() + "' already exists");
        }

        var payload =
                new TenantCreatedEvent(
                        command.tenantId(),
                        command.displayName(),
                        command.plan(),
                        command.tenantType());
        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                PlatformConstants.PLATFORM_TENANT_ID,
                                CoreEventTypes.TENANT_CREATED,
                                key,
                                payload));
        return null;
    }

    @Override
    public EventService<?> eventPublisher() {
        return eventService;
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
