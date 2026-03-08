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
 * Query actuator that finds a user by email address.
 */
public class FindUserByEmailQueryActuator
        implements QueryActuator<FindUserByEmailQueryActuator.FindUserByEmailQuery, IdpUser> {

    private static final Logger LOG = LoggerFactory.getLogger(FindUserByEmailQueryActuator.class);

    private final Keycloak adminClient;
    private final KeycloakUserMapper userMapper;

    public FindUserByEmailQueryActuator(Keycloak adminClient, KeycloakUserMapper userMapper) {
        this.adminClient = adminClient;
        this.userMapper = userMapper;
    }

    /**
     * Query input for finding a user by email.
     *
     * @param realmId the Keycloak realm
     * @param email   the email to search for
     */
    public record FindUserByEmailQuery(String realmId, String email) {}

    @Override
    public IdpUser execute(FindUserByEmailQuery query) {
        LOG.debug("Finding user by email in realm '{}': {}", query.realmId(), query.email());
        List<UserRepresentation> users =
                adminClient.realm(query.realmId()).users().searchByEmail(query.email(), true);
        return users.isEmpty() ? null : userMapper.toIdpUser(users.get(0));
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}

