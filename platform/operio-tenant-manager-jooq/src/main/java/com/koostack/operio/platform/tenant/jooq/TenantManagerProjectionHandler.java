package com.koostack.operio.platform.tenant.jooq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.koostack.operio.core.platform.core.CoreCommandService;
import com.koostack.operio.core.platform.core.CoreEventTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Event consumer that reacts to core domain events to manage tenant
 * infrastructure lifecycle.
 *
 * <p>Handles:
 * <ul>
 *   <li>{@code core.tenant.created} → provisions Postgres schema, Flyway
 *       migrations, Redis ACL, then emits {@code TenantProvisioned} via
 *       {@link CoreCommandService}.</li>
 *   <li>{@code core.tenant.deleted} → tears down Postgres schema and
 *       Redis ACL user.</li>
 * </ul>
 *
 * <p>Compatible with the {@code StreamHandler.onEvent} signature used by
 * the platform stream consumer infrastructure.
 */
public class TenantManagerProjectionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(TenantManagerProjectionHandler.class);

    private final JooqTenantManagerService tenantManager;
    private final CoreCommandService<?> coreCommandService;
    private final ObjectMapper mapper;

    public TenantManagerProjectionHandler(
            JooqTenantManagerService tenantManager,
            CoreCommandService<?> coreCommandService) {
        this.tenantManager = tenantManager;
        this.coreCommandService = coreCommandService;
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
                case CoreEventTypes.TENANT_CREATED -> handleTenantCreated(payload);
                case CoreEventTypes.TENANT_DELETED -> handleTenantDeleted(payload);
                default -> LOG.trace("Ignoring unhandled event type: {}", eventType);
            }
        } catch (Exception e) {
            LOG.error("Failed to handle event type={} messageId={}", eventType, messageId, e);
            throw new RuntimeException("TenantManagerProjectionHandler failed for " + eventType, e);
        }
    }

    private void handleTenantCreated(String payload) throws Exception {
        JsonNode node = mapper.readTree(payload);
        String tenantId = node.get("tenantId").asText();

        LOG.info("Provisioning infrastructure for tenant '{}'", tenantId);
        JooqTenantManagerService.ProvisionResult result = tenantManager.provisionTenant(tenantId);

        coreCommandService.provisionTenant(tenantId, result.schemaName(), result.redisAclUser());
        LOG.info("Tenant '{}' provisioned and TenantProvisioned event emitted", tenantId);
    }

    private void handleTenantDeleted(String payload) throws Exception {
        JsonNode node = mapper.readTree(payload);
        String tenantId = node.get("tenantId").asText();

        LOG.info("Tearing down infrastructure for tenant '{}'", tenantId);
        tenantManager.dropTenant(tenantId);
        LOG.info("Tenant '{}' infrastructure torn down", tenantId);
    }
}
