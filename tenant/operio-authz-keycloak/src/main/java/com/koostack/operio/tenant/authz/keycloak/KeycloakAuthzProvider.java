package com.koostack.operio.tenant.authz.keycloak;

import com.koostack.operio.core.tenant.authz.AuthzProvider;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.RoleRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keycloak-backed authorization provider — manages realm-level role assignments for users within a
 * tenant.
 *
 * <p>Each Operio tenant maps to a Keycloak realm. This provider uses the Keycloak Admin REST API to
 * assign, remove, and query realm-level roles.
 *
 * <h2>Thread Safety</h2>
 *
 * <p>This class is thread-safe. The Keycloak admin client is shared across threads.
 */
public class KeycloakAuthzProvider implements AuthzProvider<KeycloakAuthzConfig> {

    private static final Logger LOG = LoggerFactory.getLogger(KeycloakAuthzProvider.class);

    private KeycloakAuthzConfig config;
    private Keycloak adminClient;

    public KeycloakAuthzProvider() {}

    public KeycloakAuthzProvider(KeycloakAuthzConfig config) {
        configure(config);
    }

    // -------------------------------------------------------------------
    // Plugin SPI
    // -------------------------------------------------------------------

    @Override
    public String getName() {
        return "keycloak-authz";
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public Class<KeycloakAuthzConfig> getConfigurationType() {
        return KeycloakAuthzConfig.class;
    }

    @Override
    public void configure(KeycloakAuthzConfig config) {
        this.config = config;
        this.adminClient =
                KeycloakBuilder.builder()
                        .serverUrl(config.serverUrl())
                        .realm(config.adminRealm())
                        .clientId(config.adminClientId())
                        .username(config.adminUsername())
                        .password(config.adminPassword())
                        .build();
    }

    @Override
    public boolean isReady() {
        if (adminClient == null) {
            return false;
        }
        try {
            adminClient.realm(config.adminRealm()).toRepresentation();
            return true;
        } catch (Exception e) {
            LOG.debug("Keycloak authz health check failed: {}", e.getMessage());
            return false;
        }
    }

    // -------------------------------------------------------------------
    // AuthzProvider — Role Management
    // -------------------------------------------------------------------

    @Override
    public void assignRole(String tenantId, String userId, String role) {
        RealmResource realm = adminClient.realm(tenantId);
        RoleRepresentation roleRep = realm.roles().get(role).toRepresentation();
        realm.users().get(userId).roles().realmLevel().add(List.of(roleRep));
        LOG.debug("Assigned role '{}' to user '{}' in tenant '{}'", role, userId, tenantId);
    }

    @Override
    public void removeRole(String tenantId, String userId, String role) {
        RealmResource realm = adminClient.realm(tenantId);
        RoleRepresentation roleRep = realm.roles().get(role).toRepresentation();
        realm.users().get(userId).roles().realmLevel().remove(List.of(roleRep));
        LOG.debug("Removed role '{}' from user '{}' in tenant '{}'", role, userId, tenantId);
    }

    @Override
    public Set<String> getUserRoles(String tenantId, String userId) {
        List<RoleRepresentation> roles =
                adminClient
                        .realm(tenantId)
                        .users()
                        .get(userId)
                        .roles()
                        .realmLevel()
                        .listEffective();

        Set<String> roleNames = new HashSet<>();
        for (RoleRepresentation r : roles) {
            roleNames.add(r.getName());
        }
        return Collections.unmodifiableSet(roleNames);
    }
}
