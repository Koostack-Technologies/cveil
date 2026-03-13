package com.koostack.cveil.platform.iam.jooq;

import com.koostack.cveil.core.cqrs.QueryActuator;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Query actuator that retrieves roles for a user identified by username. */
public class GetUserRolesByUsernameQueryActuator
        implements QueryActuator<
                GetUserRolesByUsernameQueryActuator.GetUserRolesByUsernameQuery, Set<String>> {

    private static final Logger LOG =
            LoggerFactory.getLogger(GetUserRolesByUsernameQueryActuator.class);

    private final Keycloak adminClient;

    public GetUserRolesByUsernameQueryActuator(Keycloak adminClient) {
        this.adminClient = adminClient;
    }

    /**
     * Query input for getting user roles by username.
     *
     * @param realmId the Keycloak realm
     * @param username the user's username
     */
    public record GetUserRolesByUsernameQuery(String realmId, String username) {}

    @Override
    public Set<String> execute(GetUserRolesByUsernameQuery query) {
        List<UserRepresentation> users =
                adminClient.realm(query.realmId()).users().searchByUsername(query.username(), true);
        if (users.isEmpty()) return Set.of();
        return fetchRoles(query.realmId(), users.get(0).getId());
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
