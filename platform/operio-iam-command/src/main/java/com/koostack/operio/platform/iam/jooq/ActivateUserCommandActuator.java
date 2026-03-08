package com.koostack.operio.platform.iam.jooq;

import com.koostack.operio.core.PlatformConstants;
import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.platform.events.UserActivatedEvent;
import com.koostack.operio.core.platform.iam.IamEventTypes;
import java.util.List;
import java.util.UUID;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Actuator that handles the "activate user" command for platform IAM.
 *
 * <p>Resolves the Keycloak user from {@code emailOrUsername}, re-enables them, then publishes a
 * {@link UserActivatedEvent}. If the Keycloak call fails the event is never recorded.
 */
public class ActivateUserCommandActuator
        implements Actuator<ActivateUserCommandActuator.ActivateUserCommand, Void> {

    private static final Logger LOG = LoggerFactory.getLogger(ActivateUserCommandActuator.class);
    private static final String USER_AGGREGATE_TYPE = "iam-user";

    private final Keycloak adminClient;

    public ActivateUserCommandActuator(Keycloak adminClient) {
        this.adminClient = adminClient;
    }

    /**
     * Command input for activating a suspended platform user.
     *
     * @param realmId the Keycloak realm
     * @param emailOrUsername the user's email or username
     */
    public record ActivateUserCommand(String realmId, String emailOrUsername) {}

    @Override
    public Void execute(EventService<?> publisher, ActivateUserCommand command) {
        RealmResource realm = adminClient.realm(command.realmId());

        // Resolve username/email → Keycloak user
        UserRepresentation kcUser = resolveKeycloakUser(realm, command.emailOrUsername());

        // Keycloak side-effect
        kcUser.setEnabled(true);
        realm.users().get(kcUser.getId()).update(kcUser);

        // Publish domain event — Keycloak user ID is the aggregate identity
        var payload = new UserActivatedEvent(command.realmId(), kcUser.getId());
        UUID aggregateId = UUID.fromString(kcUser.getId());
        AggregateKey key =
                new AggregateKey(
                        aggregateId, USER_AGGREGATE_TYPE, PlatformConstants.PLATFORM_TENANT_ID);

        publisher.publishEvent(
                PublishDomainEventInput.of(
                        PlatformConstants.PLATFORM_TENANT_ID,
                        IamEventTypes.USER_ACTIVATED,
                        key,
                        payload));

        LOG.info(
                "User activated: realmId={}, identifier={}, kcId={}",
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
}
