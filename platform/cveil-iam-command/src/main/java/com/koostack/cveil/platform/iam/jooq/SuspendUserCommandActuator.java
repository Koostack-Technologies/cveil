package com.koostack.cveil.platform.iam.jooq;

import com.koostack.cveil.core.PlatformConstants;
import com.koostack.cveil.core.cqrs.CommandActuator;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.event.models.PublishDomainEventInput;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.AggregateKey;
import com.koostack.cveil.core.platform.events.UserSuspendedEvent;
import com.koostack.cveil.core.platform.iam.IamEventTypes;
import java.util.List;
import java.util.UUID;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Actuator that handles the "suspend user" command for platform IAM.
 *
 * <p>Resolves the Keycloak user from {@code emailOrUsername}, disables them, then publishes a
 * {@link UserSuspendedEvent}. If the Keycloak call fails the event is never recorded.
 */
public class SuspendUserCommandActuator
        implements CommandActuator<SuspendUserCommandActuator.SuspendUserCommand, Void> {

    private static final Logger LOG = LoggerFactory.getLogger(SuspendUserCommandActuator.class);
    private static final String USER_AGGREGATE_TYPE = "iam-user";

    private final Keycloak adminClient;

    private final EventService<?> eventService;

    public SuspendUserCommandActuator(EventService<?> eventService, Keycloak adminClient) {
        this.eventService = eventService;
        this.adminClient = adminClient;
    }

    /**
     * Command input for suspending a platform user.
     *
     * @param realmId the Keycloak realm
     * @param emailOrUsername the user's email or username
     */
    public record SuspendUserCommand(String realmId, String emailOrUsername) {}

    @Override
    public Void execute(SuspendUserCommand command) {
        RealmResource realm = adminClient.realm(command.realmId());

        // Resolve username/email → Keycloak user
        UserRepresentation kcUser = resolveKeycloakUser(realm, command.emailOrUsername());

        // Keycloak side-effect
        kcUser.setEnabled(false);
        realm.users().get(kcUser.getId()).update(kcUser);

        // Publish domain event — Keycloak user ID is the aggregate identity
        var payload = new UserSuspendedEvent(command.realmId(), kcUser.getId());
        UUID aggregateId = UUID.fromString(kcUser.getId());
        AggregateKey key =
                new AggregateKey(
                        aggregateId, USER_AGGREGATE_TYPE, PlatformConstants.PLATFORM_TENANT_ID);

        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                PlatformConstants.PLATFORM_TENANT_ID,
                                IamEventTypes.USER_SUSPENDED,
                                key,
                                payload));

        LOG.info(
                "User suspended: realmId={}, identifier={}, kcId={}",
                command.realmId(),
                command.emailOrUsername(),
                kcUser.getId());
        return null;
    }

    private UserRepresentation resolveKeycloakUser(RealmResource realm, String emailOrUsername) {
        List<UserRepresentation> users = realm.users().searchByEmail(emailOrUsername, true);
        if (users.isEmpty()) {
            users = realm.users().searchByUsername(emailOrUsername, true);
        }
        if (users.isEmpty()) {
            throw new IllegalArgumentException("User not found: " + emailOrUsername);
        }
        return users.get(0);
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
