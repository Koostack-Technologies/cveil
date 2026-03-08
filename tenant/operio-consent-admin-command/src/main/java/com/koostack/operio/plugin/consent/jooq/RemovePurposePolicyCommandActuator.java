package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.core.tenant.consent.ConsentEventTypes;
import com.koostack.operio.core.tenant.consent.aggregates.PurposeAggregate;
import com.koostack.operio.core.tenant.consent.events.PurposePolicyRemovedEvent;
import com.koostack.operio.core.tenant.consent.events.PurposeSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Actuator that handles the "remove purpose policy" command.
 *
 * <p>Hydrates the current {@link PurposeAggregate} from the event log to build the post-mutation
 * {@link PurposeSnapshot} with the target policy entry removed.
 */
public class RemovePurposePolicyCommandActuator
        implements Actuator<RemovePurposePolicyCommandActuator.RemovePurposePolicyCommand, Void> {

    private static final Logger LOG =
            LoggerFactory.getLogger(RemovePurposePolicyCommandActuator.class);

    public record RemovePurposePolicyCommand(
            String purposeName,
            String policyType,
            String fieldName,
            String entityName,
            String categoryName) {}

    @Override
    public Void execute(EventService<?> publisher, RemovePurposePolicyCommand command) {
        Objects.requireNonNull(command.purposeName(), "purposeName must not be null");
        Objects.requireNonNull(command.policyType(), "policyType must not be null");

        String tenantId = TenantContext.require();
        UUID aggregateId = PurposeAggregate.deriveId(command.purposeName());
        AggregateKey key =
                new AggregateKey(aggregateId, PurposeAggregate.AGGREGATE_TYPE, tenantId);

        PurposeAggregate current = publisher.hydrateAggregate(key, PurposeAggregate.class);

        List<PurposeSnapshot.RegulationLinkSnapshot> regs = List.of();
        List<PurposeSnapshot.PolicySnapshot> policies = new ArrayList<>();
        if (current != null) {
            regs = UpdatePurposeCommandActuator.toRegLinkSnapshots(current);
            current.policies().stream()
                    .filter(
                            p ->
                                    !p.matchesTarget(
                                            command.policyType(),
                                            command.fieldName(),
                                            command.entityName(),
                                            command.categoryName()))
                    .map(
                            p ->
                                    new PurposeSnapshot.PolicySnapshot(
                                            p.policyType(),
                                            p.fieldName(),
                                            p.entityName(),
                                            p.categoryName()))
                    .forEach(policies::add);
        }

        var snapshot =
                new PurposeSnapshot(
                        command.purposeName(),
                        current != null ? current.description() : null,
                        regs,
                        List.copyOf(policies));

        var payload =
                new PurposePolicyRemovedEvent(
                        command.purposeName(),
                        command.policyType(),
                        command.fieldName(),
                        command.entityName(),
                        command.categoryName(),
                        snapshot);
        publisher.publishEvent(
                PublishDomainEventInput.of(
                        tenantId, ConsentEventTypes.PURPOSE_POLICY_REMOVED, key, payload));

        LOG.info(
                "Purpose policy removed: purpose={}, policyType={}",
                command.purposeName(),
                command.policyType());
        return null;
    }
}

