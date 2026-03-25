package com.koostack.cveil.platform.site.projection.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.koostack.cveil.core.event.models.PlatformEvent;
import com.koostack.cveil.core.platform.billing.BillingEventTypes;
import com.koostack.cveil.core.platform.core.CoreEventTypes;
import com.koostack.cveil.core.platform.iam.IamEventTypes;
import com.koostack.cveil.core.platform.plan.PlanEventTypes;
import com.koostack.cveil.plugin.event.jooq.StreamEventHandler;
import com.koostack.cveil.plugin.redis.common.JedisProvider;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;

/**
 * Platform-level stream projection handler that materializes site-wide read models into Redis.
 *
 * <p>Handles IAM, Billing, Core, and Plan domain events and projects them into Redis HASHes, SETs,
 * sorted sets, and STRING lookups under the {@code site:} key namespace.
 *
 * <p>All operations are idempotent — safe to replay from stream offset zero.
 */
public class SiteRedisProjectionHandler implements StreamEventHandler {

    private static final Logger log = LoggerFactory.getLogger(SiteRedisProjectionHandler.class);

    private final JedisProvider jedisProvider;
    private final ObjectMapper objectMapper;

    public SiteRedisProjectionHandler(JedisProvider jedisProvider) {
        this(jedisProvider, defaultObjectMapper());
    }

    public SiteRedisProjectionHandler(JedisProvider jedisProvider, ObjectMapper objectMapper) {
        this.jedisProvider = jedisProvider;
        this.objectMapper = objectMapper;
    }

    private static ObjectMapper defaultObjectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    // -----------------------------------------------------------------------
    // StreamEventHandler
    // -----------------------------------------------------------------------

    @Override
    public void onEvent(String streamKey, String messageId, Map<String, String> fields) {
        String eventType = fields.get(PlatformEvent.FIELD_EVENT_TYPE);
        String payload = fields.get(PlatformEvent.FIELD_PAYLOAD);

        if (eventType == null) {
            log.warn("Skipping event {} — missing eventType", messageId);
            return;
        }

        try {
            JsonNode node =
                    payload != null
                            ? objectMapper.readTree(payload)
                            : objectMapper.createObjectNode();
            dispatch(eventType, node, messageId);
        } catch (Exception e) {
            log.error(
                    "Failed to project event {} [{}]: {}", messageId, eventType, e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    // -----------------------------------------------------------------------
    // Dispatch
    // -----------------------------------------------------------------------

    private void dispatch(String eventType, JsonNode node, String messageId) {
        switch (eventType) {
                // -- IAM: User lifecycle --
            case IamEventTypes.USER_REGISTERED -> handleUserRegistered(node);
            case IamEventTypes.USER_PROFILE_UPDATED -> handleUserProfileUpdated(node);
            case IamEventTypes.USER_APP_PROFILE_UPDATED -> handleUserAppProfileUpdated(node);
            case IamEventTypes.EMAIL_VERIFIED -> handleEmailVerified(node);
            case IamEventTypes.USER_SUSPENDED -> handleUserSuspended(node);
            case IamEventTypes.USER_ACTIVATED -> handleUserActivated(node);
            case IamEventTypes.USER_DELETED -> handleUserDeleted(node);
            case IamEventTypes.PASSWORD_CHANGED -> handlePasswordChanged(node);

                // -- IAM: Organization --
            case IamEventTypes.ORGANIZATION_CREATED -> handleOrganizationCreated(node);
            case IamEventTypes.ORGANIZATION_UPDATED -> handleOrganizationUpdated(node);
            case IamEventTypes.ORGANIZATION_DELETED -> handleOrganizationDeleted(node);
            case IamEventTypes.ORGANIZATION_MEMBER_ADDED -> handleOrgMemberAdded(node);
            case IamEventTypes.ORGANIZATION_MEMBER_REMOVED -> handleOrgMemberRemoved(node);
            case IamEventTypes.ORGANIZATION_MEMBER_INVITED -> handleOrgMemberInvited(node);

                // -- IAM: Roles --
            case IamEventTypes.ROLE_ASSIGNED -> handleRoleAssigned(node);
            case IamEventTypes.ROLE_REMOVED -> handleRoleRemoved(node);

                // -- IAM: Role definitions --
            case IamEventTypes.ROLE_DEFINITION_CREATED -> handleRoleDefinitionCreated(node);
            case IamEventTypes.ROLE_DEFINITION_DELETED -> handleRoleDefinitionDeleted(node);

                // -- IAM: Access tokens --
            case IamEventTypes.ACCESS_TOKEN_CREATED -> handleAccessTokenCreated(node);
            case IamEventTypes.ACCESS_TOKEN_REVOKED -> handleAccessTokenRevoked(node);

                // -- Billing: Profile --
            case BillingEventTypes.BILLING_PROFILE_CREATED -> handleBillingProfileCreated(node);
            case BillingEventTypes.BILLING_EMAIL_UPDATED -> handleBillingEmailUpdated(node);
            case BillingEventTypes.BILLING_TAX_ID_UPDATED -> handleBillingTaxIdUpdated(node);
            case BillingEventTypes.PAYMENT_PROCESSOR_SET -> handlePaymentProcessorSet(node);

                // -- Billing: Subscription --
            case BillingEventTypes.SUBSCRIPTION_CREATED -> handleSubscriptionCreated(node);
            case BillingEventTypes.SUBSCRIPTION_PLAN_CHANGED -> handleSubscriptionPlanChanged(node);
            case BillingEventTypes.SUBSCRIPTION_CANCELLED -> handleSubscriptionCancelled(node);
            case BillingEventTypes.SUBSCRIPTION_RENEWED -> handleSubscriptionRenewed(node);

                // -- Billing: Payment methods --
            case BillingEventTypes.PAYMENT_METHOD_ATTACHED -> handlePaymentMethodAttached(node);
            case BillingEventTypes.PAYMENT_METHOD_DETACHED -> handlePaymentMethodDetached(node);
            case BillingEventTypes
                    .PAYMENT_METHOD_DEFAULT_CHANGED -> handlePaymentMethodDefaultChanged(node);

                // -- Billing: Invoices --
            case BillingEventTypes.INVOICE_CREATED -> handleInvoiceCreated(node);
            case BillingEventTypes.INVOICE_UPDATED -> handleInvoiceUpdated(node);
            case BillingEventTypes.INVOICE_PAID -> handleInvoicePaid(node);

                // -- Billing: Usage --
            case BillingEventTypes.USAGE_REPORTED -> handleUsageReported(node);

                // -- Core: Tenant --
            case CoreEventTypes.TENANT_CREATED -> handleTenantCreated(node);
            case CoreEventTypes.TENANT_DISPLAY_NAME_UPDATED -> handleTenantDisplayNameUpdated(node);
            case CoreEventTypes.TENANT_PLAN_CHANGED -> handleTenantPlanChanged(node);
            case CoreEventTypes.TENANT_SUSPENDED -> handleTenantSuspended(node);
            case CoreEventTypes.TENANT_ACTIVATED -> handleTenantActivated(node);
            case CoreEventTypes.TENANT_DELETED -> handleTenantDeleted(node);

                // -- Plan --
            case PlanEventTypes.PLAN_CREATED -> handlePlanCreated(node);
            case PlanEventTypes.PLAN_UPDATED -> handlePlanUpdated(node);
            case PlanEventTypes.PLAN_ACTIVATED -> handlePlanActivated(node);
            case PlanEventTypes.PLAN_DEACTIVATED -> handlePlanDeactivated(node);
            case PlanEventTypes.PLAN_STRIPE_SYNCED -> handlePlanStripeSynced(node);

                // -- Plan: Feature gates --
            case PlanEventTypes.PLAN_FEATURE_SET -> handlePlanFeatureSet(node);
            case PlanEventTypes.PLAN_FEATURE_REMOVED -> handlePlanFeatureRemoved(node);

                // -- No-op events (trace and skip) --
            case IamEventTypes.USER_AUTHENTICATED,
                    IamEventTypes.TOKEN_REFRESHED,
                    IamEventTypes.USER_LOGGED_OUT,
                    IamEventTypes.EMAIL_VERIFICATION_SENT,
                    IamEventTypes.REDIS_PROVISIONED -> log.trace(
                    "Skipping no-op event type: {}", eventType);

            default -> log.trace("Ignoring unknown event type: {}", eventType);
        }
    }

    // =======================================================================
    // IAM: User lifecycle
    // =======================================================================

    private void handleUserRegistered(JsonNode node) {
        String userId = requiredText(node, "userId");
        String username = requiredText(node, "username");
        String email = requiredText(node, "email");
        String now = Instant.now().toString();

        Map<String, String> hash = new HashMap<>();
        hash.put("userId", userId);
        hash.put("username", username);
        hash.put("email", email);
        hash.put("status", "active");
        hash.put("emailVerified", "false");
        hash.put("createdAt", now);

        String fullName = textOrNull(node, "fullName");
        if (fullName != null) hash.put("fullName", fullName);

        JedisPooled jedis = jedisProvider.get();
        jedis.hset("site:user:" + userId, hash);
        jedis.set("site:user:by-username:" + username, userId);
        jedis.set("site:user:by-email:" + email, userId);

        log.debug("Projected USER_REGISTERED userId={}", userId);
    }

    private void handleUserProfileUpdated(JsonNode node) {
        String userId = requiredText(node, "userId");
        JedisPooled jedis = jedisProvider.get();
        String key = "site:user:" + userId;

        String fullName = textOrNull(node, "fullName");
        if (fullName != null) jedis.hset(key, "fullName", fullName);

        String email = textOrNull(node, "email");
        if (email != null) {
            String oldEmail = jedis.hget(key, "email");
            jedis.hset(key, "email", email);
            jedis.set("site:user:by-email:" + email, userId);
            if (oldEmail != null && !oldEmail.equals(email)) {
                jedis.del("site:user:by-email:" + oldEmail);
            }
        }

        log.debug("Projected USER_PROFILE_UPDATED userId={}", userId);
    }

    private void handleUserAppProfileUpdated(JsonNode node) {
        String userId = requiredText(node, "userId");
        JedisPooled jedis = jedisProvider.get();
        String key = "site:user:" + userId;

        Map<String, String> updates = new HashMap<>();
        putIfPresent(updates, node, "displayName");
        putIfPresent(updates, node, "avatarUrl");
        putIfPresent(updates, node, "locale");
        putIfPresent(updates, node, "timezone");

        if (!updates.isEmpty()) jedis.hset(key, updates);

        log.debug("Projected USER_APP_PROFILE_UPDATED userId={}", userId);
    }

    private void handleEmailVerified(JsonNode node) {
        String userId = requiredText(node, "userId");
        jedisProvider.get().hset("site:user:" + userId, "emailVerified", "true");
        log.debug("Projected EMAIL_VERIFIED userId={}", userId);
    }

    private void handleUserSuspended(JsonNode node) {
        String userId = requiredText(node, "userId");
        jedisProvider.get().hset("site:user:" + userId, "status", "suspended");
        log.debug("Projected USER_SUSPENDED userId={}", userId);
    }

    private void handleUserActivated(JsonNode node) {
        String userId = requiredText(node, "userId");
        jedisProvider.get().hset("site:user:" + userId, "status", "active");
        log.debug("Projected USER_ACTIVATED userId={}", userId);
    }

    private void handleUserDeleted(JsonNode node) {
        String keycloakUserId = requiredText(node, "keycloakUserId");
        JedisPooled jedis = jedisProvider.get();
        String key = "site:user:" + keycloakUserId;

        Map<String, String> existing = jedis.hgetAll(key);
        if (!existing.isEmpty()) {
            String username = existing.get("username");
            String email = existing.get("email");
            if (username != null) jedis.del("site:user:by-username:" + username);
            if (email != null) jedis.del("site:user:by-email:" + email);
        }
        jedis.del(key);

        log.debug("Projected USER_DELETED keycloakUserId={}", keycloakUserId);
    }

    private void handlePasswordChanged(JsonNode node) {
        String userId = requiredText(node, "userId");
        jedisProvider
                .get()
                .hset("site:user:" + userId, "passwordChangedAt", Instant.now().toString());
        log.debug("Projected PASSWORD_CHANGED userId={}", userId);
    }

    // =======================================================================
    // IAM: Organization
    // =======================================================================

    private void handleOrganizationCreated(JsonNode node) {
        String orgId = requiredText(node, "orgId");
        String orgName = requiredText(node, "orgName");

        Map<String, String> hash = new HashMap<>();
        hash.put("orgId", orgId);
        hash.put("name", orgName);

        String displayName = textOrNull(node, "displayName");
        if (displayName != null) hash.put("displayName", displayName);

        jedisProvider.get().hset("site:org:" + orgId, hash);
        jedisProvider.get().sadd("site:orgs:all", orgId);
        log.debug("Projected ORGANIZATION_CREATED orgId={}", orgId);
    }

    private void handleOrganizationUpdated(JsonNode node) {
        String orgId = requiredText(node, "orgId");
        JedisPooled jedis = jedisProvider.get();
        String key = "site:org:" + orgId;

        Map<String, String> updates = new HashMap<>();
        putIfPresent(updates, node, "displayName");
        putIfPresent(updates, node, "description");

        if (!updates.isEmpty()) jedis.hset(key, updates);

        log.debug("Projected ORGANIZATION_UPDATED orgId={}", orgId);
    }

    private void handleOrganizationDeleted(JsonNode node) {
        String orgId = requiredText(node, "orgId");
        JedisPooled jedis = jedisProvider.get();
        jedis.del("site:org:" + orgId);
        jedis.del("site:org:" + orgId + ":members");
        jedis.srem("site:orgs:all", orgId);
        log.debug("Projected ORGANIZATION_DELETED orgId={}", orgId);
    }

    private void handleOrgMemberAdded(JsonNode node) {
        String orgId = requiredText(node, "orgId");
        String userId = requiredText(node, "userId");
        JedisPooled jedis = jedisProvider.get();
        jedis.sadd("site:org:" + orgId + ":members", userId);
        jedis.sadd("site:user:" + userId + ":orgs", orgId);
        log.debug("Projected ORGANIZATION_MEMBER_ADDED orgId={} userId={}", orgId, userId);
    }

    private void handleOrgMemberRemoved(JsonNode node) {
        String orgId = requiredText(node, "orgId");
        String userId = requiredText(node, "userId");
        JedisPooled jedis = jedisProvider.get();
        jedis.srem("site:org:" + orgId + ":members", userId);
        jedis.srem("site:user:" + userId + ":orgs", orgId);
        log.debug("Projected ORGANIZATION_MEMBER_REMOVED orgId={} userId={}", orgId, userId);
    }

    private void handleOrgMemberInvited(JsonNode node) {
        String orgId = requiredText(node, "orgId");
        String inviteeEmail = requiredText(node, "inviteeEmail");

        ObjectNode meta = objectMapper.createObjectNode();
        meta.put("inviteeEmail", inviteeEmail);
        meta.put("invitedAt", Instant.now().toString());
        String inviterUserId = textOrNull(node, "inviterUserId");
        if (inviterUserId != null) meta.put("inviterUserId", inviterUserId);

        jedisProvider.get().hset("site:org:" + orgId + ":invitations", inviteeEmail, toJson(meta));
        log.debug(
                "Projected ORGANIZATION_MEMBER_INVITED orgId={} inviteeEmail={}",
                orgId,
                inviteeEmail);
    }

    // =======================================================================
    // IAM: Roles
    // =======================================================================

    private void handleRoleAssigned(JsonNode node) {
        String userId = requiredText(node, "userId");
        String tenantId = requiredText(node, "tenantId");
        String role = requiredText(node, "role");
        jedisProvider.get().sadd("site:user:" + userId + ":roles:" + tenantId, role);
        log.debug("Projected ROLE_ASSIGNED userId={} tenantId={} role={}", userId, tenantId, role);
    }

    private void handleRoleRemoved(JsonNode node) {
        String userId = requiredText(node, "userId");
        String tenantId = requiredText(node, "tenantId");
        String role = requiredText(node, "role");
        jedisProvider.get().srem("site:user:" + userId + ":roles:" + tenantId, role);
        log.debug("Projected ROLE_REMOVED userId={} tenantId={} role={}", userId, tenantId, role);
    }

    // =======================================================================
    // IAM: Access tokens
    // =======================================================================

    private void handleAccessTokenCreated(JsonNode node) {
        String userId = requiredText(node, "userId");
        String tokenPrefix = requiredText(node, "tokenPrefix");

        ObjectNode meta = objectMapper.createObjectNode();
        meta.put("tokenId", requiredText(node, "tokenId"));
        meta.put("tokenPrefix", tokenPrefix);
        meta.put("createdAt", Instant.now().toString());
        String name = textOrNull(node, "name");
        if (name != null) meta.put("name", name);
        String scopes = textOrNull(node, "scopes");
        if (scopes != null) meta.put("scopes", scopes);

        jedisProvider.get().hset("site:tokens:" + userId, tokenPrefix, toJson(meta));

        String createdBy = textOrNull(node, "userId");
        String tenantId = textOrNull(node, "tenantId");
        if (createdBy != null && tenantId != null && tokenPrefix != null) {
            jedisProvider.get().sadd("site:tokens-by-creator:" + tenantId + ":" + createdBy, tokenPrefix);
        }

        log.debug("Projected ACCESS_TOKEN_CREATED userId={} tokenPrefix={}", userId, tokenPrefix);
    }

    private void handleAccessTokenRevoked(JsonNode node) {
        String userId = requiredText(node, "userId");
        String tokenPrefix = requiredText(node, "tokenPrefix");
        jedisProvider.get().hdel("site:tokens:" + userId, tokenPrefix);

        String tenantId = textOrNull(node, "tenantId");
        if (tenantId != null) {
            jedisProvider.get().srem("site:tokens-by-creator:" + tenantId + ":" + userId, tokenPrefix);
        }

        log.debug("Projected ACCESS_TOKEN_REVOKED userId={} tokenPrefix={}", userId, tokenPrefix);
    }

    // =======================================================================
    // Billing: Profile
    // =======================================================================

    private void handleBillingProfileCreated(JsonNode node) {
        String tenantId = requiredText(node, "tenantId");

        Map<String, String> hash = new HashMap<>();
        hash.put("tenantId", tenantId);
        String billingEmail = textOrNull(node, "billingEmail");
        if (billingEmail != null) hash.put("billingEmail", billingEmail);

        jedisProvider.get().hset("site:billing:" + tenantId, hash);
        log.debug("Projected BILLING_PROFILE_CREATED tenantId={}", tenantId);
    }

    private void handleBillingEmailUpdated(JsonNode node) {
        String tenantId = requiredText(node, "tenantId");
        String newEmail = requiredText(node, "newEmail");
        jedisProvider.get().hset("site:billing:" + tenantId, "billingEmail", newEmail);
        log.debug("Projected BILLING_EMAIL_UPDATED tenantId={}", tenantId);
    }

    private void handleBillingTaxIdUpdated(JsonNode node) {
        String tenantId = requiredText(node, "tenantId");
        String taxId = requiredText(node, "taxId");
        jedisProvider.get().hset("site:billing:" + tenantId, "taxId", taxId);
        log.debug("Projected BILLING_TAX_ID_UPDATED tenantId={}", tenantId);
    }

    private void handlePaymentProcessorSet(JsonNode node) {
        String tenantId = requiredText(node, "tenantId");
        JedisPooled jedis = jedisProvider.get();
        String key = "site:billing:" + tenantId;

        Map<String, String> updates = new HashMap<>();
        putIfPresent(updates, node, "processor");
        putIfPresent(updates, node, "customerId");

        if (!updates.isEmpty()) jedis.hset(key, updates);

        log.debug("Projected PAYMENT_PROCESSOR_SET tenantId={}", tenantId);
    }

    // =======================================================================
    // Billing: Subscription
    // =======================================================================

    private void handleSubscriptionCreated(JsonNode node) {
        String tenantId = requiredText(node, "tenantId");

        Map<String, String> hash = new HashMap<>();
        hash.put("tenantId", tenantId);
        hash.put("status", "active");
        putIfPresent(hash, node, "planTier");
        hash.put("evalLimit", String.valueOf(longOrDefault(node, "evalLimit", 0)));
        hash.put("subjectLimit", String.valueOf(longOrDefault(node, "subjectLimit", 0)));
        hash.put("burstLimit", String.valueOf(longOrDefault(node, "burstLimit", 0)));
        putIfPresent(hash, node, "externalSubscriptionId");
        putIfPresent(hash, node, "periodStart");
        putIfPresent(hash, node, "periodEnd");

        jedisProvider.get().hset("site:subscription:" + tenantId, hash);
        log.debug("Projected SUBSCRIPTION_CREATED tenantId={}", tenantId);
    }

    private void handleSubscriptionPlanChanged(JsonNode node) {
        String tenantId = requiredText(node, "tenantId");
        JedisPooled jedis = jedisProvider.get();
        String key = "site:subscription:" + tenantId;

        Map<String, String> updates = new HashMap<>();
        String newPlanTier = textOrNull(node, "newPlanTier");
        if (newPlanTier != null) updates.put("planTier", newPlanTier);
        updates.put("evalLimit", String.valueOf(longOrDefault(node, "evalLimit", 0)));
        updates.put("subjectLimit", String.valueOf(longOrDefault(node, "subjectLimit", 0)));
        updates.put("burstLimit", String.valueOf(longOrDefault(node, "burstLimit", 0)));

        jedis.hset(key, updates);
        log.debug("Projected SUBSCRIPTION_PLAN_CHANGED tenantId={}", tenantId);
    }

    private void handleSubscriptionCancelled(JsonNode node) {
        String tenantId = requiredText(node, "tenantId");
        jedisProvider.get().hset("site:subscription:" + tenantId, "status", "cancelled");
        log.debug("Projected SUBSCRIPTION_CANCELLED tenantId={}", tenantId);
    }

    private void handleSubscriptionRenewed(JsonNode node) {
        String tenantId = requiredText(node, "tenantId");
        JedisPooled jedis = jedisProvider.get();
        String key = "site:subscription:" + tenantId;

        Map<String, String> updates = new HashMap<>();
        updates.put("status", "active");
        putIfPresent(updates, node, "periodStart");
        putIfPresent(updates, node, "periodEnd");

        jedis.hset(key, updates);
        log.debug("Projected SUBSCRIPTION_RENEWED tenantId={}", tenantId);
    }

    // =======================================================================
    // Billing: Payment methods
    // =======================================================================

    private void handlePaymentMethodAttached(JsonNode node) {
        String tenantId = requiredText(node, "tenantId");
        String methodId = requiredText(node, "externalPaymentMethodId");

        ObjectNode meta = objectMapper.createObjectNode();
        meta.put("externalPaymentMethodId", methodId);
        putJsonIfPresent(meta, node, "type");
        putJsonIfPresent(meta, node, "brand");
        putJsonIfPresent(meta, node, "last4");
        putJsonIfPresent(meta, node, "expMonth");
        putJsonIfPresent(meta, node, "expYear");
        putJsonIfPresent(meta, node, "isDefault");

        jedisProvider.get().hset("site:payment-methods:" + tenantId, methodId, toJson(meta));
        log.debug("Projected PAYMENT_METHOD_ATTACHED tenantId={} methodId={}", tenantId, methodId);
    }

    private void handlePaymentMethodDetached(JsonNode node) {
        String methodId = requiredText(node, "externalPaymentMethodId");
        String tenantId = textOrNull(node, "tenantId");
        if (tenantId != null) {
            jedisProvider.get().hdel("site:payment-methods:" + tenantId, methodId);
            log.debug(
                    "Projected PAYMENT_METHOD_DETACHED tenantId={} methodId={}",
                    tenantId,
                    methodId);
        } else {
            log.warn("PAYMENT_METHOD_DETACHED missing tenantId for methodId={}", methodId);
        }
    }

    private void handlePaymentMethodDefaultChanged(JsonNode node) {
        String tenantId = requiredText(node, "tenantId");
        String methodId = requiredText(node, "externalPaymentMethodId");
        JedisPooled jedis = jedisProvider.get();
        String key = "site:payment-methods:" + tenantId;

        // Update all methods: clear old default, set new default
        Map<String, String> allMethods = jedis.hgetAll(key);
        for (Map.Entry<String, String> entry : allMethods.entrySet()) {
            try {
                ObjectNode meta = (ObjectNode) objectMapper.readTree(entry.getValue());
                boolean isTarget = entry.getKey().equals(methodId);
                meta.put("isDefault", isTarget);
                jedis.hset(key, entry.getKey(), toJson(meta));
            } catch (JsonProcessingException e) {
                log.warn(
                        "Failed to parse payment method JSON for key={}: {}",
                        entry.getKey(),
                        e.getMessage());
            }
        }

        log.debug(
                "Projected PAYMENT_METHOD_DEFAULT_CHANGED tenantId={} methodId={}",
                tenantId,
                methodId);
    }

    // =======================================================================
    // Billing: Invoices
    // =======================================================================

    private void handleInvoiceCreated(JsonNode node) {
        String tenantId = requiredText(node, "tenantId");
        String invoiceId = requiredText(node, "externalInvoiceId");

        ObjectNode meta = objectMapper.createObjectNode();
        meta.put("externalInvoiceId", invoiceId);
        putJsonIfPresent(meta, node, "status");
        putJsonIfPresent(meta, node, "amountDue");
        putJsonIfPresent(meta, node, "total");
        putJsonIfPresent(meta, node, "hostedInvoiceUrl");
        putJsonIfPresent(meta, node, "periodStart");
        putJsonIfPresent(meta, node, "periodEnd");
        putJsonIfPresent(meta, node, "dueDate");

        double score = Instant.now().toEpochMilli();
        jedisProvider.get().zadd("site:invoices:" + tenantId, score, toJson(meta));

        // Store individual invoice hash for direct lookup
        Map<String, String> invoiceHash = new HashMap<>();
        invoiceHash.put("externalInvoiceId", invoiceId);
        putIfPresent(invoiceHash, node, "tenantId");
        putIfPresent(invoiceHash, node, "status");
        putIfPresent(invoiceHash, node, "currency");
        invoiceHash.put("amountDue", String.valueOf(longOrDefault(node, "amountDue", 0)));
        invoiceHash.put("total", String.valueOf(longOrDefault(node, "total", 0)));
        putIfPresent(invoiceHash, node, "hostedInvoiceUrl");
        putIfPresent(invoiceHash, node, "invoicePdfUrl");
        putIfPresent(invoiceHash, node, "periodStart");
        putIfPresent(invoiceHash, node, "periodEnd");
        putIfPresent(invoiceHash, node, "dueDate");
        jedisProvider.get().hset("site:invoice:" + invoiceId, invoiceHash);

        log.debug("Projected INVOICE_CREATED tenantId={} invoiceId={}", tenantId, invoiceId);
    }

    private void handleInvoiceUpdated(JsonNode node) {
        String invoiceId = requiredText(node, "externalInvoiceId");
        String tenantId = textOrNull(node, "tenantId");
        if (tenantId == null) {
            log.warn("INVOICE_UPDATED missing tenantId for invoiceId={}", invoiceId);
            return;
        }

        updateInvoiceInSortedSet(tenantId, invoiceId, node);
        log.debug("Projected INVOICE_UPDATED tenantId={} invoiceId={}", tenantId, invoiceId);
    }

    private void handleInvoicePaid(JsonNode node) {
        String invoiceId = requiredText(node, "externalInvoiceId");
        String tenantId = textOrNull(node, "tenantId");
        if (tenantId == null) {
            log.warn("INVOICE_PAID missing tenantId for invoiceId={}", invoiceId);
            return;
        }

        updateInvoiceInSortedSet(tenantId, invoiceId, node);
        log.debug("Projected INVOICE_PAID tenantId={} invoiceId={}", tenantId, invoiceId);
    }

    private void updateInvoiceInSortedSet(String tenantId, String invoiceId, JsonNode node) {
        JedisPooled jedis = jedisProvider.get();
        String key = "site:invoices:" + tenantId;

        // Find and update the matching invoice entry
        for (String member : jedis.zrange(key, 0, -1)) {
            try {
                JsonNode existing = objectMapper.readTree(member);
                if (invoiceId.equals(textOrNull(existing, "externalInvoiceId"))) {
                    double score = jedis.zscore(key, member);
                    jedis.zrem(key, member);

                    ObjectNode updated = (ObjectNode) existing;
                    putJsonIfPresent(updated, node, "status");
                    putJsonIfPresent(updated, node, "amountPaid");
                    putJsonIfPresent(updated, node, "paidAt");
                    putJsonIfPresent(updated, node, "amountDue");
                    putJsonIfPresent(updated, node, "total");

                    jedis.zadd(key, score, toJson(updated));

                    // Also update individual invoice hash
                    Map<String, String> hashUpdates = new HashMap<>();
                    putIfPresent(hashUpdates, node, "status");
                    putIfPresent(hashUpdates, node, "amountPaid");
                    putIfPresent(hashUpdates, node, "paidAt");
                    putIfPresent(hashUpdates, node, "amountDue");
                    putIfPresent(hashUpdates, node, "total");
                    if (!hashUpdates.isEmpty()) {
                        jedis.hset("site:invoice:" + invoiceId, hashUpdates);
                    }

                    return;
                }
            } catch (JsonProcessingException e) {
                log.warn("Failed to parse invoice JSON in sorted set: {}", e.getMessage());
            }
        }
    }

    // =======================================================================
    // Billing: Usage
    // =======================================================================

    private void handleUsageReported(JsonNode node) {
        String tenantId = requiredText(node, "tenantId");
        String metricName = requiredText(node, "metricName");
        String quantity = requiredText(node, "quantity");
        jedisProvider.get().hset("site:usage:" + tenantId, metricName, quantity);
        log.debug("Projected USAGE_REPORTED tenantId={} metric={}", tenantId, metricName);
    }

    // =======================================================================
    // Core: Tenant
    // =======================================================================

    private void handleTenantCreated(JsonNode node) {
        String tenantId = requiredText(node, "tenantId");

        Map<String, String> hash = new HashMap<>();
        hash.put("tenantId", tenantId);
        hash.put("status", "active");
        putIfPresent(hash, node, "displayName");
        putIfPresent(hash, node, "plan");
        putIfPresent(hash, node, "tenantType");

        jedisProvider.get().hset("site:tenant:" + tenantId, hash);
        log.debug("Projected TENANT_CREATED tenantId={}", tenantId);
    }

    private void handleTenantDisplayNameUpdated(JsonNode node) {
        String tenantId = requiredText(node, "tenantId");
        String displayName = requiredText(node, "displayName");
        jedisProvider.get().hset("site:tenant:" + tenantId, "displayName", displayName);
        log.debug("Projected TENANT_DISPLAY_NAME_UPDATED tenantId={}", tenantId);
    }

    private void handleTenantPlanChanged(JsonNode node) {
        String tenantId = requiredText(node, "tenantId");
        String newPlan = requiredText(node, "newPlan");
        jedisProvider.get().hset("site:tenant:" + tenantId, "plan", newPlan);
        log.debug("Projected TENANT_PLAN_CHANGED tenantId={}", tenantId);
    }

    private void handleTenantSuspended(JsonNode node) {
        String tenantId = requiredText(node, "tenantId");
        jedisProvider.get().hset("site:tenant:" + tenantId, "status", "suspended");
        log.debug("Projected TENANT_SUSPENDED tenantId={}", tenantId);
    }

    private void handleTenantActivated(JsonNode node) {
        String tenantId = requiredText(node, "tenantId");
        jedisProvider.get().hset("site:tenant:" + tenantId, "status", "active");
        log.debug("Projected TENANT_ACTIVATED tenantId={}", tenantId);
    }

    private void handleTenantDeleted(JsonNode node) {
        String tenantId = requiredText(node, "tenantId");
        jedisProvider.get().del("site:tenant:" + tenantId);
        log.debug("Projected TENANT_DELETED tenantId={}", tenantId);
    }

    // =======================================================================
    // Plan
    // =======================================================================

    private void handlePlanCreated(JsonNode node) {
        String planId = requiredText(node, "planId");

        Map<String, String> hash = new HashMap<>();
        hash.put("planId", planId);
        putIfPresent(hash, node, "displayName");
        putIfPresent(hash, node, "description");
        hash.put("evalLimit", String.valueOf(longOrDefault(node, "evalLimit", 0)));
        hash.put("subjectLimit", String.valueOf(longOrDefault(node, "subjectLimit", 0)));
        hash.put("burstLimit", String.valueOf(longOrDefault(node, "burstLimit", 0)));
        hash.put("isPublic", String.valueOf(boolOrDefault(node, "isPublic", false)));
        hash.put("isActive", String.valueOf(boolOrDefault(node, "isActive", false)));
        hash.put("sortOrder", String.valueOf(longOrDefault(node, "sortOrder", 0)));

        JedisPooled jedis = jedisProvider.get();
        jedis.hset("site:plan:" + planId, hash);

        if (boolOrDefault(node, "isActive", false)) {
            jedis.sadd("site:plans:active", planId);
        }

        jedis.sadd("site:plans:all", planId);

        log.debug("Projected PLAN_CREATED planId={}", planId);
    }

    private void handlePlanUpdated(JsonNode node) {
        String planId = requiredText(node, "planId");

        Map<String, String> updates = new HashMap<>();
        putIfPresent(updates, node, "displayName");
        putIfPresent(updates, node, "description");
        updates.put("evalLimit", String.valueOf(longOrDefault(node, "evalLimit", 0)));
        updates.put("subjectLimit", String.valueOf(longOrDefault(node, "subjectLimit", 0)));
        updates.put("burstLimit", String.valueOf(longOrDefault(node, "burstLimit", 0)));
        if (node.has("isPublic") && !node.get("isPublic").isNull()) {
            updates.put("isPublic", String.valueOf(node.get("isPublic").asBoolean()));
        }
        if (node.has("sortOrder") && !node.get("sortOrder").isNull()) {
            updates.put("sortOrder", String.valueOf(node.get("sortOrder").asLong()));
        }

        jedisProvider.get().hset("site:plan:" + planId, updates);
        log.debug("Projected PLAN_UPDATED planId={}", planId);
    }

    private void handlePlanActivated(JsonNode node) {
        String planId = requiredText(node, "planId");
        JedisPooled jedis = jedisProvider.get();
        jedis.hset("site:plan:" + planId, "isActive", "true");
        jedis.sadd("site:plans:active", planId);
        log.debug("Projected PLAN_ACTIVATED planId={}", planId);
    }

    private void handlePlanDeactivated(JsonNode node) {
        String planId = requiredText(node, "planId");
        JedisPooled jedis = jedisProvider.get();
        jedis.hset("site:plan:" + planId, "isActive", "false");
        jedis.srem("site:plans:active", planId);
        log.debug("Projected PLAN_DEACTIVATED planId={}", planId);
    }

    private void handlePlanStripeSynced(JsonNode node) {
        String planId = requiredText(node, "planId");

        Map<String, String> updates = new HashMap<>();
        putIfPresent(updates, node, "stripeProductId");
        putIfPresent(updates, node, "stripePriceId");
        putIfPresent(updates, node, "stripeAnnualPriceId");

        if (!updates.isEmpty()) {
            jedisProvider.get().hset("site:plan:" + planId, updates);
        }

        log.debug("Projected PLAN_STRIPE_SYNCED planId={}", planId);
    }

    // =======================================================================
    // IAM: Role definitions
    // =======================================================================

    private void handleRoleDefinitionCreated(JsonNode node) {
        String roleId = textOrNull(node, "roleId");
        String tenantId = textOrNull(node, "tenantId");
        if (roleId == null) return;

        JedisPooled jedis = jedisProvider.get();

        // Store role definition metadata
        Map<String, String> hash = new HashMap<>();
        hash.put("roleId", roleId);
        putIfPresent(hash, "tenantId", node);
        putIfPresent(hash, "scope", node);
        putIfPresent(hash, "displayName", node);
        putIfPresent(hash, "description", node);
        hash.put("isSystem", String.valueOf(boolOrDefault(node, "isSystem", false)));
        hash.put("isAssignable", String.valueOf(boolOrDefault(node, "isAssignable", true)));

        String roleKey = "site:role-def:" + roleId;
        jedis.hset(roleKey, hash);

        // Store role → permission mappings
        if (node.has("permissions") && node.get("permissions").isArray()) {
            String permKey = "site:role-perms:" + roleId;
            jedis.del(permKey); // idempotent: clear before re-adding
            for (JsonNode perm : node.get("permissions")) {
                jedis.sadd(permKey, perm.asText());
            }
        }

        // Track role in tenant's role definitions set
        String scope = tenantId != null ? tenantId : "platform";
        jedis.sadd("site:role-defs:" + scope, roleId);
        // Also add to global set for system roles
        if (boolOrDefault(node, "isSystem", false)) {
            jedis.sadd("site:role-defs:system", roleId);
        }

        log.debug("Projected role definition created: {}", roleId);
    }

    private void handleRoleDefinitionDeleted(JsonNode node) {
        String roleId = textOrNull(node, "roleId");
        String tenantId = textOrNull(node, "tenantId");
        if (roleId == null) return;

        JedisPooled jedis = jedisProvider.get();
        jedis.del("site:role-def:" + roleId);
        jedis.del("site:role-perms:" + roleId);

        String scope = tenantId != null ? tenantId : "platform";
        jedis.srem("site:role-defs:" + scope, roleId);

        log.debug("Projected role definition deleted: {}", roleId);
    }

    // =======================================================================
    // Plan: Feature gates
    // =======================================================================

    private void handlePlanFeatureSet(JsonNode node) {
        String planId = textOrNull(node, "planId");
        String featureKey = textOrNull(node, "featureKey");
        if (planId == null || featureKey == null) return;

        JedisPooled jedis = jedisProvider.get();
        String key = "site:plan-features:" + planId;

        // Store as HASH: field = featureKey, value = JSON {enabled, limitValue}
        Map<String, Object> featureData = new HashMap<>();
        featureData.put("enabled", boolOrDefault(node, "enabled", false));
        if (node.has("limitValue") && !node.get("limitValue").isNull()) {
            featureData.put("limitValue", node.get("limitValue").asInt());
        }

        try {
            jedis.hset(key, featureKey, objectMapper.writeValueAsString(featureData));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize plan feature: {}/{}", planId, featureKey, e);
        }

        log.debug("Projected plan feature set: {}/{}", planId, featureKey);
    }

    private void handlePlanFeatureRemoved(JsonNode node) {
        String planId = textOrNull(node, "planId");
        String featureKey = textOrNull(node, "featureKey");
        if (planId == null || featureKey == null) return;

        jedisProvider.get().hdel("site:plan-features:" + planId, featureKey);
        log.debug("Projected plan feature removed: {}/{}", planId, featureKey);
    }

    // =======================================================================
    // Helpers
    // =======================================================================

    private static String textOrNull(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : null;
    }

    private static String requiredText(JsonNode node, String field) {
        if (!node.has(field) || node.get(field).isNull()) {
            throw new IllegalArgumentException("Missing required field: " + field);
        }
        return node.get(field).asText();
    }

    private static long longOrDefault(JsonNode node, String field, long defaultValue) {
        return node.has(field) && !node.get(field).isNull()
                ? node.get(field).asLong()
                : defaultValue;
    }

    private static boolean boolOrDefault(JsonNode node, String field, boolean defaultValue) {
        return node.has(field) && !node.get(field).isNull()
                ? node.get(field).asBoolean()
                : defaultValue;
    }

    private static void putIfPresent(Map<String, String> map, JsonNode node, String field) {
        String value = textOrNull(node, field);
        if (value != null) map.put(field, value);
    }

    private static void putIfPresent(Map<String, String> hash, String field, JsonNode node) {
        if (node.has(field) && !node.get(field).isNull()) {
            hash.put(field, node.get(field).asText());
        }
    }

    private static void putJsonIfPresent(ObjectNode target, JsonNode source, String field) {
        if (source.has(field) && !source.get(field).isNull()) {
            target.set(field, source.get(field));
        }
    }

    private String toJson(ObjectNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize JSON", e);
        }
    }
}
