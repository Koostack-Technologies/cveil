package com.koostack.cveil.platform.site.projection.redis;

import com.koostack.cveil.plugin.redis.common.JedisProvider;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import redis.clients.jedis.JedisPooled;

/**
 * Typed read API over the Redis site projection keys.
 *
 * <p>All methods are null-safe: if the underlying Redis key does not exist, the method returns
 * {@code null} (for single-object queries) or an empty collection (for set/list queries).
 */
public class SiteRedisQueryService {

    private final JedisProvider jedisProvider;

    public SiteRedisQueryService(JedisProvider jedisProvider) {
        this.jedisProvider = jedisProvider;
    }

    // =======================================================================
    // User queries
    // =======================================================================

    public record UserInfo(
            String userId,
            String username,
            String email,
            String fullName,
            String status,
            boolean emailVerified,
            String avatarUrl,
            String locale,
            String timezone) {}

    public UserInfo getUser(String userId) {
        Map<String, String> hash = jedisProvider.get().hgetAll("site:user:" + userId);
        if (hash == null || hash.isEmpty()) return null;
        return new UserInfo(
                hash.get("userId"),
                hash.get("username"),
                hash.get("email"),
                hash.get("fullName"),
                hash.get("status"),
                "true".equals(hash.get("emailVerified")),
                hash.get("avatarUrl"),
                hash.get("locale"),
                hash.get("timezone"));
    }

    public String resolveUserId(String username) {
        return jedisProvider.get().get("site:user:by-username:" + username);
    }

    public String resolveUserIdByEmail(String email) {
        return jedisProvider.get().get("site:user:by-email:" + email);
    }

    public Set<String> getUserOrganizations(String userId) {
        Set<String> result = jedisProvider.get().smembers("site:user:" + userId + ":orgs");
        return result != null ? result : Collections.emptySet();
    }

    public Set<String> getUserRoles(String userId, String tenantId) {
        Set<String> result =
                jedisProvider.get().smembers("site:user:" + userId + ":roles:" + tenantId);
        return result != null ? result : Collections.emptySet();
    }

    /** Returns a map of tokenPrefix → JSON metadata. */
    public Map<String, String> getUserTokens(String userId) {
        Map<String, String> result = jedisProvider.get().hgetAll("site:tokens:" + userId);
        return result != null ? result : Collections.emptyMap();
    }

    // =======================================================================
    // Organization queries
    // =======================================================================

    public record OrgInfo(
            String orgId, String name, String displayName, String description, String status) {}

    public OrgInfo getOrganization(String orgId) {
        Map<String, String> hash = jedisProvider.get().hgetAll("site:org:" + orgId);
        if (hash == null || hash.isEmpty()) return null;
        return new OrgInfo(
                hash.get("orgId"),
                hash.get("name"),
                hash.get("displayName"),
                hash.get("description"),
                hash.get("status"));
    }

    public Set<String> getOrgMembers(String orgId) {
        Set<String> result = jedisProvider.get().smembers("site:org:" + orgId + ":members");
        return result != null ? result : Collections.emptySet();
    }

    // =======================================================================
    // Billing queries
    // =======================================================================

    public record BillingProfile(
            String tenantId,
            String billingEmail,
            String taxId,
            String processor,
            String customerId) {}

    public BillingProfile getBillingProfile(String tenantId) {
        Map<String, String> hash = jedisProvider.get().hgetAll("site:billing:" + tenantId);
        if (hash == null || hash.isEmpty()) return null;
        return new BillingProfile(
                hash.get("tenantId"),
                hash.get("billingEmail"),
                hash.get("taxId"),
                hash.get("processor"),
                hash.get("customerId"));
    }

    public record SubscriptionInfo(
            String tenantId,
            String planTier,
            String status,
            long evalLimit,
            long subjectLimit,
            int burstLimit,
            String externalSubscriptionId) {}

    public SubscriptionInfo getSubscription(String tenantId) {
        Map<String, String> hash = jedisProvider.get().hgetAll("site:subscription:" + tenantId);
        if (hash == null || hash.isEmpty()) return null;
        return new SubscriptionInfo(
                hash.get("tenantId"),
                hash.get("planTier"),
                hash.get("status"),
                parseLong(hash.get("evalLimit"), 0),
                parseLong(hash.get("subjectLimit"), 0),
                (int) parseLong(hash.get("burstLimit"), 0),
                hash.get("externalSubscriptionId"));
    }

    /** Returns a map of externalPaymentMethodId → JSON metadata. */
    public Map<String, String> getPaymentMethods(String tenantId) {
        Map<String, String> result =
                jedisProvider.get().hgetAll("site:payment-methods:" + tenantId);
        return result != null ? result : Collections.emptyMap();
    }

    /** Returns the most recent invoices as JSON strings from the sorted set. */
    public List<String> getRecentInvoices(String tenantId, int limit) {
        JedisPooled jedis = jedisProvider.get();
        List<String> result = jedis.zrevrange("site:invoices:" + tenantId, 0, limit - 1);
        return result != null ? result : Collections.emptyList();
    }

    // =======================================================================
    // Tenant / Plan queries
    // =======================================================================

    public record TenantInfo(
            String tenantId, String displayName, String plan, String status, String tenantType) {}

    public TenantInfo getTenant(String tenantId) {
        Map<String, String> hash = jedisProvider.get().hgetAll("site:tenant:" + tenantId);
        if (hash == null || hash.isEmpty()) return null;
        return new TenantInfo(
                hash.get("tenantId"),
                hash.get("displayName"),
                hash.get("plan"),
                hash.get("status"),
                hash.get("tenantType"));
    }

    public record PlanInfo(
            String planId,
            String displayName,
            String description,
            long evalLimit,
            long subjectLimit,
            int burstLimit,
            boolean isPublic,
            boolean isActive,
            int sortOrder,
            String stripeProductId,
            String stripePriceId) {}

    public PlanInfo getPlan(String planId) {
        Map<String, String> hash = jedisProvider.get().hgetAll("site:plan:" + planId);
        if (hash == null || hash.isEmpty()) return null;
        return new PlanInfo(
                hash.get("planId"),
                hash.get("displayName"),
                hash.get("description"),
                parseLong(hash.get("evalLimit"), 0),
                parseLong(hash.get("subjectLimit"), 0),
                (int) parseLong(hash.get("burstLimit"), 0),
                "true".equals(hash.get("isPublic")),
                "true".equals(hash.get("isActive")),
                (int) parseLong(hash.get("sortOrder"), 0),
                hash.get("stripeProductId"),
                hash.get("stripePriceId"));
    }

    public Set<String> getActivePlanIds() {
        Set<String> result = jedisProvider.get().smembers("site:plans:active");
        return result != null ? result : Collections.emptySet();
    }

    // =======================================================================
    // Helpers
    // =======================================================================

    private static long parseLong(String value, long defaultValue) {
        if (value == null) return defaultValue;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
