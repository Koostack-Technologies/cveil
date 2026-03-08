package com.koostack.operio.platform.iam.jooq;

import com.koostack.operio.core.PlatformConstants;
import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.platform.events.RoleAssignedEvent;
import com.koostack.operio.core.platform.iam.IamEventTypes;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Actuator that handles the "assign role" command for platform IAM.
 *
 * <p>Assigns the role in Keycloak first, then publishes a {@link RoleAssignedEvent}.
 * If the Keycloak call fails the event is never recorded.
 */
public class AssignRoleCommandActuator
        implements Actuator<AssignRoleCommandActuator.AssignRoleCommand, Void> {

    private static final Logger LOG = LoggerFactory.getLogger(AssignRoleCommandActuator.class);
    private static final String USER_AGGREGATE_TYPE = "iam-user";

    private final Keycloak adminClient;

    public AssignRoleCommandActuator(Keycloak adminClient) {
        this.adminClient = adminClient;
    }

    /**
     * Command input for assigning a role to a platform user.
     *
     * @param realmId the Keycloak realm
     * @param userId the resolved user ID
     * @param role the role name to assign
     * @param emailOrUsername the original identifier (used for logging)
     */
    public record AssignRoleCommand(
            String realmId, String userId, String role, String emailOrUsername) {}

    @Override
    public Void execute(EventService<?> publisher, AssignRoleCommand command) {
        // Phase 2: Keycloak side-effect before event
        RealmResource realm = adminClient.realm(command.realmId());
        String kcUserId = resolveKcUserId(realm, command.userId());
        assignRealmRole(realm, kcUserId, command.role());

        // Publish domain event
        var payload = new RoleAssignedEvent(
                command.realmId(), command.userId(), command.role());

        UUID aggregateId = deriveAggregateId(command.realmId());
        AggregateKey key = new AggregateKey(
                aggregateId, USER_AGGREGATE_TYPE, PlatformConstants.PLATFORM_TENANT_ID);

        publisher.publishEvent(
                PublishDomainEventInput.of(
                        PlatformConstants.PLATFORM_TENANT_ID,
                        IamEventTypes.ROLE_ASSIGNED,
                        key,
                        payload));

        LOG.info("Role assigned: realmId={}, identifier={}, role={}, kcId={}",
                command.realmId(), command.emailOrUsername(), command.role(), kcUserId);
        return null;
    }

    private String resolveKcUserId(RealmResource realm, String commandUserId) {
        List<UserRepresentation> users = realm.users()
                .searchByAttributes("operio-user-id:" + commandUserId);
        if (!users.isEmpty()) return users.get(0).getId();
        throw new IllegalStateException(
                "User not found by operio-user-id attribute: " + commandUserId);
    }

    private void assignRealmRole(RealmResource realm, String userId, String roleName) {
        try {
            RoleRepresentation role = realm.roles().get(roleName).toRepresentation();
            realm.users().get(userId).roles().realmLevel().add(List.of(role));
        } catch (jakarta.ws.rs.NotFoundException e) {
            RoleRepresentation newRole = new RoleRepresentation();
            newRole.setName(roleName);
            newRole.setDescription("Auto-created by IAM command");
            realm.roles().create(newRole);
            RoleRepresentation role = realm.roles().get(roleName).toRepresentation();
            realm.users().get(userId).roles().realmLevel().add(List.of(role));
        }
    }

    private static UUID deriveAggregateId(String realmId) {
        return UUID.nameUUIDFromBytes(
                (USER_AGGREGATE_TYPE + ":" + realmId).getBytes(StandardCharsets.UTF_8));
    }
}
