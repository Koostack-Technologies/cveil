package com.koostack.operio.platform.iam.jooq;

import com.koostack.operio.core.PlatformConstants;
import com.koostack.operio.core.event.DomainEvent;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.platform.events.ApiKeyGeneratedEvent;
import com.koostack.operio.core.platform.events.ApiKeyRevokedEvent;
import com.koostack.operio.core.platform.events.IdpConfigUpdatedEvent;
import com.koostack.operio.core.platform.iam.IamCommandService;
import com.koostack.operio.core.platform.iam.IamEventTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

public class JooqIamCommandService implements IamCommandService<JooqIamCommandConfig> {

    private static final Logger LOG = LoggerFactory.getLogger(JooqIamCommandService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String AGGREGATE_TYPE = "iam-api-key";

    private EventService<?> eventService;

    @Override public String getName() { return "jooq-iam-command"; }
    @Override public int getPriority() { return 100; }
    @Override public Class<JooqIamCommandConfig> getConfigurationType() { return JooqIamCommandConfig.class; }

    @Override
    public void configure(JooqIamCommandConfig config) {
        this.eventService = config.eventService();
        LOG.info("JooqIamCommandService configured");
    }

    @Override
    public CreateApiTokenResult createApiToken(String tenantId, String name, String environment, String roles) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (environment == null || environment.isBlank()) {
            throw new IllegalArgumentException("environment is required");
        }

        String keyId = UUID.randomUUID().toString();
        String plaintextKey = generateSecureKey();
        String keyPrefix = plaintextKey.substring(0, Math.min(8, plaintextKey.length()));
        String keyHash = sha256Hex(plaintextKey);

        var payload = new ApiKeyGeneratedEvent(
            tenantId, keyId, keyPrefix, keyHash, name, environment, roles, null);
        publishEvent(IamEventTypes.API_KEY_GENERATED, tenantId, payload);

        LOG.info("API token created: tenantId={}, keyId={}, env={}", tenantId, keyId, environment);
        return new CreateApiTokenResult(keyId, keyPrefix, plaintextKey);
    }

    @Override
    public void revokeApiToken(String tenantId, String keyId) {
        var payload = new ApiKeyRevokedEvent(tenantId, keyId);
        publishEvent(IamEventTypes.API_KEY_REVOKED, tenantId, payload);
        LOG.info("API token revoked: tenantId={}, keyId={}", tenantId, keyId);
    }

    // -- Internal helpers -----------------------------------------------------

    private void publishEvent(String eventType, String tenantId, DomainEvent payload) {
        UUID aggregateId = deriveAggregateId(tenantId);
        AggregateKey key = new AggregateKey(
            aggregateId, AGGREGATE_TYPE, PlatformConstants.PLATFORM_TENANT_ID);
        eventService.publishEvent(
            PublishDomainEventInput.of(PlatformConstants.PLATFORM_TENANT_ID, eventType, key, payload));
    }

    private static UUID deriveAggregateId(String tenantId) {
        return UUID.nameUUIDFromBytes(("iam:" + tenantId).getBytes(StandardCharsets.UTF_8));
    }

    private static String generateSecureKey() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return "opk_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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
