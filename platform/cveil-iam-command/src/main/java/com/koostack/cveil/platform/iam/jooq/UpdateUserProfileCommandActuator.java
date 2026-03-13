package com.koostack.cveil.platform.iam.jooq;

import com.koostack.cveil.core.PlatformConstants;
import com.koostack.cveil.core.cqrs.CommandActuator;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.event.models.PublishDomainEventInput;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.AggregateKey;
import com.koostack.cveil.core.platform.events.UserProfileUpdatedEvent;
import com.koostack.cveil.core.platform.iam.IamEventTypes;
import com.koostack.cveil.plugin.platform.keycloak.KeycloakConfig;
import java.util.UUID;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Actuator that handles user profile updates (display name, email) in Keycloak.
 *
 * <p>Updates the user representation in Keycloak first (side-effect), then publishes a {@link
 * UserProfileUpdatedEvent}.
 */
public class UpdateUserProfileCommandActuator
        implements CommandActuator<
                UpdateUserProfileCommandActuator.UpdateUserProfileCommand,
                UpdateUserProfileCommandActuator.UpdateUserProfileResult> {

    private static final Logger LOG =
            LoggerFactory.getLogger(UpdateUserProfileCommandActuator.class);
    private static final String USER_AGGREGATE_TYPE = "iam-user";

    private final EventService<?> eventService;
    private final Keycloak adminClient;
    private final KeycloakConfig keycloakConfig;

    public UpdateUserProfileCommandActuator(
            EventService<?> eventService, Keycloak adminClient, KeycloakConfig keycloakConfig) {
        this.eventService = eventService;
        this.adminClient = adminClient;
        this.keycloakConfig = keycloakConfig;
    }

    /**
     * Command input for updating a user's profile.
     *
     * @param userId the Keycloak user ID (JWT sub claim)
     * @param fullName new full name (nullable — no change if null)
     * @param email new email (nullable — no change if null)
     */
    public record UpdateUserProfileCommand(String userId, String fullName, String email) {}

    public record UpdateUserProfileResult(String userId, String fullName, String email) {}

    @Override
    public UpdateUserProfileResult execute(UpdateUserProfileCommand command) {
        if (command.userId() == null || command.userId().isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }

        RealmResource realm = adminClient.realm(keycloakConfig.cveilRealmName());
        UserRepresentation user = realm.users().get(command.userId()).toRepresentation();
        if (user == null) {
            throw new IllegalArgumentException("User not found: " + command.userId());
        }

        boolean changed = false;
        if (command.fullName() != null) {
            String[] parts = command.fullName().split("\\s+", 2);
            user.setFirstName(parts[0]);
            user.setLastName(parts.length > 1 ? parts[1] : "");
            changed = true;
        }
        if (command.email() != null && !command.email().equals(user.getEmail())) {
            user.setEmail(command.email());
            user.setUsername(command.email());
            changed = true;
        }

        if (changed) {
            realm.users().get(command.userId()).update(user);
        }

        // Publish domain event
        var payload =
                new UserProfileUpdatedEvent(command.userId(), command.fullName(), command.email());
        UUID aggregateId = UUID.fromString(command.userId());
        AggregateKey key =
                new AggregateKey(
                        aggregateId, USER_AGGREGATE_TYPE, PlatformConstants.PLATFORM_TENANT_ID);

        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                PlatformConstants.PLATFORM_TENANT_ID,
                                IamEventTypes.USER_PROFILE_UPDATED,
                                key,
                                payload));

        LOG.info("User profile updated: userId={}", command.userId());

        return new UpdateUserProfileResult(
                command.userId(),
                command.fullName() != null ? command.fullName() : buildFullName(user),
                command.email() != null ? command.email() : user.getEmail());
    }

    private String buildFullName(UserRepresentation user) {
        String first = user.getFirstName();
        String last = user.getLastName();
        if (first == null && last == null) return null;
        if (first == null) return last;
        if (last == null) return first;
        return first + " " + last;
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
