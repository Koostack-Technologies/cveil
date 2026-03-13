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

/** Query actuator that retrieves roles for a user identified by email. */
public class GetUserRolesByEmailQueryActuator
        implements QueryActuator<
                GetUserRolesByEmailQueryActuator.GetUserRolesByEmailQuery, Set<String>> {

    private static final Logger LOG =
            LoggerFactory.getLogger(GetUserRolesByEmailQueryActuator.class);

    private final Keycloak adminClient;

    public GetUserRolesByEmailQueryActuator(Keycloak adminClient) {
        this.adminClient = adminClient;
    }

    /**
     * Query input for getting user roles by email.
     *
     * @param realmId the Keycloak realm
     * @param email the user's email
     */
    public record GetUserRolesByEmailQuery(String realmId, String email) {}

    @Override
    public Set<String> execute(GetUserRolesByEmailQuery query) {
        List<UserRepresentation> users =
                adminClient.realm(query.realmId()).users().searchByEmail(query.email(), true);
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
