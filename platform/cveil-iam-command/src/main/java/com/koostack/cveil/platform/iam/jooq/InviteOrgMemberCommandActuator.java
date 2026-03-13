package com.koostack.cveil.platform.iam.jooq;

import com.koostack.cveil.core.PlatformConstants;
import com.koostack.cveil.core.cqrs.CommandActuator;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.event.models.PublishDomainEventInput;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.AggregateKey;
import com.koostack.cveil.core.platform.events.OrganizationMemberInvitedEvent;
import com.koostack.cveil.core.platform.iam.IamEventTypes;
import com.koostack.cveil.plugin.platform.keycloak.KeycloakOrganizationService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Invites a user to a Keycloak Organization via the Organizations invite API, then publishes an
 * {@link OrganizationMemberInvitedEvent}.
 */
public class InviteOrgMemberCommandActuator
        implements CommandActuator<
                InviteOrgMemberCommandActuator.InviteOrgMemberCommand,
                InviteOrgMemberCommandActuator.InviteOrgMemberResult> {

    private static final Logger LOG = LoggerFactory.getLogger(InviteOrgMemberCommandActuator.class);
    private static final String ORG_AGGREGATE_TYPE = "iam-organization";

    private final EventService<?> eventService;
    private final KeycloakOrganizationService orgService;

    public InviteOrgMemberCommandActuator(
            EventService<?> eventService, KeycloakOrganizationService orgService) {
        this.eventService = eventService;
        this.orgService = orgService;
    }

    public record InviteOrgMemberCommand(
            String orgId, String orgName, String inviteeEmail, String inviterUserId) {}

    public record InviteOrgMemberResult(String orgId, String inviteeEmail) {}

    @Override
    public InviteOrgMemberResult execute(InviteOrgMemberCommand command) {
        orgService.inviteMember(command.orgId(), command.inviteeEmail());

        var payload =
                new OrganizationMemberInvitedEvent(
                        command.orgId(),
                        command.orgName(),
                        command.inviteeEmail(),
                        command.inviterUserId());

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
                                IamEventTypes.ORGANIZATION_MEMBER_INVITED,
                                key,
                                payload));

        LOG.info(
                "Organization member invited: orgId={}, orgName={}, inviteeEmail={}",
                command.orgId(),
                command.orgName(),
                command.inviteeEmail());
        return new InviteOrgMemberResult(command.orgId(), command.inviteeEmail());
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
