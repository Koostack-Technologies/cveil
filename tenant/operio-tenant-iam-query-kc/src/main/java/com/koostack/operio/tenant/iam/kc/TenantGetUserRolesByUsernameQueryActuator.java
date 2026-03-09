package com.koostack.operio.tenant.iam.kc;

import com.koostack.operio.core.cqrs.QueryActuator;
import com.koostack.operio.core.metrics.MetricPublisherService;
import java.util.List;
import java.util.Set;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Query actuator that retrieves roles for a tenant user identified by username. */
public class TenantGetUserRolesByUsernameQueryActuator
        implements QueryActuator<
                TenantGetUserRolesByUsernameQueryActuator.TenantGetUserRolesByUsernameQuery,
                Set<String>> {

    private static final Logger LOG =
            LoggerFactory.getLogger(TenantGetUserRolesByUsernameQueryActuator.class);

    private final Keycloak adminClient;
    private final TenantKeycloakUserMapper userMapper;

    public TenantGetUserRolesByUsernameQueryActuator(
            Keycloak adminClient, TenantKeycloakUserMapper userMapper) {
        this.adminClient = adminClient;
        this.userMapper = userMapper;
    }

    /**
     * Query input for getting tenant user roles by username.
     *
     * @param tenantId the tenant identifier
     * @param username the user's username
     */
    public record TenantGetUserRolesByUsernameQuery(String tenantId, String username) {}

    @Override
    public Set<String> execute(TenantGetUserRolesByUsernameQuery query) {
        String realmId = TenantKeycloakUserMapper.toRealmId(query.tenantId());
        List<UserRepresentation> users =
                adminClient.realm(realmId).users().searchByUsername(query.username(), true);
        if (users.isEmpty()) return Set.of();
        return userMapper.fetchRoles(realmId, users.get(0).getId());
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
