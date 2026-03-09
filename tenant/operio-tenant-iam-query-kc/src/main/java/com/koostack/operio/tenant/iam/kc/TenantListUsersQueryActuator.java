package com.koostack.operio.tenant.iam.kc;

import com.koostack.operio.core.cqrs.QueryActuator;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.models.IdpUser;
import java.util.List;
import org.keycloak.admin.client.Keycloak;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Query actuator that lists all users in a tenant's Keycloak realm. */
public class TenantListUsersQueryActuator
        implements QueryActuator<TenantListUsersQueryActuator.TenantListUsersQuery, List<IdpUser>> {

    private static final Logger LOG = LoggerFactory.getLogger(TenantListUsersQueryActuator.class);

    private final Keycloak adminClient;
    private final TenantKeycloakUserMapper userMapper;

    public TenantListUsersQueryActuator(Keycloak adminClient, TenantKeycloakUserMapper userMapper) {
        this.adminClient = adminClient;
        this.userMapper = userMapper;
    }

    /**
     * Query input for listing tenant users.
     *
     * @param tenantId the tenant identifier
     */
    public record TenantListUsersQuery(String tenantId) {}

    @Override
    public List<IdpUser> execute(TenantListUsersQuery query) {
        String realmId = TenantKeycloakUserMapper.toRealmId(query.tenantId());
        LOG.debug("Listing users for tenant '{}' (realm '{}')", query.tenantId(), realmId);
        return adminClient.realm(realmId).users().list().stream()
                .map(userMapper::toIdpUser)
                .toList();
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
