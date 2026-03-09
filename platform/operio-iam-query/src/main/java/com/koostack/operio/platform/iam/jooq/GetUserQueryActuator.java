package com.koostack.operio.platform.iam.jooq;

import com.koostack.operio.core.cqrs.QueryActuator;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.models.IdpUser;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Query actuator that retrieves a single user by internal ID. */
public class GetUserQueryActuator
        implements QueryActuator<GetUserQueryActuator.GetUserQuery, IdpUser> {

    private static final Logger LOG = LoggerFactory.getLogger(GetUserQueryActuator.class);

    private final Keycloak adminClient;
    private final KeycloakUserMapper userMapper;

    public GetUserQueryActuator(Keycloak adminClient, KeycloakUserMapper userMapper) {
        this.adminClient = adminClient;
        this.userMapper = userMapper;
    }

    /**
     * Query input for getting a user by ID.
     *
     * @param realmId the Keycloak realm
     * @param userId the user's internal ID (operio-user-id attribute)
     */
    public record GetUserQuery(String realmId, String userId) {}

    @Override
    public IdpUser execute(GetUserQuery query) {
        String kcUserId = userMapper.resolveKcUserId(query.realmId(), query.userId());
        if (kcUserId == null) return null;
        try {
            UserRepresentation user =
                    adminClient.realm(query.realmId()).users().get(kcUserId).toRepresentation();
            return userMapper.toIdpUser(user);
        } catch (jakarta.ws.rs.NotFoundException e) {
            return null;
        }
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
