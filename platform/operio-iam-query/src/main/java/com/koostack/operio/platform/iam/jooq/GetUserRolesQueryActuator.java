package com.koostack.operio.platform.iam.jooq;

import com.koostack.operio.core.cqrs.QueryActuator;
import com.koostack.operio.core.metrics.MetricPublisherService;
import java.util.Set;
import java.util.stream.Collectors;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.RoleRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Query actuator that retrieves roles for a user by internal ID.
 */
public class GetUserRolesQueryActuator
        implements QueryActuator<GetUserRolesQueryActuator.GetUserRolesQuery, Set<String>> {

    private static final Logger LOG = LoggerFactory.getLogger(GetUserRolesQueryActuator.class);

    private final Keycloak adminClient;
    private final KeycloakUserMapper userMapper;

    public GetUserRolesQueryActuator(Keycloak adminClient, KeycloakUserMapper userMapper) {
        this.adminClient = adminClient;
        this.userMapper = userMapper;
    }

    /**
     * Query input for getting user roles by internal ID.
     *
     * @param realmId the Keycloak realm
     * @param userId  the user's internal ID (operio-user-id attribute)
     */
    public record GetUserRolesQuery(String realmId, String userId) {}

    @Override
    public Set<String> execute(GetUserRolesQuery query) {
        String kcUserId = userMapper.resolveKcUserId(query.realmId(), query.userId());
        if (kcUserId == null) return Set.of();
        return fetchRoles(query.realmId(), kcUserId);
    }

    private Set<String> fetchRoles(String realmId, String kcUserId) {
        try {
            return adminClient
                    .realm(realmId)
                    .users()
                    .get(kcUserId)
                    .roles()
                    .realmLevel()
                    .listEffective()
                    .stream()
                    .map(RoleRepresentation::getName)
                    .collect(Collectors.toUnmodifiableSet());
        } catch (jakarta.ws.rs.NotFoundException e) {
            return Set.of();
        }
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}

