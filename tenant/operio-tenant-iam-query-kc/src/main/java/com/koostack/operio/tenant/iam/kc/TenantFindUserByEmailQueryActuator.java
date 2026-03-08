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
 * Query actuator that finds a tenant user by email address.
 */
public class TenantFindUserByEmailQueryActuator
        implements QueryActuator<
                TenantFindUserByEmailQueryActuator.TenantFindUserByEmailQuery, IdpUser> {

    private static final Logger LOG =
            LoggerFactory.getLogger(TenantFindUserByEmailQueryActuator.class);

    private final Keycloak adminClient;
    private final TenantKeycloakUserMapper userMapper;

    public TenantFindUserByEmailQueryActuator(
            Keycloak adminClient, TenantKeycloakUserMapper userMapper) {
        this.adminClient = adminClient;
        this.userMapper = userMapper;
    }

    /**
     * Query input for finding a tenant user by email.
     *
     * @param tenantId the tenant identifier
     * @param email    the email to search for
     */
    public record TenantFindUserByEmailQuery(String tenantId, String email) {}

    @Override
    public IdpUser execute(TenantFindUserByEmailQuery query) {
        String realmId = TenantKeycloakUserMapper.toRealmId(query.tenantId());
        LOG.debug("Finding user by email in tenant '{}': {}", query.tenantId(), query.email());
        List<UserRepresentation> users =
                adminClient.realm(realmId).users().searchByEmail(query.email(), true);
        return users.isEmpty() ? null : userMapper.toIdpUser(users.get(0));
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
