package com.koostack.operio.platform.tenant.jooq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.koostack.operio.core.platform.core.CoreCommandService;
import com.koostack.operio.core.platform.core.CoreEventTypes;
import com.koostack.operio.core.platform.iam.IamCommandService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Event consumer that reacts to core domain events to manage tenant
 * infrastructure lifecycle.
 *
 * <p>Handles:
 * <ul>
 *   <li>{@code core.tenant.provisioning-started} → provisions Postgres schema,
 *       Flyway migrations, Redis ACL, generates 3 API tokens (DEV/TEST/PROD),
 *       then emits {@code TenantProvisioned} via {@link CoreCommandService}.</li>
 *   <li>{@code core.tenant.deleted} → tears down Postgres schema and
 *       Redis ACL user.</li>
 * </ul>
 *
 * <p>Compatible with the {@code StreamHandler.onEvent} signature used by
 * the platform stream consumer infrastructure.
 */
public class TenantManagerProjectionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(TenantManagerProjectionHandler.class);

    private static final String[] API_KEY_ENVIRONMENTS = {"DEV", "TEST", "PROD"};

    private final JooqTenantManagerService tenantManager;
    private final CoreCommandService<?> coreCommandService;
    private final IamCommandService<?> iamCommandService;
    private final ObjectMapper mapper;

    public TenantManagerProjectionHandler(
            JooqTenantManagerService tenantManager,
            CoreCommandService<?> coreCommandService,
            IamCommandService<?> iamCommandService) {
        this.tenantManager = tenantManager;
        this.coreCommandService = coreCommandService;
        this.iamCommandService = iamCommandService;
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
                case CoreEventTypes.TENANT_PROVISIONING_STARTED -> handleProvisioningStarted(payload);
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

        // 2. Generate 3 API tokens (DEV, TEST, PROD)
        for (String env : API_KEY_ENVIRONMENTS) {
            String keyName = tenantId + "-" + env.toLowerCase();
            IamCommandService.CreateApiTokenResult tokenResult =
                iamCommandService.createApiToken(tenantId, keyName, env);
            LOG.info("Generated {} API token for tenant '{}': keyId={}, prefix={}",
                env, tenantId, tokenResult.keyId(), tokenResult.keyPrefix());
        }

        // 3. Emit TenantProvisioned event
        coreCommandService.provisionTenant(tenantId, result.schemaName(), result.redisAclUser());
        LOG.info("Tenant '{}' fully provisioned: schema={}, redisUser={}, apiKeys=3",
            tenantId, result.schemaName(), result.redisAclUser());
    }

    private void handleTenantDeleted(String payload) throws Exception {
        JsonNode node = mapper.readTree(payload);
        String tenantId = node.get("tenantId").asText();

        LOG.info("Tearing down infrastructure for tenant '{}'", tenantId);
        tenantManager.dropTenant(tenantId);
        LOG.info("Tenant '{}' infrastructure torn down", tenantId);
    }
}
