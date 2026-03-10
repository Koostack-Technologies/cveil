package com.koostack.operio.platform.iam.jooq;

import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.cqrs.QueryDispatcher;
import com.koostack.operio.core.models.AuthResult;
import com.koostack.operio.core.models.IdentityContext;
import com.koostack.operio.core.models.IdpUser;
import com.koostack.operio.core.platform.iam.IamQueryService;
import com.koostack.operio.plugin.platform.keycloak.JwksTokenVerifier;
import com.koostack.operio.plugin.platform.keycloak.KeycloakConfig;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keycloak-native IAM query dispatcher.
 *
 * <p>Implements both the generic {@link QueryDispatcher} SPI (actuator-based routing) and the
 * legacy {@link IamQueryService} interface (thin delegation layer for backward compatibility).
 *
 * <p>All query methods delegate to the dispatcher, which routes to the appropriate {@link Actuator}
 * by query record type.
 */
public class KeycloakIamQueryDispatcher
        implements QueryDispatcher, IamQueryService<JooqIamQueryConfig> {

    private static final Logger LOG = LoggerFactory.getLogger(KeycloakIamQueryDispatcher.class);

    private final Map<Class<?>, Actuator<?, ?>> actuators = new ConcurrentHashMap<>();

    private KeycloakConfig config;
    private Keycloak adminClient;
    private KeycloakUserMapper userMapper;

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
        return "keycloak-iam-query-dispatcher";
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public Class<JooqIamQueryConfig> getConfigurationType() {
        return JooqIamQueryConfig.class;
    }

    @Override
    public void configure(JooqIamQueryConfig cfg) {
        this.config = cfg.keycloakConfig();
        this.adminClient =
                KeycloakBuilder.builder()
                        .serverUrl(config.serverUrl())
                        .realm(config.adminRealm())
                        .clientId(config.adminClientId())
                        .username(config.adminUsername())
                        .password(config.adminPassword())
                        .build();
        this.userMapper = new KeycloakUserMapper(adminClient);

        var jwksVerifier = new JwksTokenVerifier();
        DataSource platformDataSource = cfg.platformDataSource();

        // Register all query actuators
        actuators.put(
                ListUsersQueryActuator.ListUsersQuery.class,
                new ListUsersQueryActuator(adminClient, userMapper));
        actuators.put(
                GetUserQueryActuator.GetUserQuery.class,
                new GetUserQueryActuator(adminClient, userMapper));
        actuators.put(
                FindUserByEmailQueryActuator.FindUserByEmailQuery.class,
                new FindUserByEmailQueryActuator(adminClient, userMapper));
        actuators.put(
                FindUserByUsernameQueryActuator.FindUserByUsernameQuery.class,
                new FindUserByUsernameQueryActuator(adminClient, userMapper));
        actuators.put(
                GetUserRolesQueryActuator.GetUserRolesQuery.class,
                new GetUserRolesQueryActuator(adminClient, userMapper));
        actuators.put(
                GetUserRolesByEmailQueryActuator.GetUserRolesByEmailQuery.class,
                new GetUserRolesByEmailQueryActuator(adminClient));
        actuators.put(
                GetUserRolesByUsernameQueryActuator.GetUserRolesByUsernameQuery.class,
                new GetUserRolesByUsernameQueryActuator(adminClient));
        actuators.put(
                ResolveUserIdQueryActuator.ResolveUserIdQuery.class,
                new ResolveUserIdQueryActuator(adminClient, userMapper));
        actuators.put(
                ListServiceAccountsQueryActuator.ListServiceAccountsQuery.class,
                new ListServiceAccountsQueryActuator(adminClient, config.operioClientId()));
        actuators.put(
                GetServiceAccountQueryActuator.GetServiceAccountQuery.class,
                new GetServiceAccountQueryActuator(adminClient));
        actuators.put(
                VerifyTokenQueryActuator.VerifyTokenQuery.class,
                new VerifyTokenQueryActuator(jwksVerifier, config));
        @SuppressWarnings("removal")
        var ignored =
                actuators.put(
                        FindByKeyHashQueryActuator.FindByKeyHashQuery.class,
                        new FindByKeyHashQueryActuator(platformDataSource));

        // Organization query actuators
        var orgService = cfg.orgService();
        if (orgService != null) {
            actuators.put(
                    ListOrganizationsQueryActuator.ListOrganizationsQuery.class,
                    new ListOrganizationsQueryActuator(orgService));
            actuators.put(
                    GetOrganizationQueryActuator.GetOrganizationQuery.class,
                    new GetOrganizationQueryActuator(orgService));
            actuators.put(
                    ListOrgMembersQueryActuator.ListOrgMembersQuery.class,
                    new ListOrgMembersQueryActuator(orgService));
        }

        LOG.info("KeycloakIamQueryDispatcher configured with {} actuators", actuators.size());
    }

    @Override
    public boolean isReady() {
        return adminClient != null;
    }

    // -- IamQueryService delegation (backward compatibility) -------------------

    @Override
    public List<IdpUser> listUsers(String realmId) {
        return dispatch(new ListUsersQueryActuator.ListUsersQuery(realmId));
    }

    @Override
    public IdpUser getUser(String realmId, String userId) {
        return dispatch(new GetUserQueryActuator.GetUserQuery(realmId, userId));
    }

    @Override
    public IdpUser findUserByEmail(String realmId, String email) {
        return dispatch(new FindUserByEmailQueryActuator.FindUserByEmailQuery(realmId, email));
    }

    @Override
    public IdpUser findUserByUsername(String realmId, String username) {
        return dispatch(
                new FindUserByUsernameQueryActuator.FindUserByUsernameQuery(realmId, username));
    }

    @Override
    public Set<String> getUserRoles(String realmId, String userId) {
        return dispatch(new GetUserRolesQueryActuator.GetUserRolesQuery(realmId, userId));
    }

    @Override
    public Set<String> getUserRolesByEmail(String realmId, String email) {
        return dispatch(
                new GetUserRolesByEmailQueryActuator.GetUserRolesByEmailQuery(realmId, email));
    }

    @Override
    public Set<String> getUserRolesByUsername(String realmId, String username) {
        return dispatch(
                new GetUserRolesByUsernameQueryActuator.GetUserRolesByUsernameQuery(
                        realmId, username));
    }

    @Override
    public String resolveUserId(String realmId, String emailOrUsername) {
        return dispatch(
                new ResolveUserIdQueryActuator.ResolveUserIdQuery(realmId, emailOrUsername));
    }

    @Override
    public List<ServiceAccountInfo> listServiceAccounts(String realmId) {
        return dispatch(new ListServiceAccountsQueryActuator.ListServiceAccountsQuery(realmId));
    }

    @Override
    public ServiceAccountInfo getServiceAccount(String realmId, String clientId) {
        return dispatch(
                new GetServiceAccountQueryActuator.GetServiceAccountQuery(realmId, clientId));
    }

    @Override
    public AuthResult authenticate(String realmId, String email, String password) {
        // Authentication is a command-side concern — delegate remains on IamQueryService
        // for backward compatibility but callers should use the command dispatcher.
        throw new UnsupportedOperationException(
                "Use the command dispatcher for authentication — "
                        + "AuthenticateCommandActuator handles this operation");
    }

    @Override
    public AuthResult refreshToken(String realmId, String refreshToken) {
        throw new UnsupportedOperationException(
                "Use the command dispatcher for token refresh — "
                        + "RefreshTokenCommandActuator handles this operation");
    }

    @Override
    public IdentityContext verifyToken(String realmId, String accessToken) {
        return dispatch(new VerifyTokenQueryActuator.VerifyTokenQuery(realmId, accessToken));
    }

    @Override
    public void logout(String realmId, String refreshToken) {
        throw new UnsupportedOperationException(
                "Use the command dispatcher for logout — "
                        + "LogoutCommandActuator handles this operation");
    }

    @SuppressWarnings({"deprecation", "removal"})
    @Override
    public ApiKeyInfo findByKeyHash(String keyHash) {
        return dispatch(new FindByKeyHashQueryActuator.FindByKeyHashQuery(keyHash));
    }

    // -- Organization query delegation ----------------------------------------

    @Override
    public java.util.List<OrganizationInfo> listOrganizations(int offset, int limit) {
        return dispatch(new ListOrganizationsQueryActuator.ListOrganizationsQuery(offset, limit));
    }

    @Override
    public OrganizationInfo getOrganization(String orgId) {
        return dispatch(new GetOrganizationQueryActuator.GetOrganizationQuery(orgId));
    }

    @Override
    public java.util.List<OrgMemberInfo> listOrganizationMembers(
            String orgId, int offset, int limit) {
        return dispatch(new ListOrgMembersQueryActuator.ListOrgMembersQuery(orgId, offset, limit));
    }
}
