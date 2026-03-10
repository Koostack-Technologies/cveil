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
                case CoreEventTypes.TENANT_CREATED -> LOG.info(
                        "TenantManager: ignoring TENANT_CREATED (provisioning removed)");
                case CoreEventTypes.TENANT_DELETED -> handleTenantDeleted(payload);
                default -> LOG.trace("Ignoring unhandled event type: {}", eventType);
            }
        } catch (Exception e) {
            LOG.error("Failed to handle event type={} messageId={}", eventType, messageId, e);
            throw new RuntimeException("TenantManagerProjectionHandler failed for " + eventType, e);
        }
    }

    /**
     * @deprecated Provisioning pipeline removed in single-schema refactor.
     */
    @Deprecated(forRemoval = true)
    private void handleProvisioningStarted(String payload) throws Exception {
        LOG.warn("handleProvisioningStarted called but provisioning pipeline is removed");
    }

    private void handleTenantDeleted(String payload) throws Exception {
        JsonNode node = mapper.readTree(payload);
        String tenantId = node.get("tenantId").asText();

        LOG.info("Tearing down infrastructure for tenant '{}'", tenantId);
        tenantManager.dropTenant(tenantId);
        LOG.info("Tenant '{}' infrastructure torn down", tenantId);
    }
}
