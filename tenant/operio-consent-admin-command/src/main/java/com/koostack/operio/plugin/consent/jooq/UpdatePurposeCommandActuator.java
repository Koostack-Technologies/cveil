package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.cqrs.CommandActuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.core.tenant.consent.ConsentEventTypes;
import com.koostack.operio.core.tenant.consent.aggregates.PurposeAggregate;
import com.koostack.operio.core.tenant.consent.events.PurposeSnapshot;
import com.koostack.operio.core.tenant.consent.events.PurposeUpdatedEvent;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Actuator that handles the "update purpose" command.
 *
 * <p>Hydrates the current {@link PurposeAggregate} from the event log to build the {@link
 * PurposeSnapshot} (preserving existing regulation links and policies).
 */
public class UpdatePurposeCommandActuator
        implements CommandActuator<UpdatePurposeCommandActuator.UpdatePurposeCommand, Void> {

    private final EventService<?> eventService;

    public UpdatePurposeCommandActuator(EventService<?> eventService) {
        this.eventService = eventService;
    }

    private static final Logger LOG = LoggerFactory.getLogger(UpdatePurposeCommandActuator.class);

    public record UpdatePurposeCommand(String purposeName, String description) {}

    @Override
    public Void execute(UpdatePurposeCommand command) {
        Objects.requireNonNull(command.purposeName(), "purposeName must not be null");
        String tenantId = TenantContext.require();
        UUID aggregateId = PurposeAggregate.deriveId(command.purposeName());
        AggregateKey key = new AggregateKey(aggregateId, PurposeAggregate.AGGREGATE_TYPE, tenantId);

        PurposeAggregate current = eventPublisher().hydrateAggregate(key, PurposeAggregate.class);
        List<PurposeSnapshot.RegulationLinkSnapshot> regs =
                current != null ? toRegLinkSnapshots(current) : List.of();
        List<PurposeSnapshot.PolicySnapshot> policies =
                current != null ? toPolicySnapshots(current) : List.of();
        var snapshot =
                new PurposeSnapshot(command.purposeName(), command.description(), regs, policies);

        var payload =
                new PurposeUpdatedEvent(command.purposeName(), command.description(), snapshot);
        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                tenantId, ConsentEventTypes.PURPOSE_UPDATED, key, payload));
        LOG.info("Purpose updated: {}", command.purposeName());
        return null;
    }

    static List<PurposeSnapshot.RegulationLinkSnapshot> toRegLinkSnapshots(PurposeAggregate agg) {
        return agg.regulationLinks().stream()
                .map(
                        r ->
                                new PurposeSnapshot.RegulationLinkSnapshot(
                                        r.regulationName(),
                                        r.lawfulBasis(),
                                        r.onGranted(),
                                        r.onDenied()))
                .toList();
    }

    static List<PurposeSnapshot.PolicySnapshot> toPolicySnapshots(PurposeAggregate agg) {
        return agg.policies().stream()
                .map(
                        p ->
                                new PurposeSnapshot.PolicySnapshot(
                                        p.policyType(),
                                        p.fieldName(),
                                        p.entityName(),
                                        p.categoryName()))
                .toList();
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
