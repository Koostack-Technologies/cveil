package com.koostack.operio.tenant.iam.kc;

import com.koostack.operio.core.cqrs.CommandActuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.tenant.events.TenantRoleAssignedEvent;
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
 * Actuator that handles the "assign role" command for tenant IAM.
 *
 * <p>Resolves the Keycloak user from {@code emailOrUsername}, assigns the role in the tenant's
 * Keycloak realm, then publishes a {@link TenantRoleAssignedEvent}.
 */
public class TenantAssignRoleCommandActuator
        implements CommandActuator<TenantAssignRoleCommandActuator.AssignRoleCommand, Void> {

    private static final Logger LOG =
            LoggerFactory.getLogger(TenantAssignRoleCommandActuator.class);
    private static final String USER_AGGREGATE_TYPE = "tenant-iam-user";

    private final Keycloak adminClient;

    private final EventService<?> eventService;

    public TenantAssignRoleCommandActuator(EventService<?> eventService, Keycloak adminClient) {
        this.eventService = eventService;
        this.adminClient = adminClient;
    }

    /**
     * Command input for assigning a role to a tenant user.
     *
     * @param tenantId the tenant identifier
     * @param emailOrUsername the user's email or username
     * @param role the role name to assign
     */
    public record AssignRoleCommand(String tenantId, String emailOrUsername, String role) {}

    @Override
    public Void execute(AssignRoleCommand command) {
        String realmId = toRealmId(command.tenantId());
        String kcUserId = command.emailOrUsername(); // fallback if no admin client

        if (adminClient != null) {
            RealmResource realm = adminClient.realm(realmId);
            UserRepresentation kcUser = resolveKeycloakUser(realm, command.emailOrUsername());
            kcUserId = kcUser.getId();

            assignRealmRole(realm, kcUserId, command.role());
        }

        // Publish domain event — Keycloak user ID is the aggregate identity
        var payload = new TenantRoleAssignedEvent(command.tenantId(), kcUserId, command.role());
        UUID aggregateId = toAggregateId(kcUserId);
        AggregateKey key = new AggregateKey(aggregateId, USER_AGGREGATE_TYPE, command.tenantId());

        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                command.tenantId(),
                                TenantIamEventTypes.ROLE_ASSIGNED,
                                key,
                                payload));

        LOG.info(
                "Tenant role assigned: tenantId={}, identifier={}, role={}",
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

    private void assignRealmRole(RealmResource realm, String kcUserId, String roleName) {
        try {
            RoleRepresentation role = realm.roles().get(roleName).toRepresentation();
            realm.users().get(kcUserId).roles().realmLevel().add(List.of(role));
        } catch (jakarta.ws.rs.NotFoundException e) {
            RoleRepresentation newRole = new RoleRepresentation();
            newRole.setName(roleName);
            newRole.setDescription("Auto-created by tenant IAM command");
            realm.roles().create(newRole);
            RoleRepresentation role = realm.roles().get(roleName).toRepresentation();
            realm.users().get(kcUserId).roles().realmLevel().add(List.of(role));
        }
    }

    private static String toRealmId(String tenantId) {
        return "t_" + tenantId;
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
