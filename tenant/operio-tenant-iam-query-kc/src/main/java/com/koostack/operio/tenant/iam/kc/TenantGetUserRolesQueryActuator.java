package com.koostack.operio.tenant.iam.kc;

import com.koostack.operio.core.cqrs.QueryActuator;
import com.koostack.operio.core.metrics.MetricPublisherService;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Query actuator that retrieves roles for a tenant user by internal ID.
 */
public class TenantGetUserRolesQueryActuator
        implements QueryActuator<
                TenantGetUserRolesQueryActuator.TenantGetUserRolesQuery, Set<String>> {

    private static final Logger LOG =
            LoggerFactory.getLogger(TenantGetUserRolesQueryActuator.class);

    private final TenantKeycloakUserMapper userMapper;

    public TenantGetUserRolesQueryActuator(TenantKeycloakUserMapper userMapper) {
        this.userMapper = userMapper;
    }

    /**
     * Query input for getting tenant user roles by internal ID.
     *
     * @param tenantId the tenant identifier
     * @param userId   the user's internal ID (operio-user-id attribute)
     */
    public record TenantGetUserRolesQuery(String tenantId, String userId) {}

    @Override
    public Set<String> execute(TenantGetUserRolesQuery query) {
        String realmId = TenantKeycloakUserMapper.toRealmId(query.tenantId());
        String kcUserId = userMapper.resolveKcUserId(realmId, query.userId());
        if (kcUserId == null) return Set.of();
        return userMapper.fetchRoles(realmId, kcUserId);
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
