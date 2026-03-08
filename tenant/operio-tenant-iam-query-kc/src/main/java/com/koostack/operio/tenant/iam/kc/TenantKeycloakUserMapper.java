package com.koostack.operio.tenant.iam.kc;

import com.koostack.operio.core.models.IdpUser;
import com.koostack.operio.core.tenant.iam.TenantIamQueryService.ServiceAccountInfo;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

/**
 * Shared mapping and resolution utilities for tenant-scoped Keycloak user representations.
 *
 * <p>Extracted from {@link KeycloakTenantIamQueryService} to be reused by individual query
 * actuators. Mirrors the platform-level {@code KeycloakUserMapper}.
 */
public class TenantKeycloakUserMapper {

    private final Keycloak adminClient;

    public TenantKeycloakUserMapper(Keycloak adminClient) {
        this.adminClient = adminClient;
    }

    /** Convert a Keycloak {@link UserRepresentation} to the domain {@link IdpUser}. */
    public IdpUser toIdpUser(UserRepresentation user) {
        String status =
                Boolean.TRUE.equals(user.isEnabled())
                        ? (Boolean.TRUE.equals(user.isEmailVerified()) ? "active" : "unverified")
                        : "suspended";
        String fullName =
                ((user.getFirstName() != null ? user.getFirstName() : "")
                                + " "
                                + (user.getLastName() != null ? user.getLastName() : ""))
                        .trim();
        Long created = user.getCreatedTimestamp();
        return new IdpUser(
                user.getUsername(),
                user.getEmail(),
                fullName.isEmpty() ? null : fullName,
                status,
                created != null ? Instant.ofEpochMilli(created) : Instant.now());
    }

    /** Read the {@code operio-user-id} custom attribute from a Keycloak user. */
    public String readOperioUserId(UserRepresentation user) {
        var attrs = user.getAttributes();
        if (attrs != null && attrs.containsKey("operio-user-id")) {
            var values = attrs.get("operio-user-id");
            if (values != null && !values.isEmpty()) {
                return values.get(0);
            }
        }
        return null;
    }

    /**
     * Resolve a command-side userId ({@code operio-user-id} attribute) to the actual Keycloak user
     * ID.
     */
    public String resolveKcUserId(String realmId, String userId) {
        List<UserRepresentation> users =
                adminClient.realm(realmId).users().searchByAttributes("operio-user-id:" + userId);
        if (!users.isEmpty()) return users.get(0).getId();
        return null;
    }

    /** Convert a Keycloak {@link ClientRepresentation} to a {@link ServiceAccountInfo}. */
    public ServiceAccountInfo toServiceAccountInfo(ClientRepresentation client, String realmId) {
        return new ServiceAccountInfo(
                client.getClientId(),
                client.getName(),
                realmId,
                Set.of(),
                Boolean.TRUE.equals(client.isEnabled()) ? "active" : "disabled",
                null);
    }

    /**
     * Fetch the effective realm-level role names for a Keycloak user.
     *
     * @return an unmodifiable set of role names, or empty set if the user is not found
     */
    public Set<String> fetchRoles(String realmId, String kcUserId) {
        try {
            return adminClient
                    .realm(realmId)
                    .users()
                    .get(kcUserId)
                    .roles()
                    .realmLevel()
                    .listEffective()
                    .stream()
                    .map(org.keycloak.representations.idm.RoleRepresentation::getName)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        } catch (jakarta.ws.rs.NotFoundException e) {
            return Set.of();
        }
    }

    /** Convert a {@code tenantId} to the Keycloak realm ID. */
    public static String toRealmId(String tenantId) {
        return "t_" + tenantId;
    }
}
