package com.koostack.cveil.platform.iam.jooq;

import com.koostack.cveil.core.PlatformConstants;
import com.koostack.cveil.core.cqrs.CommandActuator;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.event.models.PublishDomainEventInput;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.AggregateKey;
import com.koostack.cveil.core.platform.events.UserRegisteredEvent;
import com.koostack.cveil.core.platform.iam.IamEventTypes;
import com.koostack.cveil.plugin.platform.keycloak.KeycloakIdpProvider;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Actuator that handles the "register user" command for platform IAM.
 *
 * <p>Creates the user in Keycloak first (idempotent), assigns roles, optionally sends the
 * password-setup email, then publishes a {@link UserRegisteredEvent} to the platform event store.
 * If the Keycloak call fails the event is never recorded, keeping the two systems consistent.
 */
public class RegisterUserCommandActuator
        implements CommandActuator<
                RegisterUserCommandActuator.RegisterUserCommand,
                RegisterUserCommandActuator.RegisterUserResult> {

    private static final Logger LOG = LoggerFactory.getLogger(RegisterUserCommandActuator.class);
    private static final String USER_AGGREGATE_TYPE = "iam-user";

    private final Keycloak adminClient;

    private final EventService<?> eventService;

    private final String appBaseUrl;
    private final String clientId;

    public RegisterUserCommandActuator(
            EventService<?> eventService,
            Keycloak adminClient,
            String appBaseUrl,
            String clientId) {
        this.eventService = eventService;
        this.adminClient = adminClient;
        this.appBaseUrl = appBaseUrl;
        this.clientId = clientId;
    }

    /**
     * Command input for registering a new platform user.
     *
     * @param realmId the Keycloak realm (tenantId or platform realm)
     * @param username the user's unique login name
     * @param email the user's email address
     * @param fullName the user's display name (nullable)
     * @param roles initial roles to assign (nullable — defaults to empty)
     * @param temporaryPassword if true, user must change password on first login
     * @param password the user's initial password (nullable — if null, no credentials are set)
     * @param tenantId the derived tenant ID (nullable — stored as Keycloak user attribute for JWT
     *     mapping)
     */
    public record RegisterUserCommand(
            String realmId,
            String username,
            String email,
            String fullName,
            Set<String> roles,
            boolean temporaryPassword,
            String password,
            String tenantId) {

        /** Backward-compatible constructor without tenantId. */
        public RegisterUserCommand(
                String realmId,
                String username,
                String email,
                String fullName,
                Set<String> roles,
                boolean temporaryPassword,
                String password) {
            this(realmId, username, email, fullName, roles, temporaryPassword, password, null);
        }

        /** Backward-compatible constructor without password or tenantId. */
        public RegisterUserCommand(
                String realmId,
                String username,
                String email,
                String fullName,
                Set<String> roles,
                boolean temporaryPassword) {
            this(realmId, username, email, fullName, roles, temporaryPassword, null, null);
        }
    }

    /**
     * Result of registering a user.
     *
     * @param username the user's login name
     * @param email the user's email
     * @param realmId the realm the user was registered in
     * @param keycloakUserId the Keycloak-assigned user ID
     * @param preExisting {@code true} if the user already existed in Keycloak (idempotent hit)
     */
    public record RegisterUserResult(
            String username,
            String email,
            String realmId,
            String keycloakUserId,
            boolean preExisting) {
        /** Backward-compatible constructor (no KC user ID, not pre-existing). */
        public RegisterUserResult(String username, String email, String realmId) {
            this(username, email, realmId, null, false);
        }

        /** Backward-compatible constructor (not pre-existing). */
        public RegisterUserResult(
                String username, String email, String realmId, String keycloakUserId) {
            this(username, email, realmId, keycloakUserId, false);
        }
    }

    @Override
    public RegisterUserResult execute(RegisterUserCommand command) {
        if (command.realmId() == null || command.realmId().isBlank()) {
            throw new IllegalArgumentException("realmId is required");
        }
        if (command.username() == null || command.username().isBlank()) {
            throw new IllegalArgumentException("username is required");
        }
        if (command.email() == null || command.email().isBlank()) {
            throw new IllegalArgumentException("email is required");
        }

        Set<String> roles = command.roles() != null ? command.roles() : Set.of();

        // ---- Phase 1: Keycloak side-effect (before event) -------------------
        KcUserResult kcResult = createKeycloakUser(command, roles);
        String keycloakUserId = kcResult.userId();

        // ---- Phase 2: Publish domain event ----------------------------------
        // The Keycloak-assigned user ID is the canonical aggregate identity
        var payload =
                new UserRegisteredEvent(
                        command.realmId(),
                        keycloakUserId,
                        command.username(),
                        command.email(),
                        command.fullName(),
                        roles,
                        command.temporaryPassword());

        UUID aggregateId = UUID.fromString(keycloakUserId);
        AggregateKey key =
                new AggregateKey(
                        aggregateId, USER_AGGREGATE_TYPE, PlatformConstants.PLATFORM_TENANT_ID);

        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                PlatformConstants.PLATFORM_TENANT_ID,
                                IamEventTypes.USER_REGISTERED,
                                key,
                                payload));

        LOG.info(
                "User registered: realmId={}, username={}, email={}, kcId={}, preExisting={}",
                command.realmId(),
                command.username(),
                command.email(),
                keycloakUserId,
                kcResult.preExisting());

        return new RegisterUserResult(
                command.username(),
                command.email(),
                command.realmId(),
                keycloakUserId,
                kcResult.preExisting());
    }

    // -- Internal result from KC user creation --------------------------------

    private record KcUserResult(String userId, boolean preExisting) {}

    // -- Keycloak operations --------------------------------------------------

    /**
     * Creates the user in Keycloak, assigns roles, and optionally triggers the password-setup
     * email. Idempotent — if the user already exists (by email) the call is a no-op.
     *
     * @return a {@link KcUserResult} with the user ID and whether the user was pre-existing
     */
    private KcUserResult createKeycloakUser(RegisterUserCommand command, Set<String> roles) {
        RealmResource realm = adminClient.realm(command.realmId());

        // Idempotent: check if user already exists
        List<UserRepresentation> existing = realm.users().searchByEmail(command.email(), true);
        if (!existing.isEmpty()) {
            LOG.debug(
                    "User already exists in realm '{}': email={}",
                    command.realmId(),
                    command.email());
            return new KcUserResult(existing.get(0).getId(), true);
        }

        UserRepresentation user = new UserRepresentation();
        user.setEmail(command.email());
        user.setUsername(command.username());
        user.setEnabled(true);
        user.setEmailVerified(false);

        // Store tenant_id as a user attribute so it appears in JWT via protocol mapper
        if (command.tenantId() != null && !command.tenantId().isBlank()) {
            Map<String, List<String>> attrs = new HashMap<>();
            attrs.put("tenant_id", List.of(command.tenantId()));
            user.setAttributes(attrs);
        }

        if (command.fullName() != null && !command.fullName().isBlank()) {
            String[] parts = command.fullName().trim().split("\\s+", 2);
            user.setFirstName(parts[0]);
            if (parts.length > 1) user.setLastName(parts[1]);
        }

        if (command.temporaryPassword()) {
            user.setRequiredActions(List.of("UPDATE_PASSWORD"));
        }

        // Set password credentials if provided
        if (command.password() != null && !command.password().isBlank()) {
            CredentialRepresentation cred = new CredentialRepresentation();
            cred.setType(CredentialRepresentation.PASSWORD);
            cred.setValue(command.password());
            cred.setTemporary(command.temporaryPassword());
            user.setCredentials(List.of(cred));
        }

        var response = realm.users().create(user);
        try {
            if (response.getStatus() == 201) {
                String createdUserId = extractUserId(response);
                if (createdUserId == null) {
                    throw new IllegalStateException(
                            "Keycloak returned 201 but Location header missing or malformed");
                }

                // Assign roles
                for (String roleName : roles) {
                    assignRealmRole(realm, createdUserId, roleName);
                }

                // Send action emails (combined into a single call to avoid duplicate emails)
                var requiredActions = new java.util.ArrayList<String>();
                requiredActions.add("VERIFY_EMAIL");
                if (command.temporaryPassword()) {
                    requiredActions.add("UPDATE_PASSWORD");
                }
                try {
                    String redirectUri =
                            appBaseUrl != null ? appBaseUrl + "/verify-email/callback" : null;
                    if (redirectUri != null && clientId != null) {
                        realm.users()
                                .get(createdUserId)
                                .executeActionsEmail(
                                        clientId,
                                        redirectUri,
                                        KeycloakIdpProvider.VERIFICATION_LINK_LIFESPAN_SECONDS,
                                        requiredActions);
                    } else {
                        realm.users().get(createdUserId).executeActionsEmail(requiredActions);
                    }
                } catch (Exception e) {
                    LOG.warn(
                            "Failed to send action email(s) {}: {}",
                            requiredActions,
                            e.getMessage());
                }

                return new KcUserResult(createdUserId, false);
            } else if (response.getStatus() == 409) {
                LOG.debug(
                        "User already exists (409) in realm '{}': email={}",
                        command.realmId(),
                        command.email());
                return new KcUserResult(null, false);
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
            // Create role if it doesn't exist, then retry
            RoleRepresentation newRole = new RoleRepresentation();
            newRole.setName(roleName);
            newRole.setDescription("Auto-created by IAM command");
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

    @Override
    public EventService<?> eventPublisher() {
        return eventService;
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
