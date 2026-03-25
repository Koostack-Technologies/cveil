package com.koostack.cveil.platform.iam.jooq;

import com.koostack.cveil.core.PlatformConstants;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.event.models.PublishDomainEventInput;
import com.koostack.cveil.core.models.AggregateKey;
import com.koostack.cveil.core.platform.iam.IamEventTypes;
import com.koostack.cveil.core.platform.iam.PermissionService;
import com.koostack.cveil.core.platform.iam.events.RoleDefinitionCreatedEvent;
import com.koostack.cveil.core.platform.iam.events.RoleDefinitionDeletedEvent;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JDBC-backed implementation of {@link PermissionService}.
 *
 * <p>Resolves role assignments from the {@code cveiliam} schema, expands them into atomic
 * permissions via the {@code role_permission} mapping, and caches results with a short TTL.
 *
 * <p>Cache is a simple {@code ConcurrentHashMap} with timestamp-based expiry to avoid adding a
 * Caffeine dependency to this module. For production with high concurrency, consider upgrading to
 * Caffeine or a distributed cache.
 */
public class JooqPermissionService implements PermissionService {

    private static final Logger LOG = LoggerFactory.getLogger(JooqPermissionService.class);
    private static final long CACHE_TTL_MS = TimeUnit.SECONDS.toMillis(60);
    private static final int CACHE_MAX_SIZE = 10_000;

    private static final String ROLE_DEF_AGGREGATE_TYPE = "role-definition";

    private final DataSource dataSource;
    private final EventService<?> eventService;

    /** Platform-scope permissions that cannot be granted to custom org roles. */
    private static final Set<String> PLATFORM_ONLY_PERMISSIONS =
            Set.of(
                    "platform:tenant:read",
                    "platform:tenant:manage",
                    "iam:user:read",
                    "iam:user:manage");

    /** Simple TTL cache: (userId + ":" + tenantId) → CacheEntry */
    private final Map<String, CacheEntry> permissionCache = new ConcurrentHashMap<>();

    private final Map<String, CacheEntry> roleCache = new ConcurrentHashMap<>();

    private record CacheEntry(Set<String> values, long expiresAt) {
        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    public JooqPermissionService(DataSource dataSource) {
        this(dataSource, null);
    }

    public JooqPermissionService(DataSource dataSource, EventService<?> eventService) {
        this.dataSource = dataSource;
        this.eventService = eventService;
    }

    // -- Permission resolution (hot path) -----------------------------------

    @Override
    public Set<String> resolvePermissions(String userId, String tenantId) {
        if (dataSource == null) return Set.of();

        evictIfOversized(permissionCache);
        String cacheKey = cacheKey(userId, tenantId);
        CacheEntry cached = permissionCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.values();
        }

        Set<String> permissions = loadPermissionsFromDb(userId, tenantId);
        permissionCache.put(
                cacheKey, new CacheEntry(permissions, System.currentTimeMillis() + CACHE_TTL_MS));
        return permissions;
    }

    @Override
    public Set<String> resolveRoles(String userId, String tenantId) {
        if (dataSource == null) return Set.of();

        evictIfOversized(roleCache);
        String cacheKey = cacheKey(userId, tenantId);
        CacheEntry cached = roleCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.values();
        }

        Set<String> roles = loadRolesFromDb(userId, tenantId);
        roleCache.put(cacheKey, new CacheEntry(roles, System.currentTimeMillis() + CACHE_TTL_MS));
        return roles;
    }

    // -- Role assignment commands -------------------------------------------

    @Override
    public void assignRole(String userId, String tenantId, String roleId, String grantedBy) {
        validateRoleAssignable(roleId);

        String sql =
                "INSERT INTO cveiliam.role_assignment"
                        + " (user_id, tenant_id, role_id, granted_by, granted_at)"
                        + " VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)"
                        + " ON CONFLICT (user_id, COALESCE(tenant_id, ''), role_id) DO NOTHING";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, tenantId);
            ps.setString(3, roleId);
            ps.setString(4, grantedBy);
            int rows = ps.executeUpdate();
            if (rows == 0) {
                LOG.debug(
                        "Role assignment already exists: user={}, tenant={}, role={}",
                        userId,
                        tenantId,
                        roleId);
            } else {
                LOG.info(
                        "Role assigned: user={}, tenant={}, role={}, by={}",
                        userId,
                        tenantId,
                        roleId,
                        grantedBy);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to assign role: " + e.getMessage(), e);
        }
        invalidateCache(userId, tenantId);
    }

    @Override
    public void removeRole(String userId, String tenantId, String roleId) {
        String sql =
                "DELETE FROM cveiliam.role_assignment WHERE user_id = ? AND COALESCE(tenant_id,"
                        + " '') = COALESCE(?, '') AND role_id = ?";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, tenantId);
            ps.setString(3, roleId);
            int rows = ps.executeUpdate();
            if (rows == 0) {
                LOG.debug(
                        "No role assignment to remove: user={}, tenant={}, role={}",
                        userId,
                        tenantId,
                        roleId);
            } else {
                LOG.info("Role removed: user={}, tenant={}, role={}", userId, tenantId, roleId);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to remove role: " + e.getMessage(), e);
        }
        invalidateCache(userId, tenantId);
    }

    // -- Role definition queries -------------------------------------------

    @Override
    public List<RoleDefinitionInfo> listRoleDefinitions(String tenantId) {
        // Return system roles + custom roles for this tenant
        String sql =
                "SELECT rd.role_id, rd.tenant_id, rd.scope, rd.display_name, rd.description,"
                        + " rd.is_system, rd.is_assignable"
                        + " FROM cveiliam.role_definition rd"
                        + " WHERE rd.tenant_id IS NULL OR rd.tenant_id = ?"
                        + " ORDER BY rd.is_system DESC, rd.display_name";
        List<RoleDefinitionInfo> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String roleId = rs.getString("role_id");
                    List<String> perms = loadPermissionsForRole(conn, roleId);
                    result.add(
                            new RoleDefinitionInfo(
                                    roleId,
                                    rs.getString("tenant_id"),
                                    rs.getString("scope"),
                                    rs.getString("display_name"),
                                    rs.getString("description"),
                                    rs.getBoolean("is_system"),
                                    rs.getBoolean("is_assignable"),
                                    perms));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to list role definitions: " + e.getMessage(), e);
        }
        return result;
    }

    @Override
    public List<RoleAssignmentInfo> listUserRolesInTenant(String userId, String tenantId) {
        String sql =
                "SELECT user_id, tenant_id, role_id, granted_by, granted_at"
                        + " FROM cveiliam.role_assignment"
                        + " WHERE user_id = ? AND (tenant_id = ? OR tenant_id IS NULL)"
                        + " ORDER BY granted_at";
        return queryRoleAssignments(sql, userId, tenantId);
    }

    @Override
    public List<RoleAssignmentInfo> listTenantRoleAssignments(String tenantId) {
        String sql =
                "SELECT user_id, tenant_id, role_id, granted_by, granted_at"
                        + " FROM cveiliam.role_assignment"
                        + " WHERE tenant_id = ?"
                        + " ORDER BY user_id, granted_at";
        List<RoleAssignmentInfo> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapRoleAssignment(rs));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to list tenant role assignments: " + e.getMessage(), e);
        }
        return result;
    }

    // -- Custom role management -------------------------------------------

    @Override
    public void createCustomRole(
            String tenantId,
            String roleId,
            String displayName,
            String description,
            List<String> permissions) {
        validatePermissionsExist(permissions);

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Insert role definition
                String insertRole =
                        "INSERT INTO cveiliam.role_definition (role_id, tenant_id, scope,"
                            + " display_name, description, is_system, is_assignable) VALUES (?, ?,"
                            + " 'tenant', ?, ?, FALSE, TRUE)";
                try (PreparedStatement ps = conn.prepareStatement(insertRole)) {
                    ps.setString(1, roleId);
                    ps.setString(2, tenantId);
                    ps.setString(3, displayName);
                    ps.setString(4, description);
                    ps.executeUpdate();
                }

                // Insert role → permission mappings
                String insertPerm =
                        "INSERT INTO cveiliam.role_permission"
                                + " (role_id, permission_id) VALUES (?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(insertPerm)) {
                    for (String permission : permissions) {
                        ps.setString(1, roleId);
                        ps.setString(2, permission);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }

                conn.commit();
                LOG.info(
                        "Custom role created: role={}, tenant={}, permissions={}",
                        roleId,
                        tenantId,
                        permissions.size());

                publishRoleDefinitionCreated(tenantId, roleId, displayName, description, permissions);
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create custom role: " + e.getMessage(), e);
        }
    }

    @Override
    public void deleteCustomRole(String tenantId, String roleId) {
        // Verify it's not a system role
        String checkSql =
                "SELECT is_system FROM cveiliam.role_definition"
                        + " WHERE role_id = ? AND (tenant_id = ? OR tenant_id IS NULL)";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(checkSql)) {
            ps.setString(1, roleId);
            ps.setString(2, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Role not found: " + roleId);
                }
                if (rs.getBoolean("is_system")) {
                    throw new IllegalArgumentException("Cannot delete system role: " + roleId);
                }
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to check role: " + e.getMessage(), e);
        }

        // CASCADE will handle role_permission and role_assignment cleanup
        String deleteSql =
                "DELETE FROM cveiliam.role_definition WHERE role_id = ? AND tenant_id = ?";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(deleteSql)) {
            ps.setString(1, roleId);
            ps.setString(2, tenantId);
            ps.executeUpdate();
            LOG.info("Custom role deleted: role={}, tenant={}", roleId, tenantId);

            publishRoleDefinitionDeleted(tenantId, roleId);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to delete custom role: " + e.getMessage(), e);
        }

        // Invalidate all caches (role deletion affects multiple users)
        invalidateAllCaches();
    }

    // -- Cache management -------------------------------------------------

    @Override
    public void invalidateCache(String userId, String tenantId) {
        String key = cacheKey(userId, tenantId);
        permissionCache.remove(key);
        roleCache.remove(key);
    }

    @Override
    public void invalidateAllCaches() {
        permissionCache.clear();
        roleCache.clear();
    }

    // -- Private helpers --------------------------------------------------

    private Set<String> loadPermissionsFromDb(String userId, String tenantId) {
        // Resolve permissions from: role_assignment → role_permission → permission
        // Include both tenant-scoped and platform-scoped (NULL tenant) assignments
        String sql =
                "SELECT DISTINCT rp.permission_id"
                        + " FROM cveiliam.role_assignment ra"
                        + " JOIN cveiliam.role_permission rp ON ra.role_id = rp.role_id"
                        + " WHERE ra.user_id = ? AND (ra.tenant_id = ? OR ra.tenant_id IS NULL)";
        Set<String> permissions = new HashSet<>();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    permissions.add(rs.getString("permission_id"));
                }
            }
        } catch (Exception e) {
            LOG.error("Failed to resolve permissions: user={}, tenant={}", userId, tenantId, e);
            return Set.of();
        }
        return Collections.unmodifiableSet(permissions);
    }

    private Set<String> loadRolesFromDb(String userId, String tenantId) {
        String sql =
                "SELECT DISTINCT ra.role_id"
                        + " FROM cveiliam.role_assignment ra"
                        + " WHERE ra.user_id = ? AND (ra.tenant_id = ? OR ra.tenant_id IS NULL)";
        Set<String> roles = new HashSet<>();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    roles.add(rs.getString("role_id"));
                }
            }
        } catch (Exception e) {
            LOG.error("Failed to resolve roles: user={}, tenant={}", userId, tenantId, e);
            return Set.of();
        }
        return Collections.unmodifiableSet(roles);
    }

    private List<String> loadPermissionsForRole(Connection conn, String roleId) throws Exception {
        String sql = "SELECT permission_id FROM cveiliam.role_permission WHERE role_id = ?";
        List<String> perms = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, roleId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    perms.add(rs.getString("permission_id"));
                }
            }
        }
        return perms;
    }

    private List<RoleAssignmentInfo> queryRoleAssignments(
            String sql, String userId, String tenantId) {
        List<RoleAssignmentInfo> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapRoleAssignment(rs));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to query role assignments: " + e.getMessage(), e);
        }
        return result;
    }

    private RoleAssignmentInfo mapRoleAssignment(ResultSet rs) throws Exception {
        Timestamp grantedTs = rs.getTimestamp("granted_at");
        return new RoleAssignmentInfo(
                rs.getString("user_id"),
                rs.getString("tenant_id"),
                rs.getString("role_id"),
                rs.getString("granted_by"),
                grantedTs != null ? grantedTs.toInstant() : Instant.now());
    }

    private void validateRoleExists(String roleId) {
        String sql = "SELECT 1 FROM cveiliam.role_definition WHERE role_id = ?";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, roleId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Role not found: " + roleId);
                }
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to validate role: " + e.getMessage(), e);
        }
    }

    /** Validates the role exists and is assignable (not super-admin or other non-assignable). */
    private void validateRoleAssignable(String roleId) {
        String sql = "SELECT is_assignable FROM cveiliam.role_definition WHERE role_id = ?";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, roleId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Role not found: " + roleId);
                }
                if (!rs.getBoolean("is_assignable")) {
                    throw new IllegalArgumentException(
                            "Role '" + roleId + "' cannot be assigned directly");
                }
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to validate role: " + e.getMessage(), e);
        }
    }

    private void validatePermissionsExist(List<String> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            return; // no permissions to validate
        }

        // Block platform-scope permissions from custom org roles
        for (String perm : permissions) {
            if (PLATFORM_ONLY_PERMISSIONS.contains(perm)) {
                throw new IllegalArgumentException(
                        "Platform-scope permission '"
                                + perm
                                + "' cannot be granted to custom org roles");
            }
        }

        String placeholders = String.join(",", permissions.stream().map(p -> "?").toList());
        String sql =
                "SELECT COUNT(*) FROM cveiliam.permission WHERE permission_id IN ("
                        + placeholders
                        + ")";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < permissions.size(); i++) {
                ps.setString(i + 1, permissions.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                if (rs.getInt(1) != permissions.size()) {
                    throw new IllegalArgumentException("One or more permission IDs are invalid");
                }
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to validate permissions: " + e.getMessage(), e);
        }
    }

    private static String cacheKey(String userId, String tenantId) {
        return userId + ":" + (tenantId != null ? tenantId : "<platform>");
    }

    /** Evict expired entries and, if still oversized, clear the entire cache. */
    private void evictIfOversized(Map<String, CacheEntry> cache) {
        if (cache.size() <= CACHE_MAX_SIZE) {
            return;
        }
        long now = System.currentTimeMillis();
        cache.entrySet().removeIf(e -> e.getValue().expiresAt() < now);
        if (cache.size() > CACHE_MAX_SIZE) {
            LOG.warn(
                    "Cache exceeded max size ({}) after expired eviction, clearing all entries",
                    CACHE_MAX_SIZE);
            cache.clear();
        }
    }

    // -- Event publishing helpers -------------------------------------------

    private void publishRoleDefinitionCreated(
            String tenantId,
            String roleId,
            String displayName,
            String description,
            List<String> permissions) {
        if (eventService == null) return;
        try {
            var event =
                    new RoleDefinitionCreatedEvent(
                            roleId,
                            tenantId,
                            "tenant",
                            displayName,
                            description,
                            false,
                            true,
                            permissions);
            var key =
                    new AggregateKey(
                            UUID.nameUUIDFromBytes(roleId.getBytes()),
                            ROLE_DEF_AGGREGATE_TYPE,
                            PlatformConstants.PLATFORM_TENANT_ID);
            eventService.publishEvent(
                    PublishDomainEventInput.of(
                            PlatformConstants.PLATFORM_TENANT_ID,
                            IamEventTypes.ROLE_DEFINITION_CREATED,
                            key,
                            event));
        } catch (Exception e) {
            LOG.warn("Failed to publish role-definition.created event for {}: {}", roleId, e.getMessage());
        }
    }

    private void publishRoleDefinitionDeleted(String tenantId, String roleId) {
        if (eventService == null) return;
        try {
            var event = new RoleDefinitionDeletedEvent(roleId, tenantId);
            var key =
                    new AggregateKey(
                            UUID.nameUUIDFromBytes(roleId.getBytes()),
                            ROLE_DEF_AGGREGATE_TYPE,
                            PlatformConstants.PLATFORM_TENANT_ID);
            eventService.publishEvent(
                    PublishDomainEventInput.of(
                            PlatformConstants.PLATFORM_TENANT_ID,
                            IamEventTypes.ROLE_DEFINITION_DELETED,
                            key,
                            event));
        } catch (Exception e) {
            LOG.warn("Failed to publish role-definition.deleted event for {}: {}", roleId, e.getMessage());
        }
    }
}
