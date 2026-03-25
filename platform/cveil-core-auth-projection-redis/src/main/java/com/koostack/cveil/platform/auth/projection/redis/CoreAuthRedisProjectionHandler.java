package com.koostack.cveil.platform.auth.projection.redis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.koostack.cveil.core.event.models.PlatformEvent;
import com.koostack.cveil.core.platform.core.CoreEventTypes;
import com.koostack.cveil.core.platform.iam.IamEventTypes;
import com.koostack.cveil.core.platform.plan.PlanEventTypes;
import com.koostack.cveil.plugin.event.jooq.StreamEventHandler;
import com.koostack.cveil.plugin.redis.common.JedisProvider;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;

/**
 * Focused projection handler that maintains a Redis read model for API-key validation, permission
 * resolution, rate-limit plan lookups, and feature gates.
 *
 * <p>Only processes auth-relevant events; all others are silently skipped. Every write is
 * idempotent so the stream can be safely replayed.
 */
public class CoreAuthRedisProjectionHandler implements StreamEventHandler {

    private static final Logger log = LoggerFactory.getLogger(CoreAuthRedisProjectionHandler.class);

    private final JedisProvider jedisProvider;
    private final ObjectMapper objectMapper;

    public CoreAuthRedisProjectionHandler(JedisProvider jedisProvider) {
        this(jedisProvider, defaultObjectMapper());
    }

    public CoreAuthRedisProjectionHandler(JedisProvider jedisProvider, ObjectMapper objectMapper) {
        this.jedisProvider = Objects.requireNonNull(jedisProvider, "jedisProvider");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    // ── StreamEventHandler ──────────────────────────────────────────────

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
            log.error("Failed to project event {} [{}]", messageId, eventType, e);
            throw new RuntimeException(e);
        }
    }

    // ── Dispatch ────────────────────────────────────────────────────────

    private void dispatch(String eventType, JsonNode node, String messageId) {
        switch (eventType) {
                // API Key / Access Token
            case IamEventTypes.ACCESS_TOKEN_CREATED -> handleAccessTokenCreated(node);
            case IamEventTypes.ACCESS_TOKEN_REVOKED -> handleAccessTokenRevoked(node);

                // Role / Permission
            case IamEventTypes.ROLE_ASSIGNED -> handleRoleAssigned(node);
            case IamEventTypes.ROLE_REMOVED -> handleRoleRemoved(node);
            case IamEventTypes.USER_DELETED -> handleUserDeleted(node);

                // Tenant lifecycle
            case CoreEventTypes.TENANT_CREATED -> handleTenantCreated(node);
            case CoreEventTypes.TENANT_PLAN_CHANGED -> handleTenantPlanChanged(node);
            case CoreEventTypes.TENANT_SUSPENDED -> handleTenantSuspended(node);
            case CoreEventTypes.TENANT_ACTIVATED -> handleTenantActivated(node);

                // Plan management
            case PlanEventTypes.PLAN_CREATED -> handlePlanCreated(node);
            case PlanEventTypes.PLAN_UPDATED -> handlePlanUpdated(node);
            case PlanEventTypes.PLAN_ACTIVATED -> handlePlanActivated(node);
            case PlanEventTypes.PLAN_DEACTIVATED -> handlePlanDeactivated(node);
            case PlanEventTypes.PLAN_STRIPE_SYNCED -> handlePlanStripeSynced(node);

            default -> log.trace("Skipping unhandled event type: {}", eventType);
        }
    }

    // ── API Key Events ──────────────────────────────────────────────────

    private void handleAccessTokenCreated(JsonNode node) {
        String tokenHash = textOrNull(node, "tokenHash");
        if (tokenHash == null) {
            log.warn("ACCESS_TOKEN_CREATED missing tokenHash — skipping");
            return;
        }

        JedisPooled jedis = jedisProvider.get();
        String key = "auth:apikey:" + tokenHash;

        Map<String, String> hash = new LinkedHashMap<>();
        hash.put("userId", textOrNull(node, "userId"));
        hash.put("tenantId", textOrNull(node, "tenantId"));
        hash.put("tokenPrefix", textOrNull(node, "tokenPrefix"));
        hash.put("status", "active");

        String roles = textOrNull(node, "roles");
        if (roles != null) hash.put("roles", roles);

        String scopes = textOrNull(node, "scopes");
        if (scopes != null) hash.put("scopes", scopes);

        String expiresAt = textOrNull(node, "expiresAt");
        if (expiresAt != null) hash.put("expiresAt", expiresAt);

        jedis.hset(key, hash);
        log.debug("Projected ACCESS_TOKEN_CREATED → {}", key);
    }

    private void handleAccessTokenRevoked(JsonNode node) {
        String tokenHash = textOrNull(node, "tokenHash");
        if (tokenHash == null) {
            log.warn("ACCESS_TOKEN_REVOKED missing tokenHash — skipping");
            return;
        }

        JedisPooled jedis = jedisProvider.get();
        String key = "auth:apikey:" + tokenHash;
        jedis.hset(key, "status", "revoked");
        log.debug("Projected ACCESS_TOKEN_REVOKED → {} status=revoked", key);
    }

    // ── Role / Permission Events ────────────────────────────────────────

    private void handleRoleAssigned(JsonNode node) {
        String userId = textOrNull(node, "userId");
        String tenantId = textOrNull(node, "tenantId");
        String role = textOrNull(node, "role");
        if (userId == null || tenantId == null || role == null) {
            log.warn("ROLE_ASSIGNED missing required fields — skipping");
            return;
        }

        JedisPooled jedis = jedisProvider.get();
        jedis.sadd("auth:roles:" + userId + ":" + tenantId, role);

        List<String> permissions = extractStringList(node, "permissions");
        if (!permissions.isEmpty()) {
            // Store role→permission mapping for rebuild on removal
            String rolePermsKey = "auth:role-perms:" + role;
            jedis.sadd(rolePermsKey, permissions.toArray(String[]::new));

            // Add permissions to the user's effective set
            String userPermsKey = "auth:perms:" + userId + ":" + tenantId;
            jedis.sadd(userPermsKey, permissions.toArray(String[]::new));
        }

        log.debug("Projected ROLE_ASSIGNED → user={} tenant={} role={}", userId, tenantId, role);
    }

    private void handleRoleRemoved(JsonNode node) {
        String userId = textOrNull(node, "userId");
        String tenantId = textOrNull(node, "tenantId");
        String role = textOrNull(node, "role");
        if (userId == null || tenantId == null || role == null) {
            log.warn("ROLE_REMOVED missing required fields — skipping");
            return;
        }

        JedisPooled jedis = jedisProvider.get();
        String rolesKey = "auth:roles:" + userId + ":" + tenantId;
        jedis.srem(rolesKey, role);

        // Rebuild effective permissions from remaining roles
        rebuildPermissions(jedis, userId, tenantId, rolesKey);

        log.debug("Projected ROLE_REMOVED → user={} tenant={} role={}", userId, tenantId, role);
    }

    /**
     * Rebuilds the effective permission set for a user+tenant pair by computing the union of
     * permissions across all remaining roles.
     */
    private void rebuildPermissions(
            JedisPooled jedis, String userId, String tenantId, String rolesKey) {
        String userPermsKey = "auth:perms:" + userId + ":" + tenantId;

        Set<String> remainingRoles = jedis.smembers(rolesKey);
        if (remainingRoles.isEmpty()) {
            jedis.del(userPermsKey);
            return;
        }

        // Collect role-perm keys for SUNIONSTORE
        String[] rolePermKeys =
                remainingRoles.stream().map(r -> "auth:role-perms:" + r).toArray(String[]::new);

        // SUNIONSTORE destination key1 key2 ...  — atomically replaces destination
        jedis.sunionstore(userPermsKey, rolePermKeys);
    }

    private void handleUserDeleted(JsonNode node) {
        String keycloakUserId = textOrNull(node, "keycloakUserId");
        if (keycloakUserId == null) {
            log.warn("USER_DELETED missing keycloakUserId — skipping");
            return;
        }

        JedisPooled jedis = jedisProvider.get();

        // Scan for role and permission keys belonging to this user
        String rolePattern = "auth:roles:" + keycloakUserId + ":*";
        String permPattern = "auth:perms:" + keycloakUserId + ":*";
        deleteByPattern(jedis, rolePattern);
        deleteByPattern(jedis, permPattern);

        log.debug("Projected USER_DELETED → cleaned auth keys for user={}", keycloakUserId);
    }

    private void deleteByPattern(JedisPooled jedis, String pattern) {
        var cursor = "0";
        var params = new redis.clients.jedis.params.ScanParams().match(pattern).count(100);
        do {
            var result = jedis.scan(cursor, params);
            for (String key : result.getResult()) {
                jedis.del(key);
            }
            cursor = result.getCursor();
        } while (!"0".equals(cursor));
    }

    // ── Tenant Lifecycle Events ─────────────────────────────────────────

    private void handleTenantCreated(JsonNode node) {
        String tenantId = textOrNull(node, "tenantId");
        String plan = textOrNull(node, "plan");
        if (tenantId == null) {
            log.warn("TENANT_CREATED missing tenantId — skipping");
            return;
        }

        JedisPooled jedis = jedisProvider.get();
        if (plan != null) {
            jedis.set("auth:tenant:" + tenantId + ":plan", plan);
        }
        jedis.set("auth:tenant:" + tenantId + ":status", "active");
        log.debug("Projected TENANT_CREATED → tenant={} plan={}", tenantId, plan);
    }

    private void handleTenantPlanChanged(JsonNode node) {
        String tenantId = textOrNull(node, "tenantId");
        String newPlan = textOrNull(node, "newPlan");
        if (tenantId == null || newPlan == null) {
            log.warn("TENANT_PLAN_CHANGED missing required fields — skipping");
            return;
        }

        jedisProvider.get().set("auth:tenant:" + tenantId + ":plan", newPlan);
        log.debug("Projected TENANT_PLAN_CHANGED → tenant={} plan={}", tenantId, newPlan);
    }

    private void handleTenantSuspended(JsonNode node) {
        String tenantId = textOrNull(node, "tenantId");
        if (tenantId == null) return;

        jedisProvider.get().set("auth:tenant:" + tenantId + ":status", "suspended");
        log.debug("Projected TENANT_SUSPENDED → tenant={}", tenantId);
    }

    private void handleTenantActivated(JsonNode node) {
        String tenantId = textOrNull(node, "tenantId");
        if (tenantId == null) return;

        jedisProvider.get().set("auth:tenant:" + tenantId + ":status", "active");
        log.debug("Projected TENANT_ACTIVATED → tenant={}", tenantId);
    }

    // ── Plan Events ─────────────────────────────────────────────────────

    private void handlePlanCreated(JsonNode node) {
        writePlanHash(node);
    }

    private void handlePlanUpdated(JsonNode node) {
        writePlanHash(node);
    }

    private void handlePlanActivated(JsonNode node) {
        String planId = textOrNull(node, "planId");
        if (planId == null) return;

        jedisProvider.get().hset("auth:plan:" + planId, "isActive", "true");
        log.debug("Projected PLAN_ACTIVATED → plan={}", planId);
    }

    private void handlePlanDeactivated(JsonNode node) {
        String planId = textOrNull(node, "planId");
        if (planId == null) return;

        jedisProvider.get().hset("auth:plan:" + planId, "isActive", "false");
        log.debug("Projected PLAN_DEACTIVATED → plan={}", planId);
    }

    private void handlePlanStripeSynced(JsonNode node) {
        String planId = textOrNull(node, "planId");
        if (planId == null) return;

        JedisPooled jedis = jedisProvider.get();
        String key = "auth:plan:" + planId;

        Map<String, String> updates = new LinkedHashMap<>();
        putIfPresent(updates, node, "stripeProductId");
        putIfPresent(updates, node, "stripePriceId");
        putIfPresent(updates, node, "stripeAnnualPriceId");

        if (!updates.isEmpty()) {
            jedis.hset(key, updates);
        }
        log.debug("Projected PLAN_STRIPE_SYNCED → plan={}", planId);
    }

    private void writePlanHash(JsonNode node) {
        String planId = textOrNull(node, "planId");
        if (planId == null) {
            log.warn("Plan event missing planId — skipping");
            return;
        }

        JedisPooled jedis = jedisProvider.get();
        String key = "auth:plan:" + planId;

        Map<String, String> hash = new LinkedHashMap<>();
        hash.put("planId", planId);

        putIfPresent(hash, node, "displayName");
        hash.put("evalLimit", String.valueOf(longOrDefault(node, "evalLimit", 0)));
        hash.put("subjectLimit", String.valueOf(longOrDefault(node, "subjectLimit", 0)));
        hash.put("burstLimit", String.valueOf(longOrDefault(node, "burstLimit", 0)));
        hash.put("isPublic", String.valueOf(boolOrDefault(node, "isPublic", false)));
        hash.put("isActive", String.valueOf(boolOrDefault(node, "isActive", true)));
        hash.put("sortOrder", String.valueOf(longOrDefault(node, "sortOrder", 0)));

        jedis.hset(key, hash);
        log.debug("Projected plan → {}", key);
    }

    // ── JSON Helpers ────────────────────────────────────────────────────

    private static String textOrNull(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child == null || child.isNull()) return null;
        return child.asText();
    }

    private static long longOrDefault(JsonNode node, String field, long defaultValue) {
        JsonNode child = node.get(field);
        if (child == null || child.isNull()) return defaultValue;
        return child.asLong(defaultValue);
    }

    private static boolean boolOrDefault(JsonNode node, String field, boolean defaultValue) {
        JsonNode child = node.get(field);
        if (child == null || child.isNull()) return defaultValue;
        return child.asBoolean(defaultValue);
    }

    private static void putIfPresent(Map<String, String> map, JsonNode node, String field) {
        String value = textOrNull(node, field);
        if (value != null) {
            map.put(field, value);
        }
    }

    /**
     * Extracts a list of strings from a JSON field that may be an array of strings or a single
     * comma-separated string.
     */
    private static List<String> extractStringList(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child == null || child.isNull()) return List.of();

        if (child.isArray()) {
            List<String> result = new ArrayList<>();
            for (JsonNode element : child) {
                String text = element.asText();
                if (text != null && !text.isBlank()) result.add(text);
            }
            return result;
        }

        // Treat as comma-separated string
        String text = child.asText();
        if (text == null || text.isBlank()) return List.of();
        return Arrays.stream(text.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static ObjectMapper defaultObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }
}
