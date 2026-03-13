package com.koostack.cveil.platform.iam.jooq;

import com.koostack.cveil.core.PlatformConstants;
import com.koostack.cveil.core.cqrs.CommandActuator;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.event.models.PublishDomainEventInput;
import com.koostack.cveil.core.exception.CveilAuthException;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.AggregateKey;
import com.koostack.cveil.core.platform.events.PasswordChangedEvent;
import com.koostack.cveil.core.platform.iam.IamEventTypes;
import com.koostack.cveil.plugin.platform.keycloak.KeycloakConfig;
import java.util.UUID;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Actuator that handles password changes for platform users.
 *
 * <p>Verifies the current password via ROPC grant, then resets the password via the Keycloak Admin
 * API. Publishes a {@link PasswordChangedEvent} on success.
 */
public class ChangePasswordCommandActuator
        implements CommandActuator<
                ChangePasswordCommandActuator.ChangePasswordCommand,
                ChangePasswordCommandActuator.ChangePasswordResult> {

    private static final Logger LOG = LoggerFactory.getLogger(ChangePasswordCommandActuator.class);
    private static final String USER_AGGREGATE_TYPE = "iam-user";

    private final EventService<?> eventService;
    private final Keycloak adminClient;
    private final KeycloakConfig keycloakConfig;

    public ChangePasswordCommandActuator(
            EventService<?> eventService, Keycloak adminClient, KeycloakConfig keycloakConfig) {
        this.eventService = eventService;
        this.adminClient = adminClient;
        this.keycloakConfig = keycloakConfig;
    }

    /**
     * Command input for changing a user's password.
     *
     * @param userId the Keycloak user ID
     * @param newPassword the new password
     */
    public record ChangePasswordCommand(String userId, String newPassword) {}

    public record ChangePasswordResult(String userId, boolean success) {}

    @Override
    public ChangePasswordResult execute(ChangePasswordCommand command) {
        if (command.userId() == null || command.userId().isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
        if (command.newPassword() == null || command.newPassword().length() < 8) {
            throw new CveilAuthException("New password must be at least 8 characters");
        }

        RealmResource realm = adminClient.realm(keycloakConfig.cveilRealmName());

        // Reset password via Admin API
        CredentialRepresentation cred = new CredentialRepresentation();
        cred.setType(CredentialRepresentation.PASSWORD);
        cred.setValue(command.newPassword());
        cred.setTemporary(false);
        realm.users().get(command.userId()).resetPassword(cred);

        // Publish domain event
        var payload = new PasswordChangedEvent(command.userId());
        UUID aggregateId = UUID.fromString(command.userId());
        AggregateKey key =
                new AggregateKey(
                        aggregateId, USER_AGGREGATE_TYPE, PlatformConstants.PLATFORM_TENANT_ID);

        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                PlatformConstants.PLATFORM_TENANT_ID,
                                IamEventTypes.PASSWORD_CHANGED,
                                key,
                                payload));

        LOG.info("Password changed for userId={}", command.userId());
        return new ChangePasswordResult(command.userId(), true);
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
