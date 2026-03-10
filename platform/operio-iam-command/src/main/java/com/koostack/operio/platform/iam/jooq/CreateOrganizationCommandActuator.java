package com.koostack.operio.platform.iam.jooq;

import com.koostack.operio.core.PlatformConstants;
import com.koostack.operio.core.cqrs.CommandActuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.platform.events.OrganizationCreatedEvent;
import com.koostack.operio.core.platform.iam.IamEventTypes;
import com.koostack.operio.plugin.platform.keycloak.KeycloakOrganizationService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates a Keycloak Organization (tenant). Calls the Organizations API first, then publishes an
 * {@link OrganizationCreatedEvent} to the event store.
 */
public class CreateOrganizationCommandActuator
        implements CommandActuator<
                CreateOrganizationCommandActuator.CreateOrganizationCommand,
                CreateOrganizationCommandActuator.CreateOrganizationResult> {

    private static final Logger LOG =
            LoggerFactory.getLogger(CreateOrganizationCommandActuator.class);
    private static final String ORG_AGGREGATE_TYPE = "iam-organization";

    private final EventService<?> eventService;
    private final KeycloakOrganizationService orgService;

    public CreateOrganizationCommandActuator(
            EventService<?> eventService, KeycloakOrganizationService orgService) {
        this.eventService = eventService;
        this.orgService = orgService;
    }

    public record CreateOrganizationCommand(String orgName, String displayName) {}

    public record CreateOrganizationResult(String orgId, String orgName) {}

    @Override
    public CreateOrganizationResult execute(CreateOrganizationCommand command) {
        if (command.orgName() == null || command.orgName().isBlank()) {
            throw new IllegalArgumentException("orgName is required");
        }

        String orgId = orgService.createOrganization(command.orgName(), command.displayName());

        var payload = new OrganizationCreatedEvent(orgId, command.orgName(), command.displayName());

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
                                IamEventTypes.ORGANIZATION_CREATED,
                                key,
                                payload));

        LOG.info("Organization created: name={}, kcId={}", command.orgName(), orgId);
        return new CreateOrganizationResult(orgId, command.orgName());
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
