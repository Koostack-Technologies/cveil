package com.koostack.operio.platform.iam.jooq;

import com.koostack.operio.core.cqrs.QueryActuator;
import com.koostack.operio.core.metrics.MetricPublisherService;
import java.util.List;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Query actuator that resolves a user's internal ID from an email or username.
 */
public class ResolveUserIdQueryActuator
        implements QueryActuator<ResolveUserIdQueryActuator.ResolveUserIdQuery, String> {

    private static final Logger LOG = LoggerFactory.getLogger(ResolveUserIdQueryActuator.class);

    private final Keycloak adminClient;
    private final KeycloakUserMapper userMapper;

    public ResolveUserIdQueryActuator(Keycloak adminClient, KeycloakUserMapper userMapper) {
        this.adminClient = adminClient;
        this.userMapper = userMapper;
    }

    /**
     * Query input for resolving a user ID from email or username.
     *
     * @param realmId         the Keycloak realm
     * @param emailOrUsername the user's email or username
     */
    public record ResolveUserIdQuery(String realmId, String emailOrUsername) {}

    @Override
    public String execute(ResolveUserIdQuery query) {
        // Try by email first
        List<UserRepresentation> users =
                adminClient
                        .realm(query.realmId())
                        .users()
                        .searchByEmail(query.emailOrUsername(), true);
        if (users.isEmpty()) {
            // Try by username
            users =
                    adminClient
                            .realm(query.realmId())
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

