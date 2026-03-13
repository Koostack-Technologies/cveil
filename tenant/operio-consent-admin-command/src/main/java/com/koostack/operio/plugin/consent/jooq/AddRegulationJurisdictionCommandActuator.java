package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.cqrs.CommandActuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.core.tenant.consent.ConsentEventTypes;
import com.koostack.operio.core.tenant.consent.aggregates.RegulationAggregate;
import com.koostack.operio.core.tenant.consent.events.RegulationJurisdictionAddedEvent;
import com.koostack.operio.core.tenant.consent.events.RegulationSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Actuator that handles the "add regulation jurisdiction" command.
 *
 * <p>Hydrates the current {@link RegulationAggregate} from the event log to build the post-mutation
 * {@link RegulationSnapshot} with the new jurisdiction added.
 */
public class AddRegulationJurisdictionCommandActuator
        implements CommandActuator<
                AddRegulationJurisdictionCommandActuator.AddRegulationJurisdictionCommand, Void> {

    private final EventService<?> eventService;

    public AddRegulationJurisdictionCommandActuator(EventService<?> eventService) {
        this.eventService = eventService;
    }

    private static final Logger LOG =
            LoggerFactory.getLogger(AddRegulationJurisdictionCommandActuator.class);

    public record AddRegulationJurisdictionCommand(
            String regulationName, String jurisdictionName, String description) {}

    @Override
    public Void execute(AddRegulationJurisdictionCommand command) {
        Objects.requireNonNull(command.regulationName(), "regulationName must not be null");
        Objects.requireNonNull(command.jurisdictionName(), "jurisdictionName must not be null");

        String tenantId = TenantContext.require();
        UUID aggregateId = RegulationAggregate.deriveId(command.regulationName());
        AggregateKey key =
                new AggregateKey(aggregateId, RegulationAggregate.AGGREGATE_TYPE, tenantId);

        RegulationAggregate current =
                eventPublisher().hydrateAggregate(key, RegulationAggregate.class);

        List<String> jurisdictions = new ArrayList<>();
        String regDescription = null;
        if (current != null) {
            current.jurisdictions().forEach(j -> jurisdictions.add(j.jurisdictionName()));
            regDescription = current.description();
        }
        if (!jurisdictions.contains(command.jurisdictionName())) {
            jurisdictions.add(command.jurisdictionName());
        }
        var snapshot =
                new RegulationSnapshot(
                        command.regulationName(), regDescription, List.copyOf(jurisdictions));

        var payload =
                new RegulationJurisdictionAddedEvent(
                        command.regulationName(),
                        command.jurisdictionName(),
                        command.description(),
                        snapshot);
        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                tenantId,
                                ConsentEventTypes.REGULATION_JURISDICTION_ADDED,
                                key,
                                payload));

        LOG.info(
                "Regulation jurisdiction added: regulation={}, jurisdiction={}",
                command.regulationName(),
                command.jurisdictionName());
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
