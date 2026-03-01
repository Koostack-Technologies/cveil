package com.koostack.operio.platform.iam.jooq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.koostack.operio.core.platform.iam.IamEventTypes;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.jooq.impl.DSL.*;

/**
 * Platform stream event handler that materializes IAM domain events
 * into the {@code platform_iam.api_key} read model.
 *
 * <p>All operations are idempotent upserts so that replaying the event
 * stream produces the same result.
 */
public class IamProjectionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(IamProjectionHandler.class);

    private static final org.jooq.Table<?> API_KEY = table(name("platform_iam", "api_key"));
    private static final org.jooq.Field<String> KEY_ID = field(name("key_id"), String.class);
    private static final org.jooq.Field<String> KEY_HASH = field(name("key_hash"), String.class);
    private static final org.jooq.Field<String> KEY_PREFIX = field(name("key_prefix"), String.class);
    private static final org.jooq.Field<String> NAME = field(name("name"), String.class);
    private static final org.jooq.Field<String> TENANT_ID = field(name("tenant_id"), String.class);
    private static final org.jooq.Field<String> ENVIRONMENT = field(name("environment"), String.class);
    private static final org.jooq.Field<String> STATUS = field(name("status"), String.class);
    private static final org.jooq.Field<OffsetDateTime> EXPIRES_AT = field(name("expires_at"), OffsetDateTime.class);
    private static final org.jooq.Field<OffsetDateTime> CREATED_AT = field(name("created_at"), OffsetDateTime.class);
    private static final org.jooq.Field<OffsetDateTime> UPDATED_AT = field(name("updated_at"), OffsetDateTime.class);

    private final DSLContext dsl;
    private final ObjectMapper mapper;

    public IamProjectionHandler(DSLContext dsl) {
        this.dsl = dsl;
        this.mapper = new ObjectMapper();
    }

    /**
     * Handle a platform stream event.
     */
    public void onEvent(String streamKey, String messageId, Map<String, String> fields) {
        String eventType = fields.get("eventType");
        String payload = fields.get("payload");
        if (eventType == null || payload == null) {
            LOG.warn("Skipping event with missing eventType or payload: messageId={}", messageId);
            return;
        }

        try {
            switch (eventType) {
                case IamEventTypes.API_KEY_GENERATED -> handleApiKeyGenerated(payload);
                case IamEventTypes.API_KEY_REVOKED -> handleApiKeyRevoked(payload);
                default -> LOG.trace("Ignoring unhandled event type: {}", eventType);
            }
        } catch (Exception e) {
            LOG.error("Failed to project event type={} messageId={}", eventType, messageId, e);
            throw new RuntimeException("IamProjectionHandler failed for " + eventType, e);
        }
    }

    private void handleApiKeyGenerated(String payload) throws Exception {
        var node = mapper.readTree(payload);
        String tenantId = node.get("tenantId").asText();
        String keyId = node.get("keyId").asText();
        String keyHash = node.get("keyHash").asText();
        String keyPrefix = node.get("keyPrefix").asText();
        String name = node.has("name") && !node.get("name").isNull()
            ? node.get("name").asText() : "Default Key";
        String environment = node.has("environment") && !node.get("environment").isNull()
            ? node.get("environment").asText() : "PROD";
        OffsetDateTime now = OffsetDateTime.now();

        dsl.insertInto(API_KEY)
            .set(KEY_ID, keyId)
            .set(KEY_HASH, keyHash)
            .set(KEY_PREFIX, keyPrefix)
            .set(NAME, name)
            .set(TENANT_ID, tenantId)
            .set(ENVIRONMENT, environment)
            .set(STATUS, "active")
            .set(CREATED_AT, now)
            .set(UPDATED_AT, now)
            .onConflict(KEY_ID)
            .doUpdate()
            .set(STATUS, "active")
            .set(UPDATED_AT, now)
            .execute();

        LOG.debug("Projected API key: tenantId={}, keyId={}, env={}", tenantId, keyId, environment);
    }

    private void handleApiKeyRevoked(String payload) throws Exception {
        var node = mapper.readTree(payload);
        String keyId = node.get("keyId").asText();

        dsl.update(API_KEY)
            .set(STATUS, "revoked")
            .set(UPDATED_AT, OffsetDateTime.now())
            .where(KEY_ID.eq(keyId))
            .execute();

        LOG.debug("Revoked API key: keyId={}", keyId);
    }
}
