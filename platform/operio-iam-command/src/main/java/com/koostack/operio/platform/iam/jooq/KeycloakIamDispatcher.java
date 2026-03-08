package com.koostack.operio.platform.iam.jooq;

import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.cqrs.CommandDispatcher;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keycloak-native IAM command dispatcher.
 *
 * <p>Implements both the generic {@link CommandDispatcher} SPI (actuator-based routing) and the
 * legacy {@link IamCommandService} interface (thin delegation layer for backward compatibility with
 * existing REST resources and holders).
 *
 * <p>All non-deprecated command methods delegate to the dispatcher, which routes to the appropriate
 * {@link Actuator} by command record type.
 */
public class KeycloakIamDispatcher
        implements CommandDispatcher, IamCommandService<JooqIamCommandConfig> {

    private static final Logger LOG = LoggerFactory.getLogger(KeycloakIamDispatcher.class);
    private static final String API_KEY_PREFIX_LITERAL = "opr_live_";
    private static final int KEY_PREFIX_LENGTH = 16;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final Map<Class<?>, Actuator<?, ?>> actuators = new ConcurrentHashMap<>();

    private EventService<?> eventService;
    private DataSource platformDataSource;
    private IamQueryService<?> queryService;

    // -- CommandDispatcher SPI -------------------------------------------------

    @Override
    @SuppressWarnings("unchecked")
    public <I, O> O dispatch(I input) {
        Actuator<I, O> actuator = (Actuator<I, O>) actuators.get(input.getClass());
        if (actuator == null) {
            throw new IllegalArgumentException(
                    "No actuator registered for command type: " + input.getClass().getName());
        }
        return actuator.execute(eventService, input);
    }

    // -- Plugin SPI -----------------------------------------------------------

    /** Set the query service for email/username → UUID resolution. */
    public void setQueryService(IamQueryService<?> queryService) {
        this.queryService = queryService;
    }

    @Override
    public String getName() {
        return "keycloak-iam-dispatcher";
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

        // Register all actuators
        var kc = config.keycloakAdmin();
        actuators.put(
                RegisterUserCommandActuator.RegisterUserCommand.class,
                new RegisterUserCommandActuator(kc));
        actuators.put(
                SuspendUserCommandActuator.SuspendUserCommand.class,
                new SuspendUserCommandActuator(kc));
        actuators.put(
                ActivateUserCommandActuator.ActivateUserCommand.class,
                new ActivateUserCommandActuator(kc));
        actuators.put(
                CreateServiceAccountCommandActuator.CreateServiceAccountCommand.class,
                new CreateServiceAccountCommandActuator(kc));
        actuators.put(
                RevokeServiceAccountCommandActuator.RevokeServiceAccountCommand.class,
                new RevokeServiceAccountCommandActuator(kc));
        actuators.put(
                AssignRoleCommandActuator.AssignRoleCommand.class,
                new AssignRoleCommandActuator(kc));
        actuators.put(
                RemoveRoleCommandActuator.RemoveRoleCommand.class,
                new RemoveRoleCommandActuator(kc));

        LOG.info("KeycloakIamDispatcher configured with {} actuators", actuators.size());
    }

    /**
     * Register auth actuators that depend on the query service. Must be called after {@link
     * #setQueryService(IamQueryService)}.
     */
    public void registerAuthActuators() {
        if (queryService == null) {
            LOG.warn("Cannot register auth actuators — queryService is not set");
            return;
        }
        actuators.put(
                AuthenticateCommandActuator.AuthenticateCommand.class,
                new AuthenticateCommandActuator(queryService));
        actuators.put(
                RefreshTokenCommandActuator.RefreshTokenCommand.class,
                new RefreshTokenCommandActuator(queryService));
        actuators.put(
                LogoutCommandActuator.LogoutCommand.class, new LogoutCommandActuator(queryService));
        LOG.info("KeycloakIamDispatcher registered auth actuators (total={})", actuators.size());
    }

    // -- IamCommandService delegation (backward compatibility) -----------------

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
        RegisterUserCommandActuator.RegisterUserResult result = dispatch(command);
        return new RegisterUserResult(result.username(), result.email(), result.realmId());
    }

    @Override
    public void suspendUser(String realmId, String emailOrUsername) {
        dispatch(new SuspendUserCommandActuator.SuspendUserCommand(realmId, emailOrUsername));
    }

    @Override
    public void activateUser(String realmId, String emailOrUsername) {
        dispatch(new ActivateUserCommandActuator.ActivateUserCommand(realmId, emailOrUsername));
    }

    @Override
    public CreateServiceAccountResult createServiceAccount(
            String realmId, String name, Set<String> roles) {
        var command =
                new CreateServiceAccountCommandActuator.CreateServiceAccountCommand(
                        realmId, name, roles);
        CreateServiceAccountCommandActuator.CreateServiceAccountResult result = dispatch(command);
        return new CreateServiceAccountResult(
                result.clientId(), result.clientSecret(), result.realmId());
    }

    @Override
    public void revokeServiceAccount(String realmId, String clientId) {
        dispatch(
                new RevokeServiceAccountCommandActuator.RevokeServiceAccountCommand(
                        realmId, clientId));
    }

    @Override
    public void assignRole(String realmId, String emailOrUsername, String role) {
        dispatch(
                new AssignRoleCommandActuator.AssignRoleCommand(
                        realmId, emailOrUsername, role));
    }

    @Override
    public void removeRole(String realmId, String emailOrUsername, String role) {
        dispatch(
                new RemoveRoleCommandActuator.RemoveRoleCommand(
                        realmId, emailOrUsername, role));
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
