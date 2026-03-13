package com.koostack.operio.platform.iam.jooq;

import com.koostack.operio.core.PlatformConstants;
import com.koostack.operio.core.cqrs.CommandActuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.platform.events.OrganizationMemberRemovedEvent;
import com.koostack.operio.core.platform.iam.IamEventTypes;
import com.koostack.operio.plugin.platform.keycloak.KeycloakOrganizationService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Removes a user from a Keycloak Organization. Calls the Organizations API first, then publishes an
 * {@link OrganizationMemberRemovedEvent}.
 */
public class RemoveOrgMemberCommandActuator
        implements CommandActuator<RemoveOrgMemberCommandActuator.RemoveOrgMemberCommand, Void> {

    private static final Logger LOG = LoggerFactory.getLogger(RemoveOrgMemberCommandActuator.class);
    private static final String ORG_AGGREGATE_TYPE = "iam-organization";

    private final EventService<?> eventService;
    private final KeycloakOrganizationService orgService;

    public RemoveOrgMemberCommandActuator(
            EventService<?> eventService, KeycloakOrganizationService orgService) {
        this.eventService = eventService;
        this.orgService = orgService;
    }

    public record RemoveOrgMemberCommand(String orgId, String orgName, String userId) {}

    @Override
    public Void execute(RemoveOrgMemberCommand command) {
        orgService.removeMember(command.orgId(), command.userId());

        var payload =
                new OrganizationMemberRemovedEvent(
                        command.orgId(), command.orgName(), command.userId());

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
                                IamEventTypes.ORGANIZATION_MEMBER_REMOVED,
                                key,
                                payload));

        LOG.info(
                "Member removed: orgId={}, orgName={}, userId={}",
                command.orgId(),
                command.orgName(),
                command.userId());
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
