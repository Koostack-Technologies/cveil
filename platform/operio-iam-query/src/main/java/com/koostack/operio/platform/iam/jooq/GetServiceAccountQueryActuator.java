package com.koostack.operio.platform.iam.jooq;

import com.koostack.operio.core.cqrs.QueryActuator;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.platform.iam.IamQueryService.ServiceAccountInfo;
import java.util.List;
import java.util.Set;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Query actuator that retrieves a single service account by client ID.
 */
public class GetServiceAccountQueryActuator
        implements QueryActuator<
                GetServiceAccountQueryActuator.GetServiceAccountQuery, ServiceAccountInfo> {

    private static final Logger LOG =
            LoggerFactory.getLogger(GetServiceAccountQueryActuator.class);

    private final Keycloak adminClient;

    public GetServiceAccountQueryActuator(Keycloak adminClient) {
        this.adminClient = adminClient;
    }

    /**
     * Query input for getting a service account by client ID.
     *
     * @param realmId  the Keycloak realm
     * @param clientId the Keycloak client ID
     */
    public record GetServiceAccountQuery(String realmId, String clientId) {}

    @Override
    public ServiceAccountInfo execute(GetServiceAccountQuery query) {
        LOG.debug(
                "Getting service account '{}' in realm '{}'",
                query.clientId(),
                query.realmId());
        RealmResource realm = adminClient.realm(query.realmId());
        List<ClientRepresentation> clients = realm.clients().findByClientId(query.clientId());
        if (clients.isEmpty()) return null;
        return toServiceAccountInfo(clients.get(0), query.realmId());
    }

    private ServiceAccountInfo toServiceAccountInfo(ClientRepresentation client, String realmId) {
        return new ServiceAccountInfo(
                client.getClientId(),
                client.getName(),
                realmId,
                Set.of(),
                Boolean.TRUE.equals(client.isEnabled()) ? "active" : "disabled",
                null);
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}

