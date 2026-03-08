package com.koostack.operio.platform.iam.jooq;

import com.koostack.operio.core.PlatformConstants;
import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.platform.events.RoleRemovedEvent;
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
 * Actuator that handles the "remove role" command for platform IAM.
 *
 * <p>Removes the role in Keycloak first, then publishes a {@link RoleRemovedEvent}.
 * If the Keycloak call fails the event is never recorded.
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
     * @param userId the resolved user ID
     * @param role the role name to remove
     * @param emailOrUsername the original identifier (used for logging)
     */
    public record RemoveRoleCommand(
            String realmId, String userId, String role, String emailOrUsername) {}

    @Override
    public Void execute(EventService<?> publisher, RemoveRoleCommand command) {
        // Phase 2: Keycloak side-effect before event
        RealmResource realm = adminClient.realm(command.realmId());
        String kcUserId = resolveKcUserId(realm, command.userId());
        try {
            RoleRepresentation roleRep = realm.roles().get(command.role()).toRepresentation();
            realm.users().get(kcUserId).roles().realmLevel().remove(List.of(roleRep));
        } catch (jakarta.ws.rs.NotFoundException e) {
            LOG.debug("Role or user not found for removal: realm={}, userId={}, role={}",
                    command.realmId(), command.userId(), command.role());
            // Still publish the event — the role was already absent
        }

        // Publish domain event
        var payload = new RoleRemovedEvent(
                command.realmId(), command.userId(), command.role());

        UUID aggregateId = deriveAggregateId(command.realmId());
        AggregateKey key = new AggregateKey(
                aggregateId, USER_AGGREGATE_TYPE, PlatformConstants.PLATFORM_TENANT_ID);

        publisher.publishEvent(
                PublishDomainEventInput.of(
                        PlatformConstants.PLATFORM_TENANT_ID,
                        IamEventTypes.ROLE_REMOVED,
                        key,
                        payload));

        LOG.info("Role removed: realmId={}, identifier={}, role={}, kcId={}",
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

    private static UUID deriveAggregateId(String realmId) {
        return UUID.nameUUIDFromBytes(
                (USER_AGGREGATE_TYPE + ":" + realmId).getBytes(StandardCharsets.UTF_8));
    }
}
