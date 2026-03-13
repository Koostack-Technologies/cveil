package com.koostack.cveil.platform.iam.jooq;

import com.koostack.cveil.core.PlatformConstants;
import com.koostack.cveil.core.cqrs.CommandActuator;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.event.models.PublishDomainEventInput;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.AggregateKey;
import com.koostack.cveil.core.platform.events.OrganizationMemberAddedEvent;
import com.koostack.cveil.core.platform.iam.IamEventTypes;
import com.koostack.cveil.plugin.platform.keycloak.KeycloakOrganizationService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adds a user as a member of a Keycloak Organization. Calls the Organizations API first, then
 * publishes an {@link OrganizationMemberAddedEvent}.
 */
public class AddOrgMemberCommandActuator
        implements CommandActuator<
                AddOrgMemberCommandActuator.AddOrgMemberCommand,
                AddOrgMemberCommandActuator.AddOrgMemberResult> {

    private static final Logger LOG = LoggerFactory.getLogger(AddOrgMemberCommandActuator.class);
    private static final String ORG_AGGREGATE_TYPE = "iam-organization";

    private final EventService<?> eventService;
    private final KeycloakOrganizationService orgService;

    public AddOrgMemberCommandActuator(
            EventService<?> eventService, KeycloakOrganizationService orgService) {
        this.eventService = eventService;
        this.orgService = orgService;
    }

    public record AddOrgMemberCommand(
            String orgId, String orgName, String userId, String username) {}

    public record AddOrgMemberResult(String orgId, String userId) {}

    @Override
    public AddOrgMemberResult execute(AddOrgMemberCommand command) {
        orgService.addMember(command.orgId(), command.userId());

        var payload =
                new OrganizationMemberAddedEvent(
                        command.orgId(), command.orgName(), command.userId(), command.username());

        UUID aggregateId =
                UUID.nameUUIDFromBytes(
                        ("org:" + command.orgName()).getBytes(StandardCharsets.UTF_8));
        AggregateKey key =
                new AggregateKey(
                        aggregateId, ORG_AGGREGATE_TYPE, PlatformConstants.PLATFORM_TENANT_ID);

        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                PlatformConstants.PLATFORM_TENANT_ID,
                                IamEventTypes.ORGANIZATION_MEMBER_ADDED,
                                key,
                                payload));

        LOG.info(
                "Member added: orgId={}, orgName={}, userId={}",
                command.orgId(),
                command.orgName(),
                command.userId());
        return new AddOrgMemberResult(command.orgId(), command.userId());
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
