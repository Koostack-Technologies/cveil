package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.core.tenant.consent.ConsentEventTypes;
import com.koostack.operio.core.tenant.consent.aggregates.PurposeAggregate;
import com.koostack.operio.core.tenant.consent.events.PurposeRegulationLinkedEvent;
import com.koostack.operio.core.tenant.consent.events.PurposeSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Actuator that handles the "link purpose to regulation" command.
 *
 * <p>Hydrates the current {@link PurposeAggregate} from the event log to build the post-mutation
 * {@link PurposeSnapshot} with the new regulation link added (or replaced if already present).
 */
public class LinkPurposeToRegulationCommandActuator
        implements Actuator<
                LinkPurposeToRegulationCommandActuator.LinkPurposeToRegulationCommand, Void> {

    private static final Logger LOG =
            LoggerFactory.getLogger(LinkPurposeToRegulationCommandActuator.class);

    public record LinkPurposeToRegulationCommand(
            String purposeName,
            String regulationName,
            String lawfulBasis,
            String onGranted,
            String onDenied) {}

    @Override
    public Void execute(EventService<?> publisher, LinkPurposeToRegulationCommand command) {
        Objects.requireNonNull(command.purposeName(), "purposeName must not be null");
        Objects.requireNonNull(command.regulationName(), "regulationName must not be null");

        String tenantId = TenantContext.require();
        UUID aggregateId = PurposeAggregate.deriveId(command.purposeName());
        AggregateKey key = new AggregateKey(aggregateId, PurposeAggregate.AGGREGATE_TYPE, tenantId);

        PurposeAggregate current = publisher.hydrateAggregate(key, PurposeAggregate.class);

        // Build regulation links — replace existing entry for this regulation
        List<PurposeSnapshot.RegulationLinkSnapshot> regs = new ArrayList<>();
        List<PurposeSnapshot.PolicySnapshot> policies = List.of();
        if (current != null) {
            current.regulationLinks().stream()
                    .filter(r -> !r.regulationName().equals(command.regulationName()))
                    .map(
                            r ->
                                    new PurposeSnapshot.RegulationLinkSnapshot(
                                            r.regulationName(),
                                            r.lawfulBasis(),
                                            r.onGranted(),
                                            r.onDenied()))
                    .forEach(regs::add);
            policies = UpdatePurposeCommandActuator.toPolicySnapshots(current);
        }
        regs.add(
                new PurposeSnapshot.RegulationLinkSnapshot(
                        command.regulationName(),
                        command.lawfulBasis(),
                        command.onGranted(),
                        command.onDenied()));

        var snapshot =
                new PurposeSnapshot(
                        command.purposeName(),
                        current != null ? current.description() : null,
                        List.copyOf(regs),
                        policies);

        var payload =
                new PurposeRegulationLinkedEvent(
                        command.purposeName(),
                        command.regulationName(),
                        command.lawfulBasis(),
                        command.onGranted(),
                        command.onDenied(),
                        snapshot);
        publisher.publishEvent(
                PublishDomainEventInput.of(
                        tenantId, ConsentEventTypes.PURPOSE_REGULATION_LINKED, key, payload));

        LOG.info(
                "Purpose regulation linked: purpose={}, regulation={}",
                command.purposeName(),
                command.regulationName());
        return null;
    }
}
