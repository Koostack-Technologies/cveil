package com.koostack.operio.platform.core.jooq;

import static org.jooq.impl.DSL.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.koostack.operio.core.platform.core.CoreEventTypes;
import java.time.OffsetDateTime;
import java.util.Map;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Platform stream event handler that materializes core domain events (tenant lifecycle) into the
 * {@code platform_core.tenant} read model.
 *
 * <p>All operations are idempotent upserts so that replaying the event stream produces the same
 * result.
 */
public class CoreProjectionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(CoreProjectionHandler.class);

    private static final org.jooq.Table<?> TENANT = table(name("platform_core", "tenant"));
    private static final org.jooq.Field<String> TENANT_ID = field(name("tenant_id"), String.class);
    private static final org.jooq.Field<String> DISPLAY_NAME =
            field(name("display_name"), String.class);
    private static final org.jooq.Field<String> PLAN = field(name("plan"), String.class);
    private static final org.jooq.Field<String> STATUS = field(name("status"), String.class);
    private static final org.jooq.Field<String> SCHEMA_NAME =
            field(name("schema_prefix"), String.class);
    private static final org.jooq.Field<String> REDIS_ACL_USER =
            field(name("redis_acl_user"), String.class);
    private static final org.jooq.Field<OffsetDateTime> CREATED_AT =
            field(name("created_at"), OffsetDateTime.class);
    private static final org.jooq.Field<OffsetDateTime> UPDATED_AT =
            field(name("updated_at"), OffsetDateTime.class);

    private final DSLContext dsl;
    private final ObjectMapper mapper;

    public CoreProjectionHandler(DSLContext dsl) {
        this.dsl = dsl;
        this.mapper = new ObjectMapper();
    }

    /**
     * Handle a platform stream event. Compatible with the {@code StreamEventHandler.onEvent}
     * signature.
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
                case CoreEventTypes.TENANT_DISPLAY_NAME_UPDATED -> handleDisplayNameUpdated(
                        payload);
                case CoreEventTypes.TENANT_PLAN_CHANGED -> handlePlanChanged(payload);
                case CoreEventTypes.TENANT_SUSPENDED -> handleSuspended(payload);
                case CoreEventTypes.TENANT_ACTIVATED -> handleActivated(payload);
                case CoreEventTypes.TENANT_DELETED -> handleDeleted(payload);
                case CoreEventTypes.TENANT_PROVISIONED -> handleProvisioned(payload);
                default -> LOG.trace("Ignoring unhandled event type: {}", eventType);
            }
        } catch (Exception e) {
            LOG.error("Failed to project event type={} messageId={}", eventType, messageId, e);
            throw new RuntimeException("Projection failed for " + eventType, e);
        }
    }

    private void handleTenantCreated(String payload) throws Exception {
        var node = mapper.readTree(payload);
        String id = node.get("tenantId").asText();
        String displayName = node.get("displayName").asText();
        String plan = node.get("plan").asText();
        OffsetDateTime now = OffsetDateTime.now();

        dsl.insertInto(TENANT)
                .set(TENANT_ID, id)
                .set(DISPLAY_NAME, displayName)
                .set(PLAN, plan)
                .set(STATUS, "created")
                .set(CREATED_AT, now)
                .set(UPDATED_AT, now)
                .onConflict(TENANT_ID)
                .doUpdate()
                .set(DISPLAY_NAME, displayName)
                .set(PLAN, plan)
                .set(UPDATED_AT, now)
                .execute();
    }

    private void handleDisplayNameUpdated(String payload) throws Exception {
        var node = mapper.readTree(payload);
        String id = node.get("tenantId").asText();
        String displayName = node.get("displayName").asText();

        dsl.update(TENANT)
                .set(DISPLAY_NAME, displayName)
                .set(UPDATED_AT, OffsetDateTime.now())
                .where(TENANT_ID.eq(id))
                .execute();
    }

    private void handlePlanChanged(String payload) throws Exception {
        var node = mapper.readTree(payload);
        String id = node.get("tenantId").asText();
        String newPlan = node.get("newPlan").asText();

        dsl.update(TENANT)
                .set(PLAN, newPlan)
                .set(UPDATED_AT, OffsetDateTime.now())
                .where(TENANT_ID.eq(id))
                .execute();
    }

    private void handleSuspended(String payload) throws Exception {
        var node = mapper.readTree(payload);
        String id = node.get("tenantId").asText();

        dsl.update(TENANT)
                .set(STATUS, "suspended")
                .set(UPDATED_AT, OffsetDateTime.now())
                .where(TENANT_ID.eq(id))
                .execute();
    }

    private void handleActivated(String payload) throws Exception {
        var node = mapper.readTree(payload);
        String id = node.get("tenantId").asText();

        dsl.update(TENANT)
                .set(STATUS, "active")
                .set(UPDATED_AT, OffsetDateTime.now())
                .where(TENANT_ID.eq(id))
                .execute();
    }

    private void handleDeleted(String payload) throws Exception {
        var node = mapper.readTree(payload);
        String id = node.get("tenantId").asText();

        dsl.update(TENANT)
                .set(STATUS, "deleted")
                .set(UPDATED_AT, OffsetDateTime.now())
                .where(TENANT_ID.eq(id))
                .execute();
    }

    private void handleProvisioned(String payload) throws Exception {
        var node = mapper.readTree(payload);
        String id = node.get("tenantId").asText();
        String schemaPrefix = node.get("schemaPrefix").asText();
        String redisAclUser = node.has("redisAclUser") ? node.get("redisAclUser").asText() : null;

        dsl.update(TENANT)
                .set(STATUS, "active")
                .set(SCHEMA_NAME, schemaPrefix)
                .set(REDIS_ACL_USER, redisAclUser)
                .set(UPDATED_AT, OffsetDateTime.now())
                .where(TENANT_ID.eq(id))
                .execute();
    }
}
