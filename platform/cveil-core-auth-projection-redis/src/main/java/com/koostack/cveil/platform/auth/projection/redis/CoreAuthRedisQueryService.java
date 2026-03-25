package com.koostack.cveil.platform.auth.projection.redis;

import com.koostack.cveil.plugin.redis.common.JedisProvider;
import java.time.Instant;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;

/**
 * Read-only query service that resolves auth data from the Redis read model maintained by {@link
 * CoreAuthRedisProjectionHandler}.
 *
 * <p>Every public method targets a single Redis call to keep latency minimal on the API hot path.
 */
public class CoreAuthRedisQueryService {

    private static final Logger log = LoggerFactory.getLogger(CoreAuthRedisQueryService.class);

    private final JedisProvider jedisProvider;

    public CoreAuthRedisQueryService(JedisProvider jedisProvider) {
        this.jedisProvider = Objects.requireNonNull(jedisProvider, "jedisProvider");
    }

    // ── API Key Records ─────────────────────────────────────────────────

    /** Immutable snapshot of an API key's projected state. */
    public record ApiKeyInfo(
            String userId,
            String tenantId,
            String roles,
            String status,
            String expiresAt,
            String scopes) {}

    /**
     * Looks up an API key by its SHA-256 hash.
     *
     * @return the key info, or {@code null} if no key with this hash exists
     */
    public ApiKeyInfo findApiKeyByHash(String sha256Hash) {
        if (sha256Hash == null) return null;

        JedisPooled jedis = jedisProvider.get();
        Map<String, String> hash = jedis.hgetAll("auth:apikey:" + sha256Hash);
        if (hash == null || hash.isEmpty()) return null;

        return new ApiKeyInfo(
                hash.get("userId"),
                hash.get("tenantId"),
                hash.get("roles"),
                hash.get("status"),
                hash.get("expiresAt"),
                hash.get("scopes"));
    }

    /**
     * Returns {@code true} if the key exists, has status "active", and is not past its expiry time.
     */
    public boolean isApiKeyValid(String sha256Hash) {
        ApiKeyInfo info = findApiKeyByHash(sha256Hash);
        if (info == null) return false;
        if (!"active".equals(info.status())) return false;

        if (info.expiresAt() != null) {
            try {
                Instant expiry = Instant.parse(info.expiresAt());
                if (Instant.now().isAfter(expiry)) return false;
            } catch (Exception e) {
                log.warn(
                        "Unparseable expiresAt '{}' for key hash — treating as valid",
                        info.expiresAt());
            }
        }
        return true;
    }

    // ── Permission Resolution ───────────────────────────────────────────

    /** Returns the set of roles assigned to a user within a tenant. */
    public Set<String> resolveRoles(String userId, String tenantId) {
        JedisPooled jedis = jedisProvider.get();
        Set<String> roles = jedis.smembers("auth:roles:" + userId + ":" + tenantId);
        return roles != null ? roles : Set.of();
    }

    /** Returns the effective permission set for a user within a tenant. */
    public Set<String> resolvePermissions(String userId, String tenantId) {
        JedisPooled jedis = jedisProvider.get();
        Set<String> perms = jedis.smembers("auth:perms:" + userId + ":" + tenantId);
        return perms != null ? perms : Set.of();
    }

    /** Returns {@code true} if the user holds a specific permission within the tenant. */
    public boolean hasPermission(String userId, String tenantId, String permission) {
        JedisPooled jedis = jedisProvider.get();
        return jedis.sismember("auth:perms:" + userId + ":" + tenantId, permission);
    }

    // ── Plan / Rate Limit Lookups ───────────────────────────────────────

    /** Immutable snapshot of a plan's rate-limit and feature-gate configuration. */
    public record PlanLimits(
            String planId,
            int burstLimitPerMin,
            long evalLimitMonthly,
            long subjectLimit,
            boolean isActive) {}

    /** Returns the plan limits for a given plan ID, or {@code null} if not found. */
    public PlanLimits getPlanLimits(String planId) {
        if (planId == null) return null;

        JedisPooled jedis = jedisProvider.get();
        Map<String, String> hash = jedis.hgetAll("auth:plan:" + planId);
        if (hash == null || hash.isEmpty()) return null;

        return new PlanLimits(
                planId,
                safeInt(hash.get("burstLimit"), 0),
                safeLong(hash.get("evalLimit"), 0L),
                safeLong(hash.get("subjectLimit"), 0L),
                "true".equals(hash.get("isActive")));
    }

    /** Returns the plan ID assigned to a tenant, or {@code null} if unset. */
    public String getTenantPlan(String tenantId) {
        if (tenantId == null) return null;
        return jedisProvider.get().get("auth:tenant:" + tenantId + ":plan");
    }

    /** Returns the tenant status ("active" or "suspended"), or {@code null} if unset. */
    public String getTenantStatus(String tenantId) {
        if (tenantId == null) return null;
        return jedisProvider.get().get("auth:tenant:" + tenantId + ":status");
    }

    /**
     * Checks if a feature flag is enabled for a given plan by looking up the plan hash for a field
     * matching the feature key.
     *
     * <p>Feature keys are stored as hash fields with values "true"/"false" (e.g. {@code isPublic},
     * or future feature gates).
     */
    public boolean isFeatureEnabled(String planId, String featureKey) {
        if (planId == null || featureKey == null) return false;

        JedisPooled jedis = jedisProvider.get();
        String value = jedis.hget("auth:plan:" + planId, featureKey);
        return "true".equals(value);
    }

    // ── Internal Helpers ────────────────────────────────────────────────

    private static int safeInt(String value, int defaultValue) {
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static long safeLong(String value, long defaultValue) {
        if (value == null) return defaultValue;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
