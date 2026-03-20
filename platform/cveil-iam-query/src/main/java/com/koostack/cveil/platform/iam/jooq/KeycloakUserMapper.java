package com.koostack.cveil.platform.iam.jooq;

import com.koostack.cveil.core.models.IdpUser;
import java.time.Instant;
import java.util.List;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.UserRepresentation;

/**
 * Shared mapping and resolution utilities for Keycloak user representations.
 *
 * <p>Extracted from {@link JooqIamQueryService} to be reused by individual query actuators.
 */
public class KeycloakUserMapper {

    private final Keycloak adminClient;

    public KeycloakUserMapper(Keycloak adminClient) {
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
                created != null ? Instant.ofEpochMilli(created) : Instant.now(),
                Boolean.TRUE.equals(user.isEmailVerified()));
    }

    /** Read the {@code cveil-user-id} custom attribute from a Keycloak user. */
    public String readCveilUserId(UserRepresentation user) {
        var attrs = user.getAttributes();
        if (attrs != null && attrs.containsKey("cveil-user-id")) {
            var values = attrs.get("cveil-user-id");
            if (values != null && !values.isEmpty()) {
                return values.get(0);
            }
        }
        return null;
    }

    /**
     * Resolve a command-side userId ({@code cveil-user-id} attribute) to the actual Keycloak user
     * ID.
     */
    public String resolveKcUserId(String realmId, String userId) {
        List<UserRepresentation> users =
                adminClient.realm(realmId).users().searchByAttributes("cveil-user-id:" + userId);
        if (!users.isEmpty()) return users.get(0).getId();
        return null;
    }
}
