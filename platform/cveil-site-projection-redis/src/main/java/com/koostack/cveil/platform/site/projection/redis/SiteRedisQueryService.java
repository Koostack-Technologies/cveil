package com.koostack.cveil.platform.site.projection.redis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.koostack.cveil.plugin.redis.common.JedisProvider;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    /** Returns all plans by reading site:plans:all and fetching each plan hash. */
    public List<PlanInfo> listPlans() {
        Set<String> allPlanIds = jedisProvider.get().smembers("site:plans:all");
        if (allPlanIds == null || allPlanIds.isEmpty()) return Collections.emptyList();
        return allPlanIds.stream().map(this::getPlan).filter(Objects::nonNull).toList();
    }

    /** Returns only active plans with full PlanInfo objects. */
    public List<PlanInfo> listActivePlans() {
        Set<String> activePlanIds = getActivePlanIds();
        if (activePlanIds.isEmpty()) return Collections.emptyList();
        return activePlanIds.stream().map(this::getPlan).filter(Objects::nonNull).toList();
    }

    // =======================================================================
    // Invoice queries
    // =======================================================================

    public record InvoiceInfo(
            String externalInvoiceId,
            String tenantId,
            String status,
            String currency,
            long amountDue,
            long amountPaid,
            long total,
            String hostedInvoiceUrl,
            String invoicePdfUrl,
            String periodStart,
            String periodEnd,
            String dueDate,
            String paidAt) {}

    /** Retrieve a single invoice by external invoice ID. */
    public InvoiceInfo getInvoice(String tenantId, String invoiceId) {
        Map<String, String> hash = jedisProvider.get().hgetAll("site:invoice:" + invoiceId);
        if (hash == null || hash.isEmpty()) return null;
        // Verify tenant ownership
        String storedTenant = hash.get("tenantId");
        if (storedTenant != null && !storedTenant.equals(tenantId)) return null;
        return new InvoiceInfo(
                hash.get("externalInvoiceId"),
                hash.get("tenantId"),
                hash.get("status"),
                hash.get("currency"),
                parseLong(hash.get("amountDue"), 0),
                parseLong(hash.get("amountPaid"), 0),
                parseLong(hash.get("total"), 0),
                hash.get("hostedInvoiceUrl"),
                hash.get("invoicePdfUrl"),
                hash.get("periodStart"),
                hash.get("periodEnd"),
                hash.get("dueDate"),
                hash.get("paidAt"));
    }

    /** List invoices for a tenant with pagination. */
    public List<String> listInvoices(String tenantId, int page, int pageSize) {
        JedisPooled jedis = jedisProvider.get();
        long start = (long) page * pageSize;
        long end = start + pageSize - 1;
        List<String> result = jedis.zrevrange("site:invoices:" + tenantId, start, end);
        return result != null ? result : Collections.emptyList();
    }

    // =======================================================================
    // Organization listing
    // =======================================================================

    /** List all organization IDs. */
    public Set<String> listAllOrganizationIds() {
        Set<String> result = jedisProvider.get().smembers("site:orgs:all");
        return result != null ? result : Collections.emptySet();
    }

    /** List all organizations with full OrgInfo objects. */
    public List<OrgInfo> listOrganizations() {
        Set<String> orgIds = listAllOrganizationIds();
        if (orgIds.isEmpty()) return Collections.emptyList();
        return orgIds.stream().map(this::getOrganization).filter(Objects::nonNull).toList();
    }

    // =======================================================================
    // Access token queries
    // =======================================================================

    /** List access tokens created by a specific user in a tenant. */
    public Set<String> listAccessTokenPrefixesByCreator(String tenantId, String createdBy) {
        Set<String> result =
                jedisProvider
                        .get()
                        .smembers("site:tokens-by-creator:" + tenantId + ":" + createdBy);
        return result != null ? result : Collections.emptySet();
    }

    // =======================================================================
    // Permission resolution
    // =======================================================================

    /**
     * Resolve the effective permissions for a user in a tenant by looking up their roles, then
     * expanding each role's permissions via the role-perms sets.
     */
    public Set<String> resolvePermissions(String userId, String tenantId) {
        Set<String> roles = getUserRoles(userId, tenantId);
        if (roles.isEmpty()) return Collections.emptySet();

        JedisPooled jedis = jedisProvider.get();
        Set<String> permissions = new java.util.HashSet<>();
        for (String role : roles) {
            Set<String> rolePerms = jedis.smembers("site:role-perms:" + role);
            if (rolePerms != null) {
                permissions.addAll(rolePerms);
            }
        }
        // Also include platform-scoped roles (tenant_id IS NULL → stored as platform)
        Set<String> platformRoles = jedis.smembers("site:user:" + userId + ":roles:platform");
        if (platformRoles != null) {
            for (String role : platformRoles) {
                Set<String> rolePerms = jedis.smembers("site:role-perms:" + role);
                if (rolePerms != null) {
                    permissions.addAll(rolePerms);
                }
            }
        }
        return permissions;
    }

    // =======================================================================
    // Role definition queries
    // =======================================================================

    public record RoleDefinitionInfo(
            String roleId,
            String tenantId,
            String scope,
            String displayName,
            String description,
            boolean isSystem,
            boolean isAssignable,
            List<String> permissions) {}

    /** List all role definitions visible to the given tenant (system + tenant-custom). */
    public List<RoleDefinitionInfo> listRoleDefinitions(String tenantId) {
        JedisPooled jedis = jedisProvider.get();
        Set<String> systemRoleIds = jedis.smembers("site:role-defs:system");
        Set<String> tenantRoleIds = jedis.smembers("site:role-defs:" + tenantId);

        Set<String> allRoleIds = new java.util.HashSet<>();
        if (systemRoleIds != null) allRoleIds.addAll(systemRoleIds);
        if (tenantRoleIds != null) allRoleIds.addAll(tenantRoleIds);

        return allRoleIds.stream()
                .map(
                        roleId -> {
                            Map<String, String> hash = jedis.hgetAll("site:role-def:" + roleId);
                            if (hash == null || hash.isEmpty()) return null;
                            Set<String> perms = jedis.smembers("site:role-perms:" + roleId);
                            return new RoleDefinitionInfo(
                                    hash.get("roleId"),
                                    hash.get("tenantId"),
                                    hash.get("scope"),
                                    hash.get("displayName"),
                                    hash.get("description"),
                                    "true".equals(hash.get("isSystem")),
                                    "true".equals(hash.get("isAssignable")),
                                    perms != null ? List.copyOf(perms) : Collections.emptyList());
                        })
                .filter(Objects::nonNull)
                .toList();
    }

    /** List role assignment details for a user in a tenant. */
    public Set<String> listUserRolesInTenant(String userId, String tenantId) {
        return getUserRoles(userId, tenantId);
    }

    // =======================================================================
    // Feature gate queries
    // =======================================================================

    public record PlanFeature(
            String planId, String featureKey, boolean enabled, Integer limitValue) {}

    /** Check if a specific feature is enabled for a plan. */
    public boolean isFeatureEnabled(String planId, String featureKey) {
        String json = jedisProvider.get().hget("site:plan-features:" + planId, featureKey);
        if (json == null) return false;
        try {
            JsonNode node = new ObjectMapper().readTree(json);
            return node.has("enabled") && node.get("enabled").asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }

    /** Get the limit value for a feature on a plan. Returns null if unlimited or not found. */
    public Integer getFeatureLimit(String planId, String featureKey) {
        String json = jedisProvider.get().hget("site:plan-features:" + planId, featureKey);
        if (json == null) return null;
        try {
            JsonNode node = new ObjectMapper().readTree(json);
            if (node.has("limitValue") && !node.get("limitValue").isNull()) {
                return node.get("limitValue").asInt();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Get all features for a plan. */
    public List<PlanFeature> getFeaturesForPlan(String planId) {
        Map<String, String> features = jedisProvider.get().hgetAll("site:plan-features:" + planId);
        if (features == null || features.isEmpty()) return Collections.emptyList();

        ObjectMapper mapper = new ObjectMapper();
        return features.entrySet().stream()
                .map(
                        entry -> {
                            try {
                                JsonNode node = mapper.readTree(entry.getValue());
                                boolean enabled =
                                        node.has("enabled") && node.get("enabled").asBoolean(false);
                                Integer limitValue =
                                        (node.has("limitValue") && !node.get("limitValue").isNull())
                                                ? node.get("limitValue").asInt()
                                                : null;
                                return new PlanFeature(planId, entry.getKey(), enabled, limitValue);
                            } catch (Exception e) {
                                return null;
                            }
                        })
                .filter(Objects::nonNull)
                .toList();
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
