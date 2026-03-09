package com.koostack.operio.tenant.iam.kc;

import com.koostack.operio.core.cqrs.QueryActuator;
import com.koostack.operio.core.metrics.MetricPublisherService;
import java.util.List;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Query actuator that resolves a tenant user's internal ID from an email or username. */
public class TenantResolveUserIdQueryActuator
        implements QueryActuator<
                TenantResolveUserIdQueryActuator.TenantResolveUserIdQuery, String> {

    private static final Logger LOG =
            LoggerFactory.getLogger(TenantResolveUserIdQueryActuator.class);

    private final Keycloak adminClient;
    private final TenantKeycloakUserMapper userMapper;

    public TenantResolveUserIdQueryActuator(
            Keycloak adminClient, TenantKeycloakUserMapper userMapper) {
        this.adminClient = adminClient;
        this.userMapper = userMapper;
    }

    /**
     * Query input for resolving a tenant user ID from email or username.
     *
     * @param tenantId the tenant identifier
     * @param emailOrUsername the user's email or username
     */
    public record TenantResolveUserIdQuery(String tenantId, String emailOrUsername) {}

    @Override
    public String execute(TenantResolveUserIdQuery query) {
        String realmId = TenantKeycloakUserMapper.toRealmId(query.tenantId());
        // Try by email first
        List<UserRepresentation> users =
                adminClient.realm(realmId).users().searchByEmail(query.emailOrUsername(), true);
        if (users.isEmpty()) {
            // Try by username
            users =
                    adminClient
                            .realm(realmId)
                            .users()
                            .searchByUsername(query.emailOrUsername(), true);
        }
        if (users.isEmpty()) return null;
        return userMapper.readOperioUserId(users.get(0));
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
