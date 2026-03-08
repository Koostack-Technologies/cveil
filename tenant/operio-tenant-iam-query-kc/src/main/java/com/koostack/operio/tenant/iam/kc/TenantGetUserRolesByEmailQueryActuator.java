package com.koostack.operio.tenant.iam.kc;

import com.koostack.operio.core.cqrs.QueryActuator;
import com.koostack.operio.core.metrics.MetricPublisherService;
import java.util.List;
import java.util.Set;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Query actuator that retrieves roles for a tenant user identified by email.
 */
public class TenantGetUserRolesByEmailQueryActuator
        implements QueryActuator<
                TenantGetUserRolesByEmailQueryActuator.TenantGetUserRolesByEmailQuery,
                Set<String>> {

    private static final Logger LOG =
            LoggerFactory.getLogger(TenantGetUserRolesByEmailQueryActuator.class);

    private final Keycloak adminClient;
    private final TenantKeycloakUserMapper userMapper;

    public TenantGetUserRolesByEmailQueryActuator(
            Keycloak adminClient, TenantKeycloakUserMapper userMapper) {
        this.adminClient = adminClient;
        this.userMapper = userMapper;
    }

    /**
     * Query input for getting tenant user roles by email.
     *
     * @param tenantId the tenant identifier
     * @param email    the user's email
     */
    public record TenantGetUserRolesByEmailQuery(String tenantId, String email) {}

    @Override
    public Set<String> execute(TenantGetUserRolesByEmailQuery query) {
        String realmId = TenantKeycloakUserMapper.toRealmId(query.tenantId());
        List<UserRepresentation> users =
                adminClient.realm(realmId).users().searchByEmail(query.email(), true);
        if (users.isEmpty()) return Set.of();
        return userMapper.fetchRoles(realmId, users.get(0).getId());
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
