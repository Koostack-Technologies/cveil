package com.koostack.operio.platform.tenant.jooq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.koostack.operio.core.platform.core.CoreCommandService;
import com.koostack.operio.core.platform.core.CoreEventTypes;
import com.koostack.operio.core.platform.iam.IamCommandService;
import com.koostack.operio.plugin.redis.common.JedisProvider;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.exceptions.JedisDataException;

/**
 * Event consumer that reacts to core domain events to manage tenant infrastructure lifecycle.
 *
 * <p>Handles:
 *
 * <ul>
 *   <li>{@code core.tenant.provisioning-started} → provisions Postgres schema, Flyway migrations,
 *       Redis ACL, generates 3 API tokens (DEV/TEST/PROD), then emits {@code TenantProvisioned} via
 *       {@link CoreCommandService}.
 *   <li>{@code core.tenant.deleted} → tears down Postgres schema and Redis ACL user.
 * </ul>
 *
 * <p>Compatible with the {@code StreamHandler.onEvent} signature used by the platform stream
 * consumer infrastructure.
 */
public class TenantManagerProjectionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(TenantManagerProjectionHandler.class);

    private static final String[] API_KEY_ENVIRONMENTS = {"DEV", "TEST", "PROD"};
    private static final String DEFAULT_TENANT_ROLES =
            "admin:read,admin:write,consent:read,consent:write";
    /** Must match {@code RedisEventPublisher.STREAM_KEY_PREFIX} in operio-tenant-event-jooq. */
    private static final String TENANT_STREAM_PREFIX = "operio:stream:tenant:";
    /** Default consumer group name created on the tenant stream at provisioning time. */
    private static final String DEFAULT_CONSUMER_GROUP = "default";

    private final JooqTenantManagerService tenantManager;
    private final CoreCommandService<?> coreCommandService;
    private final IamCommandService<?> iamCommandService;
    private final JedisProvider jedisProvider;
    private final ObjectMapper mapper;

    public TenantManagerProjectionHandler(
            JooqTenantManagerService tenantManager,
            CoreCommandService<?> coreCommandService,
            IamCommandService<?> iamCommandService,
            JedisProvider jedisProvider) {
        this.tenantManager = tenantManager;
        this.coreCommandService = coreCommandService;
        this.iamCommandService = iamCommandService;
        this.jedisProvider = jedisProvider;
        this.mapper = new ObjectMapper();
    }

    /** Handle a platform stream event. */
    public void onEvent(String streamKey, String messageId, Map<String, String> fields) {
        String eventType = fields.get("eventType");
        String payload = fields.get("payload");
        if (eventType == null || payload == null) {
            LOG.warn("Skipping event with missing eventType or payload: messageId={}", messageId);
            return;
        }

        try {
            switch (eventType) {
                case CoreEventTypes.TENANT_PROVISIONING_STARTED -> handleProvisioningStarted(
                        payload);
                case CoreEventTypes.TENANT_DELETED -> handleTenantDeleted(payload);
                default -> LOG.trace("Ignoring unhandled event type: {}", eventType);
            }
        } catch (Exception e) {
            LOG.error("Failed to handle event type={} messageId={}", eventType, messageId, e);
            throw new RuntimeException("TenantManagerProjectionHandler failed for " + eventType, e);
        }
    }

    private void handleProvisioningStarted(String payload) throws Exception {
        JsonNode node = mapper.readTree(payload);
        String tenantId = node.get("tenantId").asText();

        // 1. Provision Postgres schema + Flyway migrations + Redis ACL
        LOG.info("Provisioning infrastructure for tenant '{}'", tenantId);
        JooqTenantManagerService.ProvisionResult result = tenantManager.provisionTenant(tenantId);

        // 1b. Persist Redis ACL credentials so TenantAwareJedisProvider can
        // look them up later (the ProvisionResult carries the plaintext password
        // that was used to create the ACL user — we must store it now).
        if (result.redisAclPassword() != null) {
            tenantManager
                    .getTenantRepository()
                    .updateRedisAclCredentials(
                            tenantId,
                            result.redisAclUser(),
                            result.redisAclPassword(),
                            result.redisAclPassHash(),
                            "tenant-provisioning");
            LOG.info("Stored Redis ACL credentials for tenant '{}'", tenantId);
        }

        // 2. Create per-tenant Redis event stream so it exists before TENANT_PROVISIONED
        //    is emitted. Uses XGROUP CREATE with MKSTREAM to atomically create both
        //    the stream and a default consumer group.
        String streamKey = TENANT_STREAM_PREFIX + tenantId;
        try {
            jedisProvider
                    .get()
                    .xgroupCreate(streamKey, DEFAULT_CONSUMER_GROUP, new StreamEntryID(), true);
            LOG.info("Created Redis event stream '{}' for tenant '{}'", streamKey, tenantId);
        } catch (JedisDataException e) {
            if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                LOG.debug("Redis stream '{}' already exists for tenant '{}'", streamKey, tenantId);
            } else {
                throw e;
            }
        }

        // 3. Generate 3 API tokens (DEV, TEST, PROD)
        for (String env : API_KEY_ENVIRONMENTS) {
            String keyName = tenantId + "-" + env.toLowerCase();
            IamCommandService.CreateApiTokenResult tokenResult =
                    iamCommandService.createApiToken(tenantId, keyName, env, DEFAULT_TENANT_ROLES);
            LOG.info(
                    "Generated {} API token for tenant '{}': keyId={}, prefix={}",
                    env,
                    tenantId,
                    tokenResult.keyId(),
                    tokenResult.keyPrefix());
        }

        // 4. Emit TenantProvisioned event
        coreCommandService.provisionTenant(tenantId, result.schemaName(), result.redisAclUser());
        LOG.info(
                "Tenant '{}' fully provisioned: schema={}, redisUser={}, apiKeys=3",
                tenantId,
                result.schemaName(),
                result.redisAclUser());
    }

    private void handleTenantDeleted(String payload) throws Exception {
        JsonNode node = mapper.readTree(payload);
        String tenantId = node.get("tenantId").asText();

        LOG.info("Tearing down infrastructure for tenant '{}'", tenantId);
        tenantManager.dropTenant(tenantId);
        LOG.info("Tenant '{}' infrastructure torn down", tenantId);
    }
}
