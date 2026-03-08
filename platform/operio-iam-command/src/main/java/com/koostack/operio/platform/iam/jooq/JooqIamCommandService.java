package com.koostack.operio.platform.iam.jooq;

import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.platform.iam.IamCommandService;
import com.koostack.operio.core.platform.iam.IamQueryService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Base64;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keycloak-native platform IAM command service.
 *
 * <p>Publishes domain events for user/service-account operations. The projection handler translates
 * these events into Keycloak Admin API calls.
 */
public class JooqIamCommandService implements IamCommandService<JooqIamCommandConfig> {

    private static final Logger LOG = LoggerFactory.getLogger(JooqIamCommandService.class);
    private static final String API_KEY_PREFIX_LITERAL = "opr_live_";
    private static final int KEY_PREFIX_LENGTH = 16;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private RegisterUserCommandActuator registerUserActuator;
    private final SuspendUserCommandActuator suspendUserActuator =
            new SuspendUserCommandActuator(null);
    private final ActivateUserCommandActuator activateUserActuator =
            new ActivateUserCommandActuator(null);
    private final CreateServiceAccountCommandActuator createServiceAccountActuator =
            new CreateServiceAccountCommandActuator(null);
    private final RevokeServiceAccountCommandActuator revokeServiceAccountActuator =
            new RevokeServiceAccountCommandActuator(null);
    private final AssignRoleCommandActuator assignRoleActuator =
            new AssignRoleCommandActuator(null);
    private final RemoveRoleCommandActuator removeRoleActuator =
            new RemoveRoleCommandActuator(null);

    private EventService<?> eventService;
    private DataSource platformDataSource;
    private IamQueryService<?> queryService;

    /** Set the query service for email/username → UUID resolution. */
    public void setQueryService(IamQueryService<?> queryService) {
        this.queryService = queryService;
    }

    @Override
    public String getName() {
        return "keycloak-iam-command";
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public Class<JooqIamCommandConfig> getConfigurationType() {
        return JooqIamCommandConfig.class;
    }

    @Override
    public void configure(JooqIamCommandConfig config) {
        this.eventService = config.eventService();
        this.platformDataSource = config.platformDataSource();
        this.registerUserActuator = new RegisterUserCommandActuator(config.keycloakAdmin());
        LOG.info("KeycloakIamCommandService configured");
    }

    // -- User operations ------------------------------------------------------

    @Override
    public RegisterUserResult registerUser(
            String realmId,
            String username,
            String email,
            String fullName,
            Set<String> roles,
            boolean temporaryPassword) {
        var command =
                new RegisterUserCommandActuator.RegisterUserCommand(
                        realmId, username, email, fullName, roles, temporaryPassword);
        var result = registerUserActuator.execute(eventService, command);
        return new RegisterUserResult(result.username(), result.email(), result.realmId());
    }

    @Override
    public void suspendUser(String realmId, String emailOrUsername) {
        String userId = requireUserId(realmId, emailOrUsername);
        var command =
                new SuspendUserCommandActuator.SuspendUserCommand(realmId, userId, emailOrUsername);
        suspendUserActuator.execute(eventService, command);
    }

    @Override
    public void activateUser(String realmId, String emailOrUsername) {
        String userId = requireUserId(realmId, emailOrUsername);
        var command =
                new ActivateUserCommandActuator.ActivateUserCommand(
                        realmId, userId, emailOrUsername);
        activateUserActuator.execute(eventService, command);
    }

    // -- Service account operations -------------------------------------------

    @Override
    public CreateServiceAccountResult createServiceAccount(
            String realmId, String name, Set<String> roles) {
        var command =
                new CreateServiceAccountCommandActuator.CreateServiceAccountCommand(
                        realmId, name, roles);
        var result = createServiceAccountActuator.execute(eventService, command);
        return new CreateServiceAccountResult(
                result.clientId(), result.clientSecret(), result.realmId());
    }

    @Override
    public void revokeServiceAccount(String realmId, String clientId) {
        var command =
                new RevokeServiceAccountCommandActuator.RevokeServiceAccountCommand(
                        realmId, clientId);
        revokeServiceAccountActuator.execute(eventService, command);
    }

    // -- Role operations ------------------------------------------------------

    @Override
    public void assignRole(String realmId, String emailOrUsername, String role) {
        String userId = requireUserId(realmId, emailOrUsername);
        var command =
                new AssignRoleCommandActuator.AssignRoleCommand(
                        realmId, userId, role, emailOrUsername);
        assignRoleActuator.execute(eventService, command);
    }

    @Override
    public void removeRole(String realmId, String emailOrUsername, String role) {
        String userId = requireUserId(realmId, emailOrUsername);
        var command =
                new RemoveRoleCommandActuator.RemoveRoleCommand(
                        realmId, userId, role, emailOrUsername);
        removeRoleActuator.execute(eventService, command);
    }

    // -- Legacy API key compat ------------------------------------------------

    @SuppressWarnings("deprecation")
    @Override
    public CreateApiTokenResult createApiToken(
            String tenantId, String name, String environment, String roles) {
        if (platformDataSource == null) {
            throw new IllegalStateException(
                    "platformDataSource not configured — cannot create API keys");
        }

        String rawKey = generateRawApiKey();
        String keyHash = sha256Hex(rawKey);
        String keyPrefix = rawKey.substring(0, Math.min(rawKey.length(), KEY_PREFIX_LENGTH));

        try (Connection conn = platformDataSource.getConnection()) {
            // Ensure system user exists (the FK on api_key.platform_user_id requires it)
            try (PreparedStatement upsert =
                    conn.prepareStatement(
                            "INSERT INTO platform_iam.platform_user (platform_user_id, email,"
                                + " status) VALUES ('system', 'system@operio.internal', 'active')"
                                + " ON CONFLICT (platform_user_id) DO NOTHING")) {
                upsert.executeUpdate();
            }

            String sql =
                    "INSERT INTO platform_iam.api_key (key_hash, key_prefix, name, tenant_id,"
                        + " platform_user_id, subject, roles, status) VALUES (?, ?, ?, ?, 'system',"
                        + " ?, ?, 'active')";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, keyHash);
                ps.setString(2, keyPrefix);
                ps.setString(3, name);
                ps.setString(4, tenantId);
                ps.setString(5, name);
                ps.setString(6, roles);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to create API key: " + e.getMessage(), e);
        }

        LOG.info("API key created: tenant={}, subject={}, prefix={}", tenantId, name, keyPrefix);
        return new CreateApiTokenResult(keyPrefix, keyPrefix, rawKey);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void revokeApiToken(String tenantId, String keyId) {
        if (platformDataSource == null) {
            revokeServiceAccount(tenantId, keyId);
            return;
        }

        String sql = "UPDATE platform_iam.api_key SET status = 'revoked' WHERE key_prefix = ?";
        try (Connection conn = platformDataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, keyId);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException("Failed to revoke API key: " + e.getMessage(), e);
        }
        LOG.info("API key revoked: tenant={}, keyId={}", tenantId, keyId);
    }

    // -- Internal helpers -----------------------------------------------------

    private String requireUserId(String realmId, String emailOrUsername) {
        if (queryService == null) {
            throw new IllegalStateException(
                    "queryService not configured — call setQueryService() after wiring");
        }
        String userId = queryService.resolveUserId(realmId, emailOrUsername);
        if (userId == null) {
            throw new IllegalArgumentException(
                    "User not found: " + emailOrUsername + " in realm " + realmId);
        }
        return userId;
    }

    private static String generateRawApiKey() {
        byte[] bytes = new byte[24];
        SECURE_RANDOM.nextBytes(bytes);
        String random = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return API_KEY_PREFIX_LITERAL + random;
    }

    static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 not available", e);
        }
    }
}
