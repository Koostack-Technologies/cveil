package com.koostack.operio.platform.iam.jooq;

import com.koostack.operio.core.cqrs.QueryActuator;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.models.IdpUser;
import java.util.List;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Query actuator that finds a user by username.
 */
public class FindUserByUsernameQueryActuator
        implements QueryActuator<FindUserByUsernameQueryActuator.FindUserByUsernameQuery, IdpUser> {

    private static final Logger LOG =
            LoggerFactory.getLogger(FindUserByUsernameQueryActuator.class);

    private final Keycloak adminClient;
    private final KeycloakUserMapper userMapper;

    public FindUserByUsernameQueryActuator(Keycloak adminClient, KeycloakUserMapper userMapper) {
        this.adminClient = adminClient;
        this.userMapper = userMapper;
    }

    /**
     * Query input for finding a user by username.
     *
     * @param realmId  the Keycloak realm
     * @param username the username to search for
     */
    public record FindUserByUsernameQuery(String realmId, String username) {}

    @Override
    public IdpUser execute(FindUserByUsernameQuery query) {
        LOG.debug("Finding user by username in realm '{}': {}", query.realmId(), query.username());
        List<UserRepresentation> users =
                adminClient
                        .realm(query.realmId())
                        .users()
                        .searchByUsername(query.username(), true);
        return users.isEmpty() ? null : userMapper.toIdpUser(users.get(0));
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}

