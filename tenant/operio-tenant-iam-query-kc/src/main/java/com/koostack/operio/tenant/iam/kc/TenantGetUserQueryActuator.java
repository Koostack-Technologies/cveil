package com.koostack.operio.tenant.iam.kc;

import com.koostack.operio.core.cqrs.QueryActuator;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.models.IdpUser;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Query actuator that retrieves a single tenant user by internal ID.
 */
public class TenantGetUserQueryActuator
        implements QueryActuator<TenantGetUserQueryActuator.TenantGetUserQuery, IdpUser> {

    private static final Logger LOG = LoggerFactory.getLogger(TenantGetUserQueryActuator.class);

    private final Keycloak adminClient;
    private final TenantKeycloakUserMapper userMapper;

    public TenantGetUserQueryActuator(Keycloak adminClient, TenantKeycloakUserMapper userMapper) {
        this.adminClient = adminClient;
        this.userMapper = userMapper;
    }

    /**
     * Query input for getting a tenant user by ID.
     *
     * @param tenantId the tenant identifier
     * @param userId   the user's internal ID (operio-user-id attribute)
     */
    public record TenantGetUserQuery(String tenantId, String userId) {}

    @Override
    public IdpUser execute(TenantGetUserQuery query) {
        String realmId = TenantKeycloakUserMapper.toRealmId(query.tenantId());
        String kcUserId = userMapper.resolveKcUserId(realmId, query.userId());
        if (kcUserId == null) return null;
        try {
            UserRepresentation user =
                    adminClient.realm(realmId).users().get(kcUserId).toRepresentation();
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
