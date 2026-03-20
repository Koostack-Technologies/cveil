package com.koostack.cveil.plugin.consent.jooq;

import com.koostack.cveil.core.cqrs.CommandActuator;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.event.models.PublishDomainEventInput;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.AggregateKey;
import com.koostack.cveil.core.models.TenantContext;
import com.koostack.cveil.core.tenant.consent.ConsentEventTypes;
import com.koostack.cveil.core.tenant.consent.aggregates.PurposeAggregate;
import com.koostack.cveil.core.tenant.consent.events.PurposePolicyAddedEvent;
import com.koostack.cveil.core.tenant.consent.events.PurposeSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Actuator that handles the "add purpose policy" command.
 *
 * <p>Hydrates the current {@link PurposeAggregate} from the event log to build the post-mutation
 * {@link PurposeSnapshot} with the new policy entry added.
 */
public class AddPurposePolicyCommandActuator
        implements CommandActuator<AddPurposePolicyCommandActuator.AddPurposePolicyCommand, Void> {

    private final EventService<?> eventService;

    public AddPurposePolicyCommandActuator(EventService<?> eventService) {
        this.eventService = eventService;
    }

    private static final Logger LOG =
            LoggerFactory.getLogger(AddPurposePolicyCommandActuator.class);

    public record AddPurposePolicyCommand(
            String purposeName,
            String policyType,
            String fieldName,
            String entityName,
            String categoryName) {}

    @Override
    public Void execute(AddPurposePolicyCommand command) {
        Objects.requireNonNull(command.purposeName(), "purposeName must not be null");
        Objects.requireNonNull(command.policyType(), "policyType must not be null");

        String tenantId = TenantContext.require();
        UUID aggregateId = PurposeAggregate.deriveId(tenantId, command.purposeName());
        AggregateKey key = new AggregateKey(aggregateId, PurposeAggregate.AGGREGATE_TYPE, tenantId);

        PurposeAggregate current = null;
        try {
            current = eventPublisher().hydrateAggregate(key, PurposeAggregate.class);
        } catch (Exception e) {
            LOG.warn(
                    "Could not hydrate purpose '{}' — snapshot will be partial: {}",
                    command.purposeName(),
                    e.getMessage());
            LOG.debug("Hydration failure details for purpose '{}':", command.purposeName(), e);
        }

        List<PurposeSnapshot.RegulationLinkSnapshot> regs =
                current != null
                        ? UpdatePurposeCommandActuator.toRegLinkSnapshots(current)
                        : List.of();
        List<PurposeSnapshot.PolicySnapshot> policies =
                new ArrayList<>(
                        current != null
                                ? UpdatePurposeCommandActuator.toPolicySnapshots(current)
                                : List.of());
        policies.add(
                new PurposeSnapshot.PolicySnapshot(
                        command.policyType(),
                        command.fieldName(),
                        command.entityName(),
                        command.categoryName()));

        var snapshot =
                new PurposeSnapshot(
                        command.purposeName(),
                        current != null ? current.description() : null,
                        regs,
                        List.copyOf(policies));

        var payload =
                new PurposePolicyAddedEvent(
                        command.purposeName(),
                        command.policyType(),
                        command.fieldName(),
                        command.entityName(),
                        command.categoryName(),
                        snapshot);
        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                tenantId, ConsentEventTypes.PURPOSE_POLICY_ADDED, key, payload));

        LOG.info(
                "Purpose policy added: purpose={}, policyType={}",
                command.purposeName(),
                command.policyType());
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
