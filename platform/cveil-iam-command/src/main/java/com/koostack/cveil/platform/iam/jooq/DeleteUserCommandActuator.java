package com.koostack.cveil.platform.iam.jooq;

import com.koostack.cveil.core.PlatformConstants;
import com.koostack.cveil.core.cqrs.CommandActuator;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.event.models.PublishDomainEventInput;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.AggregateKey;
import com.koostack.cveil.core.platform.events.UserDeletedEvent;
import com.koostack.cveil.core.platform.iam.IamEventTypes;
import java.util.UUID;
import org.keycloak.admin.client.Keycloak;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Actuator that deletes a Keycloak user by user ID. Used as a compensation step in the user
 * registration saga — best-effort: catches and logs exceptions, never throws.
 */
public class DeleteUserCommandActuator
        implements CommandActuator<
                DeleteUserCommandActuator.DeleteUserCommand,
                DeleteUserCommandActuator.DeleteUserResult> {

    private static final Logger LOG = LoggerFactory.getLogger(DeleteUserCommandActuator.class);
    private static final String USER_AGGREGATE_TYPE = "iam-user";

    private final EventService<?> eventService;
    private final Keycloak adminClient;

    public DeleteUserCommandActuator(EventService<?> eventService, Keycloak adminClient) {
        this.eventService = eventService;
        this.adminClient = adminClient;
    }

    public record DeleteUserCommand(String realmId, String keycloakUserId) {}

    public record DeleteUserResult(String keycloakUserId) {}

    @Override
    public DeleteUserResult execute(DeleteUserCommand command) {
        try {
            adminClient.realm(command.realmId()).users().delete(command.keycloakUserId());
        } catch (Exception e) {
            LOG.warn(
                    "Best-effort user deletion failed: realmId={}, kcId={}, error={}",
                    command.realmId(),
                    command.keycloakUserId(),
                    e.getMessage());
            return new DeleteUserResult(command.keycloakUserId());
        }

        try {
            var payload = new UserDeletedEvent(command.realmId(), command.keycloakUserId());
            UUID aggregateId = UUID.fromString(command.keycloakUserId());
            AggregateKey key =
                    new AggregateKey(
                            aggregateId, USER_AGGREGATE_TYPE, PlatformConstants.PLATFORM_TENANT_ID);

            eventPublisher()
                    .publishEvent(
                            PublishDomainEventInput.of(
                                    PlatformConstants.PLATFORM_TENANT_ID,
                                    IamEventTypes.USER_DELETED,
                                    key,
                                    payload));
        } catch (Exception e) {
            LOG.warn(
                    "Failed to publish UserDeletedEvent for kcId={}: {}",
                    command.keycloakUserId(),
                    e.getMessage());
        }

        LOG.info(
                "User deleted (compensation): realmId={}, kcId={}",
                command.realmId(),
                command.keycloakUserId());
        return new DeleteUserResult(command.keycloakUserId());
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
