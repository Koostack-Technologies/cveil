package com.koostack.operio.tenant.iam.kc;

import com.koostack.operio.core.cqrs.QueryActuator;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.tenant.iam.TenantIamQueryService.ServiceAccountInfo;
import java.util.List;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Query actuator that retrieves a single tenant service account by client ID.
 */
public class TenantGetServiceAccountQueryActuator
        implements QueryActuator<
                TenantGetServiceAccountQueryActuator.TenantGetServiceAccountQuery,
                ServiceAccountInfo> {

    private static final Logger LOG =
            LoggerFactory.getLogger(TenantGetServiceAccountQueryActuator.class);

    private final Keycloak adminClient;
    private final TenantKeycloakUserMapper userMapper;

    public TenantGetServiceAccountQueryActuator(
            Keycloak adminClient, TenantKeycloakUserMapper userMapper) {
        this.adminClient = adminClient;
        this.userMapper = userMapper;
    }

    /**
     * Query input for getting a tenant service account by client ID.
     *
     * @param tenantId the tenant identifier
     * @param clientId the Keycloak client ID
     */
    public record TenantGetServiceAccountQuery(String tenantId, String clientId) {}

    @Override
    public ServiceAccountInfo execute(TenantGetServiceAccountQuery query) {
        String realmId = TenantKeycloakUserMapper.toRealmId(query.tenantId());
        LOG.debug(
                "Getting service account '{}' in tenant '{}' (realm '{}')",
                query.clientId(),
                query.tenantId(),
                realmId);
        RealmResource realm = adminClient.realm(realmId);
        List<ClientRepresentation> clients = realm.clients().findByClientId(query.clientId());
        if (clients.isEmpty()) return null;
        return userMapper.toServiceAccountInfo(clients.get(0), realmId);
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
