package com.koostack.operio.platform.iam.jooq;

import com.koostack.operio.common.keycloak.KeycloakAuthSupport;
import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.cqrs.CommandDispatcher;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.platform.iam.IamQueryService;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keycloak-native IAM command dispatcher.
 *
 * <p>Implements the generic {@link CommandDispatcher} SPI (actuator-based routing). All IAM
 * commands are dispatched to registered {@link Actuator} instances by command record type.
 */
public class KeycloakIamDispatcher implements CommandDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(KeycloakIamDispatcher.class);

    private final Map<Class<?>, Actuator<?, ?>> actuators = new ConcurrentHashMap<>();

    private EventService<?> eventService;
    private IamQueryService<?> queryService;
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

    // -- Plugin-style lifecycle (called directly by OperioProducers) -----------

    /** Set the query service for email/username → UUID resolution. */
    public void setQueryService(IamQueryService<?> queryService) {
        this.queryService = queryService;
    }

    public void configure(JooqIamCommandConfig config) {
        this.eventService = config.eventService();
        this.keycloakAdmin = config.keycloakAdmin();
        this.keycloakConfig = config.keycloakConfig();

        // Register all actuators
        var kc = config.keycloakAdmin();
        actuators.put(
                RegisterUserCommandActuator.RegisterUserCommand.class,
                new RegisterUserCommandActuator(eventService, kc));
        actuators.put(
                DeleteUserCommandActuator.DeleteUserCommand.class,
                new DeleteUserCommandActuator(eventService, kc));
        actuators.put(
                SuspendUserCommandActuator.SuspendUserCommand.class,
                new SuspendUserCommandActuator(eventService, kc));
        actuators.put(
                ActivateUserCommandActuator.ActivateUserCommand.class,
                new ActivateUserCommandActuator(eventService, kc));
        actuators.put(
                AssignRoleCommandActuator.AssignRoleCommand.class,
                new AssignRoleCommandActuator(eventService, kc));
        actuators.put(
                RemoveRoleCommandActuator.RemoveRoleCommand.class,
                new RemoveRoleCommandActuator(eventService, kc));
        actuators.put(
                UpdateUserProfileCommandActuator.UpdateUserProfileCommand.class,
                new UpdateUserProfileCommandActuator(eventService, kc, keycloakConfig));
        actuators.put(
                ChangePasswordCommandActuator.ChangePasswordCommand.class,
                new ChangePasswordCommandActuator(eventService, kc, keycloakConfig));

        // Access token actuators (DB-backed)
        var platformDs = config.platformDataSource();
        if (platformDs != null) {
            actuators.put(
                    CreateAccessTokenCommandActuator.CreateAccessTokenCommand.class,
                    new CreateAccessTokenCommandActuator(eventService, platformDs));
            actuators.put(
                    RevokeAccessTokenCommandActuator.RevokeAccessTokenCommand.class,
                    new RevokeAccessTokenCommandActuator(eventService, platformDs));
        }

        // Organization actuators (Keycloak Organizations API)
        var orgService = config.orgService();
        if (orgService != null) {
            actuators.put(
                    CreateOrganizationCommandActuator.CreateOrganizationCommand.class,
                    new CreateOrganizationCommandActuator(eventService, orgService));
            actuators.put(
                    AddOrgMemberCommandActuator.AddOrgMemberCommand.class,
                    new AddOrgMemberCommandActuator(eventService, orgService));
            actuators.put(
                    RemoveOrgMemberCommandActuator.RemoveOrgMemberCommand.class,
                    new RemoveOrgMemberCommandActuator(eventService, orgService));
            actuators.put(
                    DeleteOrganizationCommandActuator.DeleteOrganizationCommand.class,
                    new DeleteOrganizationCommandActuator(eventService, orgService));
            actuators.put(
                    InviteOrgMemberCommandActuator.InviteOrgMemberCommand.class,
                    new InviteOrgMemberCommandActuator(eventService, orgService));
            actuators.put(
                    UpdateOrganizationCommandActuator.UpdateOrganizationCommand.class,
                    new UpdateOrganizationCommandActuator(eventService, orgService));
        }

        // Redis provisioning actuator
        var jedis = config.jedis();
        if (jedis != null) {
            actuators.put(
                    ProvisionRedisCommandActuator.ProvisionRedisCommand.class,
                    new ProvisionRedisCommandActuator(eventService, jedis));
        }

        LOG.info("KeycloakIamDispatcher configured with {} actuators", actuators.size());
    }

    /**
     * Register auth actuators that depend on the query service. Must be called after {@link
     * #setQueryService(IamQueryService)}.
     */
    public void registerAuthActuators() {
        if (keycloakConfig == null || keycloakAdmin == null) {
            LOG.warn("Cannot register auth actuators — keycloakConfig/keycloakAdmin not set");
            return;
        }
        var authSupport = new KeycloakAuthSupport(keycloakConfig, keycloakAdmin);
        actuators.put(
                AuthenticateCommandActuator.AuthenticateCommand.class,
                new AuthenticateCommandActuator(eventService, authSupport));
        actuators.put(
                RefreshTokenCommandActuator.RefreshTokenCommand.class,
                new RefreshTokenCommandActuator(eventService, authSupport));
        actuators.put(
                LogoutCommandActuator.LogoutCommand.class,
                new LogoutCommandActuator(eventService, authSupport));
        LOG.info("KeycloakIamDispatcher registered auth actuators (total={})", actuators.size());
    }
}
