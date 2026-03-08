package com.koostack.operio.tenant.iam.kc;

import com.koostack.operio.common.keycloak.KeycloakAuthSupport;
import com.koostack.operio.common.keycloak.RealmResolver;
import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.cqrs.CommandDispatcher;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.tenant.iam.TenantIamCommandService;
import com.koostack.operio.core.tenant.iam.TenantIamQueryService;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keycloak-native tenant IAM command dispatcher.
 *
 * <p>Implements both the generic {@link CommandDispatcher} SPI (actuator-based routing) and the
 * legacy {@link TenantIamCommandService} interface (thin delegation layer for backward
 * compatibility with existing REST resources and holders).
 *
 * <p>All non-deprecated command methods delegate to the dispatcher, which routes to the appropriate
 * {@link Actuator} by command record type. Each actuator performs the Keycloak side-effect
 * synchronously before publishing a domain event to the tenant event store.
 */
public class KeycloakTenantIamDispatcher
        implements CommandDispatcher, TenantIamCommandService<KeycloakTenantIamCommandConfig> {

    private static final Logger LOG = LoggerFactory.getLogger(KeycloakTenantIamDispatcher.class);

    private final Map<Class<?>, Actuator<?, ?>> actuators = new ConcurrentHashMap<>();

    private EventService<?> eventService;
    private TenantIamQueryService<?> queryService;
    private org.keycloak.admin.client.Keycloak keycloakAdmin;
    private com.koostack.operio.plugin.platform.keycloak.KeycloakConfig keycloakConfig;

    // -- CommandDispatcher SPI -------------------------------------------------

    @Override
    @SuppressWarnings("unchecked")
    public <I, O> O dispatch(I input) {
        Actuator<I, O> actuator = (Actuator<I, O>) actuators.get(input.getClass());
        if (actuator == null) {
            throw new IllegalArgumentException(
                    "No actuator registered for command type: " + input.getClass().getName());
        }
        return actuator.execute(input);
    }

    // -- Plugin SPI -----------------------------------------------------------

    /** Set the query service for email/username → UUID resolution. */
    public void setQueryService(TenantIamQueryService<?> queryService) {
        this.queryService = queryService;
    }

    @Override
    public String getName() {
        return "keycloak-tenant-iam-dispatcher";
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public Class<KeycloakTenantIamCommandConfig> getConfigurationType() {
        return KeycloakTenantIamCommandConfig.class;
    }

    @Override
    public void configure(KeycloakTenantIamCommandConfig config) {
        this.eventService = config.eventService();
        this.keycloakAdmin = config.keycloakAdmin();
        this.keycloakConfig = config.keycloakConfig();
        var kc = config.keycloakAdmin();

        // Register all actuators
        actuators.put(
                TenantRegisterUserCommandActuator.RegisterUserCommand.class,
                new TenantRegisterUserCommandActuator(eventService, kc));
        actuators.put(
                TenantSuspendUserCommandActuator.SuspendUserCommand.class,
                new TenantSuspendUserCommandActuator(eventService, kc));
        actuators.put(
                TenantActivateUserCommandActuator.ActivateUserCommand.class,
                new TenantActivateUserCommandActuator(eventService, kc));
        actuators.put(
                TenantCreateServiceAccountCommandActuator.CreateServiceAccountCommand.class,
                new TenantCreateServiceAccountCommandActuator(eventService, kc));
        actuators.put(
                TenantRevokeServiceAccountCommandActuator.RevokeServiceAccountCommand.class,
                new TenantRevokeServiceAccountCommandActuator(eventService, kc));
        actuators.put(
                TenantAssignRoleCommandActuator.AssignRoleCommand.class,
                new TenantAssignRoleCommandActuator(eventService, kc));
        actuators.put(
                TenantRemoveRoleCommandActuator.RemoveRoleCommand.class,
                new TenantRemoveRoleCommandActuator(eventService, kc));

        LOG.info("KeycloakTenantIamDispatcher configured with {} actuators", actuators.size());
    }

    /**
     * Register auth actuators that depend on the query service. Must be called after {@link
     * #setQueryService(TenantIamQueryService)}.
     */
    public void registerAuthActuators() {
        if (keycloakConfig == null || keycloakAdmin == null) {
            LOG.warn("Cannot register tenant auth actuators — keycloakConfig/keycloakAdmin not set");
            return;
        }
        var authSupport = new KeycloakAuthSupport(keycloakConfig, keycloakAdmin, RealmResolver.tenantPrefix());
        actuators.put(
                TenantAuthenticateCommandActuator.AuthenticateCommand.class,
                new TenantAuthenticateCommandActuator(eventService, authSupport));
        actuators.put(
                TenantRefreshTokenCommandActuator.RefreshTokenCommand.class,
                new TenantRefreshTokenCommandActuator(eventService, authSupport));
        actuators.put(
                TenantLogoutCommandActuator.LogoutCommand.class,
                new TenantLogoutCommandActuator(eventService, authSupport));
        LOG.info(
                "KeycloakTenantIamDispatcher registered auth actuators (total={})",
                actuators.size());
    }

    @Override
    public boolean isReady() {
        return eventService != null;
    }

    // -- TenantIamCommandService delegation (backward compatibility) -----------

    @Override
    public RegisterUserResult registerUser(
            String tenantId,
            String username,
            String email,
            String fullName,
            Set<String> roles,
            boolean temporaryPassword) {
        var command =
                new TenantRegisterUserCommandActuator.RegisterUserCommand(
                        tenantId, username, email, fullName, roles, temporaryPassword);
        TenantRegisterUserCommandActuator.RegisterUserResult result = dispatch(command);
        return new RegisterUserResult(result.username(), result.email(), result.realmId());
    }

    @Override
    public void suspendUser(String tenantId, String emailOrUsername) {
        dispatch(new TenantSuspendUserCommandActuator.SuspendUserCommand(
                tenantId, emailOrUsername));
    }

    @Override
    public void activateUser(String tenantId, String emailOrUsername) {
        dispatch(new TenantActivateUserCommandActuator.ActivateUserCommand(
                tenantId, emailOrUsername));
    }

    @Override
    public CreateServiceAccountResult createServiceAccount(
            String tenantId, String name, Set<String> roles) {
        var command =
                new TenantCreateServiceAccountCommandActuator.CreateServiceAccountCommand(
                        tenantId, name, roles);
        TenantCreateServiceAccountCommandActuator.CreateServiceAccountResult result =
                dispatch(command);
        return new CreateServiceAccountResult(
                result.clientId(), result.clientSecret(), result.realmId());
    }

    @Override
    public void revokeServiceAccount(String tenantId, String clientId) {
        dispatch(
                new TenantRevokeServiceAccountCommandActuator.RevokeServiceAccountCommand(
                        tenantId, clientId));
    }

    @Override
    public void assignRole(String tenantId, String emailOrUsername, String role) {
        dispatch(new TenantAssignRoleCommandActuator.AssignRoleCommand(
                tenantId, emailOrUsername, role));
    }

    @Override
    public void removeRole(String tenantId, String emailOrUsername, String role) {
        dispatch(new TenantRemoveRoleCommandActuator.RemoveRoleCommand(
                tenantId, emailOrUsername, role));
    }

    // -- Internal helpers -----------------------------------------------------

    private String requireUserId(String tenantId, String emailOrUsername) {
        if (queryService == null) {
            throw new IllegalStateException(
                    "queryService not configured — call setQueryService() after wiring");
        }
        String userId = queryService.resolveUserId(tenantId, emailOrUsername);
        if (userId == null) {
            throw new IllegalArgumentException(
                    "User not found: " + emailOrUsername + " in tenant " + tenantId);
        }
        return userId;
    }
}
