package com.koostack.operio.platform.iam.jooq;

import com.koostack.operio.core.cqrs.QueryActuator;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.platform.iam.IamQueryService.OrganizationInfo;
import com.koostack.operio.plugin.platform.keycloak.KeycloakConfig;
import com.koostack.operio.plugin.platform.keycloak.KeycloakOrganizationService;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Query actuator that returns the authenticated user's profile and organization memberships.
 *
 * <p>Resolves user details and org list from the Keycloak Admin API using the user's Keycloak ID
 * (the JWT {@code sub} claim).
 */
public class GetCurrentUserQueryActuator
        implements QueryActuator<
                GetCurrentUserQueryActuator.GetCurrentUserQuery,
                GetCurrentUserQueryActuator.CurrentUserResult> {

    private static final Logger LOG = LoggerFactory.getLogger(GetCurrentUserQueryActuator.class);

    private final Keycloak adminClient;
    private final KeycloakConfig keycloakConfig;
    private final KeycloakOrganizationService orgService;

    public GetCurrentUserQueryActuator(
            Keycloak adminClient,
            KeycloakConfig keycloakConfig,
            KeycloakOrganizationService orgService) {
        this.adminClient = adminClient;
        this.keycloakConfig = keycloakConfig;
        this.orgService = orgService;
    }

    /**
     * Query input for getting the current user's profile.
     *
     * @param userId the Keycloak user ID (JWT sub claim)
     */
    public record GetCurrentUserQuery(String userId) {}

    /**
     * Result containing user profile and org memberships.
     *
     * @param username the user's username
     * @param email the user's email
     * @param fullName the user's full name
     * @param roles the user's realm roles
     * @param status active or suspended
     * @param createdAt when the user was created
     * @param organizations list of orgs the user belongs to
     */
    public record CurrentUserResult(
            String userId,
            String username,
            String email,
            String fullName,
            Set<String> roles,
            String status,
            java.time.Instant createdAt,
            List<OrganizationInfo> organizations) {}

    @Override
    public CurrentUserResult execute(GetCurrentUserQuery query) {
        LOG.debug("Getting current user: userId={}", query.userId());

        RealmResource realm = adminClient.realm(keycloakConfig.operioRealmName());
        UserRepresentation user = realm.users().get(query.userId()).toRepresentation();
        if (user == null) {
            return null;
        }

        Set<String> roles =
                realm.users().get(query.userId()).roles().realmLevel().listEffective().stream()
                        .map(RoleRepresentation::getName)
                        .collect(Collectors.toSet());

        String fullName = buildFullName(user.getFirstName(), user.getLastName());
        String status = Boolean.TRUE.equals(user.isEnabled()) ? "active" : "suspended";

        java.time.Instant createdAt = null;
        if (user.getCreatedTimestamp() != null) {
            createdAt = java.time.Instant.ofEpochMilli(user.getCreatedTimestamp());
        }

        // Resolve org memberships
        List<OrganizationInfo> organizations = List.of();
        if (orgService != null) {
            try {
                organizations = orgService.listUserOrganizations(query.userId());
            } catch (Exception e) {
                LOG.warn("Failed to list orgs for user {}: {}", query.userId(), e.getMessage());
            }
        }

        return new CurrentUserResult(
                query.userId(),
                user.getUsername(),
                user.getEmail(),
                fullName,
                roles,
                status,
                createdAt,
                organizations);
    }

    private String buildFullName(String firstName, String lastName) {
        if (firstName == null && lastName == null) return null;
        if (firstName == null) return lastName;
        if (lastName == null) return firstName;
        return firstName + " " + lastName;
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
