package com.koostack.operio.platform.iam.jooq;

import com.koostack.operio.core.PlatformConstants;
import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.platform.events.RoleRemovedEvent;
import com.koostack.operio.core.platform.iam.IamEventTypes;
import java.util.List;
import java.util.UUID;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Actuator that handles the "remove role" command for platform IAM.
 *
 * <p>Resolves the Keycloak user from {@code emailOrUsername}, removes the role, then publishes a
 * {@link RoleRemovedEvent}. If the Keycloak call fails the event is never recorded.
 */
public class RemoveRoleCommandActuator
        implements Actuator<RemoveRoleCommandActuator.RemoveRoleCommand, Void> {

    private static final Logger LOG = LoggerFactory.getLogger(RemoveRoleCommandActuator.class);
    private static final String USER_AGGREGATE_TYPE = "iam-user";

    private final Keycloak adminClient;

    public RemoveRoleCommandActuator(Keycloak adminClient) {
        this.adminClient = adminClient;
    }

    /**
     * Command input for removing a role from a platform user.
     *
     * @param realmId the Keycloak realm
     * @param emailOrUsername the user's email or username
     * @param role the role name to remove
     */
    public record RemoveRoleCommand(String realmId, String emailOrUsername, String role) {}

    @Override
    public Void execute(EventService<?> publisher, RemoveRoleCommand command) {
        RealmResource realm = adminClient.realm(command.realmId());

        // Resolve username/email → Keycloak user
        UserRepresentation kcUser = resolveKeycloakUser(realm, command.emailOrUsername());

        // Keycloak side-effect
        try {
            RoleRepresentation roleRep = realm.roles().get(command.role()).toRepresentation();
            realm.users().get(kcUser.getId()).roles().realmLevel().remove(List.of(roleRep));
        } catch (jakarta.ws.rs.NotFoundException e) {
            LOG.debug(
                    "Role or user not found for removal: realm={}, identifier={}, role={}",
                    command.realmId(),
                    command.emailOrUsername(),
                    command.role());
            // Still publish the event — the role was already absent
        }

        // Publish domain event — Keycloak user ID is the aggregate identity
        var payload = new RoleRemovedEvent(command.realmId(), kcUser.getId(), command.role());
        UUID aggregateId = UUID.fromString(kcUser.getId());
        AggregateKey key =
                new AggregateKey(
                        aggregateId, USER_AGGREGATE_TYPE, PlatformConstants.PLATFORM_TENANT_ID);

        publisher.publishEvent(
                PublishDomainEventInput.of(
                        PlatformConstants.PLATFORM_TENANT_ID,
                        IamEventTypes.ROLE_REMOVED,
                        key,
                        payload));

        LOG.info(
                "Role removed: realmId={}, identifier={}, role={}, kcId={}",
                command.realmId(),
                command.emailOrUsername(),
                command.role(),
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
