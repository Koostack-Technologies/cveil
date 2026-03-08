package com.koostack.operio.platform.iam.jooq;

import com.koostack.operio.core.PlatformConstants;
import com.koostack.operio.core.cqrs.CommandActuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.platform.events.RoleAssignedEvent;
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
 * Actuator that handles the "assign role" command for platform IAM.
 *
 * <p>Resolves the Keycloak user from {@code emailOrUsername}, assigns the role, then publishes a
 * {@link RoleAssignedEvent}. If the Keycloak call fails the event is never recorded.
 */
public class AssignRoleCommandActuator
        implements CommandActuator<AssignRoleCommandActuator.AssignRoleCommand, Void> {

    private static final Logger LOG = LoggerFactory.getLogger(AssignRoleCommandActuator.class);
    private static final String USER_AGGREGATE_TYPE = "iam-user";

    private final Keycloak adminClient;

    private final EventService<?> eventService;

    public AssignRoleCommandActuator(EventService<?> eventService, Keycloak adminClient) {
        this.eventService = eventService;
        this.adminClient = adminClient;
    }

    /**
     * Command input for assigning a role to a platform user.
     *
     * @param realmId the Keycloak realm
     * @param emailOrUsername the user's email or username
     * @param role the role name to assign
     */
    public record AssignRoleCommand(String realmId, String emailOrUsername, String role) {}

    @Override
    public Void execute(AssignRoleCommand command) {
        RealmResource realm = adminClient.realm(command.realmId());

        // Resolve username/email → Keycloak user
        UserRepresentation kcUser = resolveKeycloakUser(realm, command.emailOrUsername());

        // Keycloak side-effect
        assignRealmRole(realm, kcUser.getId(), command.role());

        // Publish domain event — Keycloak user ID is the aggregate identity
        var payload = new RoleAssignedEvent(command.realmId(), kcUser.getId(), command.role());
        UUID aggregateId = UUID.fromString(kcUser.getId());
        AggregateKey key =
                new AggregateKey(
                        aggregateId, USER_AGGREGATE_TYPE, PlatformConstants.PLATFORM_TENANT_ID);

        eventPublisher().publishEvent(
                PublishDomainEventInput.of(
                        PlatformConstants.PLATFORM_TENANT_ID,
                        IamEventTypes.ROLE_ASSIGNED,
                        key,
                        payload));

        LOG.info(
                "Role assigned: realmId={}, identifier={}, role={}, kcId={}",
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

    private void assignRealmRole(RealmResource realm, String kcUserId, String roleName) {
        try {
            RoleRepresentation role = realm.roles().get(roleName).toRepresentation();
            realm.users().get(kcUserId).roles().realmLevel().add(List.of(role));
        } catch (jakarta.ws.rs.NotFoundException e) {
            RoleRepresentation newRole = new RoleRepresentation();
            newRole.setName(roleName);
            newRole.setDescription("Auto-created by IAM command");
            realm.roles().create(newRole);
            RoleRepresentation role = realm.roles().get(roleName).toRepresentation();
            realm.users().get(kcUserId).roles().realmLevel().add(List.of(role));
        }
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
