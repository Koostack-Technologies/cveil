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

/** Query actuator that lists all service accounts (confidential clients) in a Keycloak realm. */
public class ListServiceAccountsQueryActuator
        implements QueryActuator<
                ListServiceAccountsQueryActuator.ListServiceAccountsQuery,
                List<ServiceAccountInfo>> {

    private static final Logger LOG =
            LoggerFactory.getLogger(ListServiceAccountsQueryActuator.class);

    private final Keycloak adminClient;
    private final String operioClientId;

    public ListServiceAccountsQueryActuator(Keycloak adminClient, String operioClientId) {
        this.adminClient = adminClient;
        this.operioClientId = operioClientId;
    }

    /**
     * Query input for listing service accounts.
     *
     * @param realmId the Keycloak realm
     */
    public record ListServiceAccountsQuery(String realmId) {}

    @Override
    public List<ServiceAccountInfo> execute(ListServiceAccountsQuery query) {
        LOG.debug("Listing service accounts for realm '{}'", query.realmId());
        RealmResource realm = adminClient.realm(query.realmId());
        return realm.clients().findAll().stream()
                .filter(c -> Boolean.TRUE.equals(c.isServiceAccountsEnabled()))
                .filter(c -> !operioClientId.equals(c.getClientId()))
                .map(c -> toServiceAccountInfo(c, query.realmId()))
                .toList();
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
