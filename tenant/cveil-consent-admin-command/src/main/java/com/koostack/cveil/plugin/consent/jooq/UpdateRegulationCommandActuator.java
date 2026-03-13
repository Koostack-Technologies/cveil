package com.koostack.cveil.plugin.consent.jooq;

import com.koostack.cveil.core.cqrs.CommandActuator;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.event.models.PublishDomainEventInput;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.AggregateKey;
import com.koostack.cveil.core.models.TenantContext;
import com.koostack.cveil.core.tenant.consent.ConsentEventTypes;
import com.koostack.cveil.core.tenant.consent.aggregates.RegulationAggregate;
import com.koostack.cveil.core.tenant.consent.events.RegulationSnapshot;
import com.koostack.cveil.core.tenant.consent.events.RegulationUpdatedEvent;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Actuator that handles the "update regulation" command.
 *
 * <p>Hydrates the current {@link RegulationAggregate} from the event log to build the {@link
 * RegulationSnapshot} (preserving existing jurisdiction assignments).
 */
public class UpdateRegulationCommandActuator
        implements CommandActuator<UpdateRegulationCommandActuator.UpdateRegulationCommand, Void> {

    private final EventService<?> eventService;

    public UpdateRegulationCommandActuator(EventService<?> eventService) {
        this.eventService = eventService;
    }

    private static final Logger LOG =
            LoggerFactory.getLogger(UpdateRegulationCommandActuator.class);

    public record UpdateRegulationCommand(String regulationName, String description) {}

    @Override
    public Void execute(UpdateRegulationCommand command) {
        Objects.requireNonNull(command.regulationName(), "regulationName must not be null");
        String tenantId = TenantContext.require();
        UUID aggregateId = RegulationAggregate.deriveId(command.regulationName());
        AggregateKey key =
                new AggregateKey(aggregateId, RegulationAggregate.AGGREGATE_TYPE, tenantId);

        RegulationAggregate current =
                eventPublisher().hydrateAggregate(key, RegulationAggregate.class);
        List<String> jurisdictions =
                current != null
                        ? current.jurisdictions().stream()
                                .map(RegulationAggregate.JurisdictionEntry::jurisdictionName)
                                .toList()
                        : List.of();
        var snapshot =
                new RegulationSnapshot(
                        command.regulationName(), command.description(), jurisdictions);

        var payload =
                new RegulationUpdatedEvent(
                        command.regulationName(), command.description(), snapshot);
        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                tenantId, ConsentEventTypes.REGULATION_UPDATED, key, payload));
        LOG.info("Regulation updated: {}", command.regulationName());
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
