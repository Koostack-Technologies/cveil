package com.koostack.operio.platform.iam.jooq;

import com.koostack.operio.core.PlatformConstants;
import com.koostack.operio.core.cqrs.CommandActuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.platform.events.OrganizationDeletedEvent;
import com.koostack.operio.core.platform.iam.IamEventTypes;
import com.koostack.operio.plugin.platform.keycloak.KeycloakOrganizationService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deletes a Keycloak Organization (tenant). Calls the Organizations API first, then publishes an
 * {@link OrganizationDeletedEvent}.
 */
public class DeleteOrganizationCommandActuator
        implements CommandActuator<
                DeleteOrganizationCommandActuator.DeleteOrganizationCommand, Void> {

    private static final Logger LOG =
            LoggerFactory.getLogger(DeleteOrganizationCommandActuator.class);
    private static final String ORG_AGGREGATE_TYPE = "iam-organization";

    private final EventService<?> eventService;
    private final KeycloakOrganizationService orgService;

    public DeleteOrganizationCommandActuator(
            EventService<?> eventService, KeycloakOrganizationService orgService) {
        this.eventService = eventService;
        this.orgService = orgService;
    }

    public record DeleteOrganizationCommand(String orgId, String orgName) {}

    @Override
    public Void execute(DeleteOrganizationCommand command) {
        orgService.deleteOrganization(command.orgId());

        var payload = new OrganizationDeletedEvent(command.orgId(), command.orgName());

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
                                IamEventTypes.ORGANIZATION_DELETED,
                                key,
                                payload));

        LOG.info("Organization deleted: orgId={}, orgName={}", command.orgId(), command.orgName());
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
