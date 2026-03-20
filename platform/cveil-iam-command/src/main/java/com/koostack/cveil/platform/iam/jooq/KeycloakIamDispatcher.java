package com.koostack.cveil.platform.iam.jooq;

import com.koostack.cveil.common.keycloak.KeycloakAuthSupport;
import com.koostack.cveil.core.cqrs.Actuator;
import com.koostack.cveil.core.cqrs.CommandDispatcher;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.platform.iam.IamQueryService;
import com.koostack.cveil.plugin.platform.keycloak.JwksTokenVerifier;
import com.koostack.cveil.plugin.platform.keycloak.KeycloakIdpProvider;
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
    private com.koostack.cveil.plugin.platform.keycloak.KeycloakConfig keycloakConfig;
    private KeycloakIdpProvider idpProvider;
    private String appBaseUrl;

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

    // -- Plugin-style lifecycle (called directly by CveilProducers) -----------

    /** Set the query service for email/username → UUID resolution. */
    public void setQueryService(IamQueryService<?> queryService) {
        this.queryService = queryService;
    }

    /** Set the IdP provider for operations that need direct Keycloak access. */
    public void setIdpProvider(KeycloakIdpProvider idpProvider) {
        this.idpProvider = idpProvider;
    }

    /** Set the application base URL for redirect URIs (e.g. email verification callback). */
    public void setAppBaseUrl(String appBaseUrl) {
        // Strip trailing slash to avoid double-slash in constructed URLs
        this.appBaseUrl =
                appBaseUrl != null && appBaseUrl.endsWith("/")
                        ? appBaseUrl.substring(0, appBaseUrl.length() - 1)
                        : appBaseUrl;
    }

    public void configure(JooqIamCommandConfig config) {
        this.eventService = config.eventService();
        this.keycloakAdmin = config.keycloakAdmin();
        this.keycloakConfig = config.keycloakConfig();

        // Register all actuators
        var kc = config.keycloakAdmin();
        String clientId = keycloakConfig != null ? keycloakConfig.cveilClientId() : null;
        actuators.put(
                RegisterUserCommandActuator.RegisterUserCommand.class,
                new RegisterUserCommandActuator(eventService, kc, appBaseUrl, clientId));
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

        // App-specific profile update (local DB only — no Keycloak side-effect)
        actuators.put(
                UpdateUserAppProfileCommandActuator.UpdateUserAppProfileCommand.class,
                new UpdateUserAppProfileCommandActuator(eventService));

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
     *
     * @param sharedJwksVerifier shared JWKS verifier instance (from the IdpProvider) so the login
     *     and API-request verification paths use the same cache
     */
    public void registerAuthActuators(JwksTokenVerifier sharedJwksVerifier) {
        if (keycloakConfig == null || keycloakAdmin == null) {
            LOG.warn("Cannot register auth actuators — keycloakConfig/keycloakAdmin not set");
            return;
        }
        var authSupport =
                new KeycloakAuthSupport(keycloakConfig, keycloakAdmin, sharedJwksVerifier);
        actuators.put(
                AuthenticateCommandActuator.AuthenticateCommand.class,
                new AuthenticateCommandActuator(eventService, authSupport));
        actuators.put(
                RefreshTokenCommandActuator.RefreshTokenCommand.class,
                new RefreshTokenCommandActuator(eventService, authSupport));
        actuators.put(
                LogoutCommandActuator.LogoutCommand.class,
                new LogoutCommandActuator(eventService, authSupport));

        // Email verification (requires idpProvider)
        if (idpProvider != null) {
            String verifyClientId = keycloakConfig != null ? keycloakConfig.cveilClientId() : null;
            actuators.put(
                    SendVerificationEmailCommandActuator.SendVerificationEmailCommand.class,
                    new SendVerificationEmailCommandActuator(
                            eventService, idpProvider, appBaseUrl, verifyClientId));
        }

        LOG.info("KeycloakIamDispatcher registered auth actuators (total={})", actuators.size());
    }

    /**
     * @deprecated Use {@link #registerAuthActuators(JwksTokenVerifier)} to share the JWKS cache.
     */
    @Deprecated
    public void registerAuthActuators() {
        registerAuthActuators(new JwksTokenVerifier());
    }
}
