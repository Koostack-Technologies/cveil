package com.koostack.operio.tenant.iam.kc;

import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.tenant.events.TenantRoleRemovedEvent;
import com.koostack.operio.core.tenant.iam.TenantIamEventTypes;
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
 * Actuator that handles the "remove role" command for tenant IAM.
 *
 * <p>Removes the role in the tenant's Keycloak realm first, then publishes a {@link
 * TenantRoleRemovedEvent}. If the Keycloak call fails the event is never recorded.
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
     * @param userId the resolved user ID
     * @param role the role name to remove
     * @param emailOrUsername the original identifier (used for logging)
     */
    public record RemoveRoleCommand(
            String tenantId, String userId, String role, String emailOrUsername) {}

    @Override
    public Void execute(EventService<?> publisher, RemoveRoleCommand command) {
        String realmId = toRealmId(command.tenantId());

        // Phase 2: Keycloak side-effect BEFORE publishing event
        if (adminClient != null) {
            RealmResource realm = adminClient.realm(realmId);
            String kcUserId = resolveKcUserId(realm, command.userId());
            try {
                RoleRepresentation roleRep = realm.roles().get(command.role()).toRepresentation();
                realm.users().get(kcUserId).roles().realmLevel().remove(List.of(roleRep));
            } catch (jakarta.ws.rs.NotFoundException e) {
                LOG.debug(
                        "Role or user not found for removal: realm={}, userId={}, role={}",
                        realmId,
                        command.userId(),
                        command.role());
                // Still publish the event — the role was already absent
            }
        }

        // Publish domain event
        var payload =
                new TenantRoleRemovedEvent(command.tenantId(), command.userId(), command.role());

        UUID aggregateId = deriveAggregateId(command.tenantId());
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

    private String resolveKcUserId(RealmResource realm, String commandUserId) {
        List<UserRepresentation> users =
                realm.users().searchByAttributes("operio-user-id:" + commandUserId);
        if (!users.isEmpty()) return users.get(0).getId();
        throw new IllegalStateException(
                "User not found by operio-user-id attribute: " + commandUserId);
    }

    private static UUID deriveAggregateId(String tenantId) {
        return UUID.nameUUIDFromBytes(
                (USER_AGGREGATE_TYPE + ":" + tenantId).getBytes(StandardCharsets.UTF_8));
    }

    private static String toRealmId(String tenantId) {
        return "t_" + tenantId;
    }
}
