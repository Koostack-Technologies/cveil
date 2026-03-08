package com.koostack.operio.tenant.iam.kc;

import com.koostack.operio.core.cqrs.QueryActuator;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.models.IdpUser;
import java.util.List;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Query actuator that finds a tenant user by username.
 */
public class TenantFindUserByUsernameQueryActuator
        implements QueryActuator<
                TenantFindUserByUsernameQueryActuator.TenantFindUserByUsernameQuery, IdpUser> {

    private static final Logger LOG =
            LoggerFactory.getLogger(TenantFindUserByUsernameQueryActuator.class);

    private final Keycloak adminClient;
    private final TenantKeycloakUserMapper userMapper;

    public TenantFindUserByUsernameQueryActuator(
            Keycloak adminClient, TenantKeycloakUserMapper userMapper) {
        this.adminClient = adminClient;
        this.userMapper = userMapper;
    }

    /**
     * Query input for finding a tenant user by username.
     *
     * @param tenantId the tenant identifier
     * @param username the username to search for
     */
    public record TenantFindUserByUsernameQuery(String tenantId, String username) {}

    @Override
    public IdpUser execute(TenantFindUserByUsernameQuery query) {
        String realmId = TenantKeycloakUserMapper.toRealmId(query.tenantId());
        LOG.debug(
                "Finding user by username in tenant '{}': {}",
                query.tenantId(),
                query.username());
        List<UserRepresentation> users =
                adminClient
                        .realm(realmId)
                        .users()
                        .searchByUsername(query.username(), true);
        return users.isEmpty() ? null : userMapper.toIdpUser(users.get(0));
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
