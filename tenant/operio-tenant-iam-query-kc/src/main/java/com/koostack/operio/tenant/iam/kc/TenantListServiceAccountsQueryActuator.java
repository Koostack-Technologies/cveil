package com.koostack.operio.tenant.iam.kc;

import com.koostack.operio.core.cqrs.QueryActuator;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.tenant.iam.TenantIamQueryService.ServiceAccountInfo;
import java.util.List;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Query actuator that lists all service accounts in a tenant's Keycloak realm.
 */
public class TenantListServiceAccountsQueryActuator
        implements QueryActuator<
                TenantListServiceAccountsQueryActuator.TenantListServiceAccountsQuery,
                List<ServiceAccountInfo>> {

    private static final Logger LOG =
            LoggerFactory.getLogger(TenantListServiceAccountsQueryActuator.class);

    private final Keycloak adminClient;
    private final TenantKeycloakUserMapper userMapper;
    private final String operioClientId;

    public TenantListServiceAccountsQueryActuator(
            Keycloak adminClient, TenantKeycloakUserMapper userMapper, String operioClientId) {
        this.adminClient = adminClient;
        this.userMapper = userMapper;
        this.operioClientId = operioClientId;
    }

    /**
     * Query input for listing tenant service accounts.
     *
     * @param tenantId the tenant identifier
     */
    public record TenantListServiceAccountsQuery(String tenantId) {}

    @Override
    public List<ServiceAccountInfo> execute(TenantListServiceAccountsQuery query) {
        String realmId = TenantKeycloakUserMapper.toRealmId(query.tenantId());
        LOG.debug("Listing service accounts for tenant '{}' (realm '{}')", query.tenantId(), realmId);
        RealmResource realm = adminClient.realm(realmId);
        return realm.clients().findAll().stream()
                .filter(c -> Boolean.TRUE.equals(c.isServiceAccountsEnabled()))
                .filter(c -> !operioClientId.equals(c.getClientId()))
                .map(c -> userMapper.toServiceAccountInfo(c, realmId))
                .toList();
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
