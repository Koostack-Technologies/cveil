package com.koostack.operio.tenant.iam.kc;

import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.cqrs.QueryDispatcher;
import com.koostack.operio.core.models.AuthResult;
import com.koostack.operio.core.models.IdentityContext;
import com.koostack.operio.core.models.IdpUser;
import com.koostack.operio.core.tenant.iam.TenantIamQueryService;
import com.koostack.operio.plugin.platform.keycloak.JwksTokenVerifier;
import com.koostack.operio.plugin.platform.keycloak.KeycloakConfig;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keycloak-native tenant IAM query dispatcher.
 *
 * <p>Implements both the generic {@link QueryDispatcher} SPI (actuator-based routing) and the
 * legacy {@link TenantIamQueryService} interface (thin delegation layer for backward compatibility).
 *
 * <p>All query methods delegate to the dispatcher, which routes to the appropriate {@link Actuator}
 * by query record type.
 */
public class KeycloakTenantIamQueryDispatcher
        implements QueryDispatcher, TenantIamQueryService<KeycloakTenantIamQueryConfig> {

    private static final Logger LOG =
            LoggerFactory.getLogger(KeycloakTenantIamQueryDispatcher.class);

    private final Map<Class<?>, Actuator<?, ?>> actuators = new ConcurrentHashMap<>();

    private KeycloakConfig config;
    private Keycloak adminClient;
    private TenantKeycloakUserMapper userMapper;

    // -- QueryDispatcher SPI --------------------------------------------------

    @Override
    @SuppressWarnings("unchecked")
    public <I, O> O dispatch(I input) {
        Actuator<I, O> actuator = (Actuator<I, O>) actuators.get(input.getClass());
        if (actuator == null) {
            throw new IllegalArgumentException(
                    "No actuator registered for query type: " + input.getClass().getName());
        }
        return actuator.execute(input);
    }

    // -- Plugin SPI -----------------------------------------------------------

    @Override
    public String getName() {
        return "keycloak-tenant-iam-query-dispatcher";
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public Class<KeycloakTenantIamQueryConfig> getConfigurationType() {
        return KeycloakTenantIamQueryConfig.class;
    }

    @Override
    public void configure(KeycloakTenantIamQueryConfig cfg) {
        this.config = cfg.keycloakConfig();
        this.adminClient =
                KeycloakBuilder.builder()
                        .serverUrl(config.serverUrl())
                        .realm(config.adminRealm())
                        .clientId(config.adminClientId())
                        .username(config.adminUsername())
                        .password(config.adminPassword())
                        .build();
        this.userMapper = new TenantKeycloakUserMapper(adminClient);

        var jwksVerifier = new JwksTokenVerifier();

        // Register all query actuators
        actuators.put(
                TenantListUsersQueryActuator.TenantListUsersQuery.class,
                new TenantListUsersQueryActuator(adminClient, userMapper));
        actuators.put(
                TenantGetUserQueryActuator.TenantGetUserQuery.class,
                new TenantGetUserQueryActuator(adminClient, userMapper));
        actuators.put(
                TenantFindUserByEmailQueryActuator.TenantFindUserByEmailQuery.class,
                new TenantFindUserByEmailQueryActuator(adminClient, userMapper));
        actuators.put(
                TenantFindUserByUsernameQueryActuator.TenantFindUserByUsernameQuery.class,
                new TenantFindUserByUsernameQueryActuator(adminClient, userMapper));
        actuators.put(
                TenantGetUserRolesQueryActuator.TenantGetUserRolesQuery.class,
                new TenantGetUserRolesQueryActuator(userMapper));
        actuators.put(
                TenantGetUserRolesByEmailQueryActuator.TenantGetUserRolesByEmailQuery.class,
                new TenantGetUserRolesByEmailQueryActuator(adminClient, userMapper));
        actuators.put(
                TenantGetUserRolesByUsernameQueryActuator.TenantGetUserRolesByUsernameQuery.class,
                new TenantGetUserRolesByUsernameQueryActuator(adminClient, userMapper));
        actuators.put(
                TenantResolveUserIdQueryActuator.TenantResolveUserIdQuery.class,
                new TenantResolveUserIdQueryActuator(adminClient, userMapper));
        actuators.put(
                TenantListServiceAccountsQueryActuator.TenantListServiceAccountsQuery.class,
                new TenantListServiceAccountsQueryActuator(
                        adminClient, userMapper, config.operioClientId()));
        actuators.put(
                TenantGetServiceAccountQueryActuator.TenantGetServiceAccountQuery.class,
                new TenantGetServiceAccountQueryActuator(adminClient, userMapper));
        actuators.put(
                TenantVerifyTokenQueryActuator.TenantVerifyTokenQuery.class,
                new TenantVerifyTokenQueryActuator(jwksVerifier, config));

        LOG.info(
                "KeycloakTenantIamQueryDispatcher configured with {} actuators",
                actuators.size());
    }

    @Override
    public boolean isReady() {
        return adminClient != null;
    }

    // -- TenantIamQueryService delegation (backward compatibility) ------------

    @Override
    public List<IdpUser> listUsers(String tenantId) {
        return dispatch(new TenantListUsersQueryActuator.TenantListUsersQuery(tenantId));
    }

    @Override
    public IdpUser getUser(String tenantId, String userId) {
        return dispatch(new TenantGetUserQueryActuator.TenantGetUserQuery(tenantId, userId));
    }

    @Override
    public IdpUser findUserByEmail(String tenantId, String email) {
        return dispatch(
                new TenantFindUserByEmailQueryActuator.TenantFindUserByEmailQuery(
                        tenantId, email));
    }

    @Override
    public IdpUser findUserByUsername(String tenantId, String username) {
        return dispatch(
                new TenantFindUserByUsernameQueryActuator.TenantFindUserByUsernameQuery(
                        tenantId, username));
    }

    @Override
    public Set<String> getUserRoles(String tenantId, String userId) {
        return dispatch(
                new TenantGetUserRolesQueryActuator.TenantGetUserRolesQuery(tenantId, userId));
    }

    @Override
    public Set<String> getUserRolesByEmail(String tenantId, String email) {
        return dispatch(
                new TenantGetUserRolesByEmailQueryActuator.TenantGetUserRolesByEmailQuery(
                        tenantId, email));
    }

    @Override
    public Set<String> getUserRolesByUsername(String tenantId, String username) {
        return dispatch(
                new TenantGetUserRolesByUsernameQueryActuator.TenantGetUserRolesByUsernameQuery(
                        tenantId, username));
    }

    @Override
    public String resolveUserId(String tenantId, String emailOrUsername) {
        return dispatch(
                new TenantResolveUserIdQueryActuator.TenantResolveUserIdQuery(
                        tenantId, emailOrUsername));
    }

    @Override
    public List<ServiceAccountInfo> listServiceAccounts(String tenantId) {
        return dispatch(
                new TenantListServiceAccountsQueryActuator.TenantListServiceAccountsQuery(
                        tenantId));
    }

    @Override
    public ServiceAccountInfo getServiceAccount(String tenantId, String clientId) {
        return dispatch(
                new TenantGetServiceAccountQueryActuator.TenantGetServiceAccountQuery(
                        tenantId, clientId));
    }

    @Override
    public AuthResult authenticate(String tenantId, String email, String password) {
        throw new UnsupportedOperationException(
                "Use the command dispatcher for authentication — "
                        + "TenantAuthenticateCommandActuator handles this operation");
    }

    @Override
    public AuthResult refreshToken(String tenantId, String refreshToken) {
        throw new UnsupportedOperationException(
                "Use the command dispatcher for token refresh — "
                        + "TenantRefreshTokenCommandActuator handles this operation");
    }

    @Override
    public IdentityContext verifyToken(String tenantId, String accessToken) {
        return dispatch(
                new TenantVerifyTokenQueryActuator.TenantVerifyTokenQuery(tenantId, accessToken));
    }

    @Override
    public void logout(String tenantId, String refreshToken) {
        throw new UnsupportedOperationException(
                "Use the command dispatcher for logout — "
                        + "TenantLogoutCommandActuator handles this operation");
    }
}
