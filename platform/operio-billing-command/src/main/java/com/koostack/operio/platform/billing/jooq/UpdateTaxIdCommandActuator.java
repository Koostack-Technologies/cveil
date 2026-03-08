package com.koostack.operio.platform.billing.jooq;

import com.koostack.operio.core.PlatformConstants;
import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.platform.billing.BillingEventTypes;
import com.koostack.operio.core.platform.billing.aggregates.BillingProfileAggregate;
import com.koostack.operio.core.platform.billing.events.BillingTaxIdUpdatedEvent;
import java.util.UUID;

/**
 * Actuator that handles the "update tax ID" command.
 *
 * <p>Publishes a {@link BillingTaxIdUpdatedEvent} to the platform event store.
 */
public class UpdateTaxIdCommandActuator
        implements Actuator<UpdateTaxIdCommandActuator.UpdateTaxIdCommand, Void> {

    public record UpdateTaxIdCommand(String tenantId, String taxId) {}

    @Override
    public Void execute(EventService<?> publisher, UpdateTaxIdCommand command) {
        UUID aggregateId = BillingProfileAggregate.deriveId(command.tenantId());
        AggregateKey key =
                new AggregateKey(
                        aggregateId,
                        BillingProfileAggregate.AGGREGATE_TYPE,
                        PlatformConstants.PLATFORM_TENANT_ID);

        var payload = new BillingTaxIdUpdatedEvent(command.tenantId(), command.taxId());
        publisher.publishEvent(
                PublishDomainEventInput.of(
                        PlatformConstants.PLATFORM_TENANT_ID,
                        BillingEventTypes.BILLING_TAX_ID_UPDATED,
                        key,
                        payload));
        return null;
    }
}
