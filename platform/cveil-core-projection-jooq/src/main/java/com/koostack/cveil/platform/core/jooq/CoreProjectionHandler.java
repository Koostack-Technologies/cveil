package com.koostack.cveil.platform.core.jooq;

import static org.jooq.impl.DSL.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.koostack.cveil.core.platform.core.CoreEventTypes;
import com.koostack.cveil.core.platform.iam.IamEventTypes;
import com.koostack.cveil.core.platform.plan.PlanEventTypes;
import java.time.OffsetDateTime;
import java.util.Map;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Platform stream event handler that materializes core domain events (tenant lifecycle) into the
 * {@code cveiliam.tenant} read model.
 *
 * <p>All operations are idempotent upserts so that replaying the event stream produces the same
 * result.
 */
public class CoreProjectionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(CoreProjectionHandler.class);

    private static final org.jooq.Table<?> TENANT = table(name("cveiliam", "tenant"));
    private static final org.jooq.Field<String> TENANT_ID = field(name("tenant_id"), String.class);
    private static final org.jooq.Field<String> DISPLAY_NAME =
            field(name("display_name"), String.class);
    private static final org.jooq.Field<String> PLAN = field(name("plan"), String.class);
    private static final org.jooq.Field<String> STATUS = field(name("status"), String.class);
    private static final org.jooq.Field<OffsetDateTime> CREATED_AT =
            field(name("created_at"), OffsetDateTime.class);
    private static final org.jooq.Field<OffsetDateTime> UPDATED_AT =
            field(name("updated_at"), OffsetDateTime.class);
    private static final org.jooq.Field<String> REDIS_ACL_USER =
            field(name("redis_acl_user"), String.class);
    private static final org.jooq.Field<String> REDIS_ACL_PASSWORD =
            field(name("redis_acl_password"), String.class);
    private static final org.jooq.Field<String> TENANT_TYPE =
            field(name("tenant_type"), String.class);

    // -- plan table -----------------------------------------------------------
    private static final org.jooq.Table<?> PLAN_TABLE = table(name("cveilcore", "plan"));
    private static final org.jooq.Field<String> PLAN_ID = field(name("plan_id"), String.class);
    private static final org.jooq.Field<String> STRIPE_PRODUCT_ID =
            field(name("stripe_product_id"), String.class);
    private static final org.jooq.Field<String> STRIPE_PRICE_ID =
            field(name("stripe_price_id"), String.class);
    private static final org.jooq.Field<OffsetDateTime> PLAN_UPDATED_AT =
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
                case IamEventTypes.REDIS_PROVISIONED -> handleRedisProvisioned(payload);
                case PlanEventTypes.PLAN_STRIPE_SYNCED -> handlePlanStripeSynced(payload);
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
        String tenantType = node.get("tenantType").asText();
        OffsetDateTime now = OffsetDateTime.now();

        dsl.insertInto(TENANT)
                .set(TENANT_ID, id)
                .set(DISPLAY_NAME, displayName)
                .set(PLAN, plan)
                .set(TENANT_TYPE, tenantType)
                .set(STATUS, "created")
                .set(CREATED_AT, now)
                .set(UPDATED_AT, now)
                .onConflict(TENANT_ID)
                .doUpdate()
                .set(DISPLAY_NAME, displayName)
                .set(PLAN, plan)
                .set(TENANT_TYPE, tenantType)
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

    private void handleRedisProvisioned(String payload) throws Exception {
        var node = mapper.readTree(payload);
        String id = node.get("tenantId").asText();
        String aclUser =
                node.has("aclUser") && !node.get("aclUser").isNull()
                        ? node.get("aclUser").asText()
                        : null;
        String aclPassword =
                node.has("aclPassword") && !node.get("aclPassword").isNull()
                        ? node.get("aclPassword").asText()
                        : null;

        if (aclUser == null || aclPassword == null) {
            LOG.info("Redis provisioned for tenant {} without ACL credentials", id);
            return;
        }

        OffsetDateTime now = OffsetDateTime.now();

        // UPSERT: create a minimal tenant row if it doesn't exist yet (the
        // redis-provisioned event may arrive before tenant-created in the stream).
        // handleTenantCreated will update display_name/plan if it runs later.
        dsl.insertInto(TENANT)
                .set(TENANT_ID, id)
                .set(REDIS_ACL_USER, aclUser)
                .set(REDIS_ACL_PASSWORD, aclPassword)
                .set(DISPLAY_NAME, id)
                .set(PLAN, "free")
                .set(STATUS, "created")
                .set(CREATED_AT, now)
                .set(UPDATED_AT, now)
                .onConflict(TENANT_ID)
                .doUpdate()
                .set(REDIS_ACL_USER, aclUser)
                .set(REDIS_ACL_PASSWORD, aclPassword)
                .set(UPDATED_AT, now)
                .execute();

        LOG.info("Redis ACL credentials stored for tenant {}: aclUser={}", id, aclUser);
    }

    private void handlePlanStripeSynced(String payload) throws Exception {
        var node = mapper.readTree(payload);
        String planId = node.get("planId").asText();
        String productId = node.get("stripeProductId").asText();
        String priceId = node.get("stripePriceId").asText();

        dsl.update(PLAN_TABLE)
                .set(STRIPE_PRODUCT_ID, productId)
                .set(STRIPE_PRICE_ID, priceId)
                .set(PLAN_UPDATED_AT, OffsetDateTime.now())
                .where(PLAN_ID.eq(planId))
                .execute();

        LOG.info("Plan {} synced with Stripe: product={}, price={}", planId, productId, priceId);
    }
}
