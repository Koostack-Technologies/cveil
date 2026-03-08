package com.koostack.operio.tenant.iam.kc;

import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.tenant.events.TenantUserRegisteredEvent;
import com.koostack.operio.core.tenant.iam.TenantIamEventTypes;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Actuator that handles the "register user" command for tenant IAM.
 *
 * <p>Creates the user in the tenant's Keycloak realm first (idempotent), assigns roles, optionally
 * sends the password-setup email, then publishes a {@link TenantUserRegisteredEvent} to the tenant
 * event store. If the Keycloak call fails the event is never recorded.
 */
public class TenantRegisterUserCommandActuator
        implements Actuator<
                TenantRegisterUserCommandActuator.RegisterUserCommand,
                TenantRegisterUserCommandActuator.RegisterUserResult> {

    private static final Logger LOG =
            LoggerFactory.getLogger(TenantRegisterUserCommandActuator.class);
    private static final String USER_AGGREGATE_TYPE = "tenant-iam-user";

    private final Keycloak adminClient;

    public TenantRegisterUserCommandActuator(Keycloak adminClient) {
        this.adminClient = adminClient;
    }

    /**
     * Command input for registering a tenant user.
     *
     * @param tenantId the tenant identifier
     * @param username the user's unique login name
     * @param email the user's email address
     * @param fullName the user's display name (nullable)
     * @param roles initial roles to assign (nullable — defaults to empty)
     * @param temporaryPassword if true, user must change password on first login
     */
    public record RegisterUserCommand(
            String tenantId,
            String username,
            String email,
            String fullName,
            Set<String> roles,
            boolean temporaryPassword) {}

    /**
     * Result of registering a tenant user.
     *
     * @param username the user's login name
     * @param email the user's email
     * @param realmId the Keycloak realm the user was registered in
     */
    public record RegisterUserResult(String username, String email, String realmId) {}

    @Override
    public RegisterUserResult execute(EventService<?> publisher, RegisterUserCommand command) {
        if (command.tenantId() == null || command.tenantId().isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (command.username() == null || command.username().isBlank()) {
            throw new IllegalArgumentException("username is required");
        }
        if (command.email() == null || command.email().isBlank()) {
            throw new IllegalArgumentException("email is required");
        }

        String realmId = toRealmId(command.tenantId());
        Set<String> roles = command.roles() != null ? command.roles() : Set.of();

        // Phase 2: Keycloak side-effect BEFORE publishing event
        String keycloakUserId = null;
        if (adminClient != null) {
            keycloakUserId = createKeycloakUser(command, realmId, roles);
        }

        // Publish domain event (only reached if KC succeeded)
        String userId = keycloakUserId != null ? keycloakUserId : UUID.randomUUID().toString();
        var payload =
                new TenantUserRegisteredEvent(
                        command.tenantId(),
                        userId,
                        command.username(),
                        command.email(),
                        command.fullName(),
                        roles,
                        command.temporaryPassword());

        UUID aggregateId = UUID.fromString(userId);
        AggregateKey key = new AggregateKey(aggregateId, USER_AGGREGATE_TYPE, command.tenantId());

        publisher.publishEvent(
                PublishDomainEventInput.of(
                        command.tenantId(), TenantIamEventTypes.USER_REGISTERED, key, payload));

        LOG.info(
                "Tenant user registered: tenantId={}, username={}, email={}",
                command.tenantId(),
                command.username(),
                command.email());

        return new RegisterUserResult(command.username(), command.email(), realmId);
    }

    // -- Keycloak operations --------------------------------------------------

    private String createKeycloakUser(
            RegisterUserCommand command, String realmId, Set<String> roles) {
        RealmResource realm = adminClient.realm(realmId);

        // Idempotent: check if user already exists
        List<UserRepresentation> existing = realm.users().searchByEmail(command.email(), true);
        if (!existing.isEmpty()) {
            LOG.debug("User already exists in realm '{}': email={}", realmId, command.email());
            return existing.get(0).getId();
        }

        UserRepresentation user = new UserRepresentation();
        user.setEmail(command.email());
        user.setUsername(command.username());
        user.setEnabled(true);
        user.setEmailVerified(!command.temporaryPassword());

        if (command.fullName() != null && !command.fullName().isBlank()) {
            String[] parts = command.fullName().trim().split("\\s+", 2);
            user.setFirstName(parts[0]);
            if (parts.length > 1) user.setLastName(parts[1]);
        }

        if (command.temporaryPassword()) {
            user.setRequiredActions(List.of("UPDATE_PASSWORD"));
        }

        var response = realm.users().create(user);
        try {
            if (response.getStatus() == 201) {
                String createdUserId = extractUserId(response);
                if (createdUserId == null) {
                    throw new IllegalStateException(
                            "Keycloak returned 201 but Location header missing or malformed");
                }

                for (String roleName : roles) {
                    assignRealmRole(realm, createdUserId, roleName);
                }

                if (command.temporaryPassword()) {
                    try {
                        realm.users()
                                .get(createdUserId)
                                .executeActionsEmail(List.of("UPDATE_PASSWORD"));
                    } catch (Exception e) {
                        LOG.warn("Failed to send password setup email: {}", e.getMessage());
                    }
                }

                return createdUserId;
            } else if (response.getStatus() == 409) {
                LOG.debug(
                        "User already exists (409) in realm '{}': email={}",
                        realmId,
                        command.email());
                // Re-fetch to get the KC user ID
                List<UserRepresentation> retried =
                        realm.users().searchByEmail(command.email(), true);
                return retried.isEmpty() ? null : retried.get(0).getId();
            } else {
                throw new RuntimeException(
                        "Keycloak user creation failed: HTTP " + response.getStatus());
            }
        } finally {
            response.close();
        }
    }

    private void assignRealmRole(RealmResource realm, String userId, String roleName) {
        try {
            RoleRepresentation role = realm.roles().get(roleName).toRepresentation();
            realm.users().get(userId).roles().realmLevel().add(List.of(role));
        } catch (jakarta.ws.rs.NotFoundException e) {
            RoleRepresentation newRole = new RoleRepresentation();
            newRole.setName(roleName);
            newRole.setDescription("Auto-created by tenant IAM command");
            realm.roles().create(newRole);
            RoleRepresentation role = realm.roles().get(roleName).toRepresentation();
            realm.users().get(userId).roles().realmLevel().add(List.of(role));
        }
    }

    private static String extractUserId(jakarta.ws.rs.core.Response response) {
        String location = response.getHeaderString("Location");
        if (location != null && location.contains("/")) {
            return location.substring(location.lastIndexOf('/') + 1);
        }
        return null;
    }


    private static String toRealmId(String tenantId) {
        return "t_" + tenantId;
    }
}
