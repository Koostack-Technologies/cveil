package com.koostack.operio.tenant.iam.kc;

import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.tenant.events.TenantRoleRemovedEvent;
import com.koostack.operio.core.tenant.iam.TenantIamEventTypes;
import java.util.List;
import java.util.UUID;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Actuator that handles the "remove role" command for tenant IAM.
 *
 * <p>Resolves the Keycloak user from {@code emailOrUsername}, removes the role in the tenant's
 * Keycloak realm, then publishes a {@link TenantRoleRemovedEvent}.
 */
public class TenantRemoveRoleCommandActuator
        implements Actuator<TenantRemoveRoleCommandActuator.RemoveRoleCommand, Void> {

    private static final Logger LOG =
            LoggerFactory.getLogger(TenantRemoveRoleCommandActuator.class);
    private static final String USER_AGGREGATE_TYPE = "tenant-iam-user";

    private final Keycloak adminClient;

    public TenantRemoveRoleCommandActuator(Keycloak adminClient) {
        this.adminClient = adminClient;
    }

    /**
     * Command input for removing a role from a tenant user.
     *
     * @param tenantId the tenant identifier
     * @param emailOrUsername the user's email or username
     * @param role the role name to remove
     */
    public record RemoveRoleCommand(String tenantId, String emailOrUsername, String role) {}

    @Override
    public Void execute(EventService<?> publisher, RemoveRoleCommand command) {
        String realmId = toRealmId(command.tenantId());
        String kcUserId = command.emailOrUsername(); // fallback if no admin client

        if (adminClient != null) {
            RealmResource realm = adminClient.realm(realmId);
            UserRepresentation kcUser = resolveKeycloakUser(realm, command.emailOrUsername());
            kcUserId = kcUser.getId();

            try {
                RoleRepresentation roleRep = realm.roles().get(command.role()).toRepresentation();
                realm.users().get(kcUserId).roles().realmLevel().remove(List.of(roleRep));
            } catch (jakarta.ws.rs.NotFoundException e) {
                LOG.debug(
                        "Role or user not found for removal: realm={}, identifier={}, role={}",
                        realmId,
                        command.emailOrUsername(),
                        command.role());
            }
        }

        // Publish domain event — Keycloak user ID is the aggregate identity
        var payload = new TenantRoleRemovedEvent(command.tenantId(), kcUserId, command.role());
        UUID aggregateId = toAggregateId(kcUserId);
        AggregateKey key = new AggregateKey(aggregateId, USER_AGGREGATE_TYPE, command.tenantId());

        publisher.publishEvent(
                PublishDomainEventInput.of(
                        command.tenantId(), TenantIamEventTypes.ROLE_REMOVED, key, payload));

        LOG.info(
                "Tenant role removed: tenantId={}, identifier={}, role={}",
                command.tenantId(),
                command.emailOrUsername(),
                command.role());
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



    private static UUID toAggregateId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return UUID.nameUUIDFromBytes(id.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static String toRealmId(String tenantId) {
        return "t_" + tenantId;
    }
}
