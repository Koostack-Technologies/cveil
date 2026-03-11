package com.koostack.operio.platform.iam.jooq;

import com.koostack.operio.core.PlatformConstants;
import com.koostack.operio.core.cqrs.CommandActuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.platform.events.OrganizationUpdatedEvent;
import com.koostack.operio.core.platform.iam.IamEventTypes;
import com.koostack.operio.plugin.platform.keycloak.KeycloakOrganizationService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Updates a Keycloak Organization's metadata, then publishes an {@link OrganizationUpdatedEvent}.
 */
public class UpdateOrganizationCommandActuator
        implements CommandActuator<
                UpdateOrganizationCommandActuator.UpdateOrganizationCommand,
                UpdateOrganizationCommandActuator.UpdateOrganizationResult> {

    private static final Logger LOG =
            LoggerFactory.getLogger(UpdateOrganizationCommandActuator.class);
    private static final String ORG_AGGREGATE_TYPE = "iam-organization";

    private final EventService<?> eventService;
    private final KeycloakOrganizationService orgService;

    public UpdateOrganizationCommandActuator(
            EventService<?> eventService, KeycloakOrganizationService orgService) {
        this.eventService = eventService;
        this.orgService = orgService;
    }

    public record UpdateOrganizationCommand(
            String orgId, String orgName, String displayName, String description) {}

    public record UpdateOrganizationResult(String orgId, String displayName) {}

    @Override
    public UpdateOrganizationResult execute(UpdateOrganizationCommand command) {
        orgService.updateOrganization(
                command.orgId(), command.displayName(), command.description());

        var payload =
                new OrganizationUpdatedEvent(
                        command.orgId(),
                        command.orgName(),
                        command.displayName(),
                        command.description());

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
                                IamEventTypes.ORGANIZATION_UPDATED,
                                key,
                                payload));

        LOG.info("Organization updated: orgId={}, orgName={}", command.orgId(), command.orgName());
        return new UpdateOrganizationResult(command.orgId(), command.displayName());
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
