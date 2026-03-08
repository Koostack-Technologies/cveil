package com.koostack.operio.platform.iam.jooq;

import com.koostack.operio.core.cqrs.QueryActuator;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.models.IdpUser;
import java.util.List;
import org.keycloak.admin.client.Keycloak;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Query actuator that lists all users in a given Keycloak realm.
 */
public class ListUsersQueryActuator
        implements QueryActuator<ListUsersQueryActuator.ListUsersQuery, List<IdpUser>> {

    private static final Logger LOG = LoggerFactory.getLogger(ListUsersQueryActuator.class);

    private final Keycloak adminClient;
    private final KeycloakUserMapper userMapper;

    public ListUsersQueryActuator(Keycloak adminClient, KeycloakUserMapper userMapper) {
        this.adminClient = adminClient;
        this.userMapper = userMapper;
    }

    /**
     * Query input for listing users.
     *
     * @param realmId the Keycloak realm
     */
    public record ListUsersQuery(String realmId) {}

    @Override
    public List<IdpUser> execute(ListUsersQuery query) {
        LOG.debug("Listing users for realm '{}'", query.realmId());
        return adminClient.realm(query.realmId()).users().list().stream()
                .map(userMapper::toIdpUser)
                .toList();
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}

