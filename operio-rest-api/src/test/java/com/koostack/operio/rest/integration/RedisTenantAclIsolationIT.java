package com.koostack.operio.rest.integration;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import com.koostack.operio.core.Operio;
import com.koostack.operio.core.service.PlatformAdminService;
import com.koostack.operio.rest.config.OperioConfig;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestClassOrder;
import org.junit.jupiter.api.TestMethodOrder;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.exceptions.JedisAccessControlException;

/**
 * Integration test that verifies <strong>server-side</strong> Redis ACL
 * tenant isolation — the critical security boundary that prevents one
 * tenant from reading or writing another tenant's consent data in Redis.
 *
 * <h2>What this test proves</h2>
 * <ol>
 *   <li>Each tenant provisioned by {@code TenantManager} gets a dedicated
 *       Redis ACL user (e.g. {@code t_acl_tenant_a}) restricted to keys
 *       matching {@code operio:{tenantId}:*}.</li>
 *   <li>A per-tenant Redis connection (authenticated as the tenant's ACL
 *       user) <strong>can</strong> read/write keys within its own
 *       namespace.</li>
 *   <li>A per-tenant Redis connection <strong>cannot</strong> read, write,
 *       or delete keys in another tenant's namespace — Redis rejects the
 *       operation with a {@link JedisAccessControlException} (NOPERM).</li>
 *   <li>A per-tenant Redis connection <strong>cannot</strong> access
 *       platform-level keys (e.g. {@code ratelimit:*}) — those are
 *       intentionally outside the tenant ACL key pattern.</li>
 *   <li>The admin (default) Redis user <strong>can</strong> access all
 *       keys (needed for provisioning, health checks, rate limiting).</li>
 *   <li>Redis ACL reprovisioning (credential rotation / backfill) works
 *       correctly — after reprovisioning, the old password is rejected
 *       and the new password authenticates successfully.</li>
 * </ol>
 *
 * <h2>Infrastructure</h2>
 * <p>Uses the same {@link OperioTestResourceManager} as
 * {@link ProductionStackIT} to start Postgres 16 and Redis 7 containers
 * via Testcontainers. The Quarkus application boots in production mode
 * with real infrastructure.
 *
 * <h2>Test flow</h2>
 * <p>Tests are ordered to build on each other:
 * <ol>
 *   <li><strong>Setup</strong> — creates two tenants (A and B) and API
 *       keys via the REST API using the bootstrap key.</li>
 *   <li><strong>Write isolation</strong> — writes keys in each tenant's
 *       namespace using per-tenant ACL connections, verifies cross-tenant
 *       writes are rejected.</li>
 *   <li><strong>Read isolation</strong> — verifies cross-tenant reads are
 *       rejected.</li>
 *   <li><strong>Platform key isolation</strong> — verifies tenant ACL
 *       users cannot access platform-level keys.</li>
 *   <li><strong>Admin access</strong> — verifies the admin user can
 *       access all keys.</li>
 *   <li><strong>Reprovisioning</strong> — rotates credentials for tenant A,
 *       verifies old credentials fail and new ones work.</li>
 *   <li><strong>Cleanup</strong> — deletes both tenants.</li>
 * </ol>
 *
 * @see ProductionStackIT
 * @see OperioTestResourceManager
 */
@QuarkusTest
@QuarkusTestResource(OperioTestResourceManager.class)
@DisplayName("Redis Tenant ACL Isolation (Cross-Tenant Access Prevention)")
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RedisTenantAclIsolationIT {

    // -- Bootstrap key (must match test application.yml) -----------------------
    private static final String BOOTSTRAP_KEY =
        "test-bootstrap-key-for-integration-tests";

    // -- Tenants under test ---------------------------------------------------
    private static final String TENANT_A = "acl_tenant_a";
    private static final String TENANT_B = "acl_tenant_b";
    private static final String APP_NAME = "operio"; // default app name

    // -- Shared state populated by ordered tests ------------------------------
    private static volatile String tenantAApiKey;
    private static volatile String tenantBApiKey;
    private static volatile String tenantAAclUser;
    private static volatile String tenantAAclPassword;
    private static volatile String tenantBAclUser;
    private static volatile String tenantBAclPassword;

    // Redis coordinates resolved from Testcontainers
    private static volatile String redisHost;
    private static volatile int redisPort;
    private static volatile String redisAdminPassword; // may be null (no-auth)

    @Inject
    Operio operio;

    @Inject
    OperioConfig config;

    @Inject
    @SuppressWarnings("rawtypes")
    PlatformAdminService platformAdminService;

    // =======================================================================
    // 1. Setup — create tenants and resolve Redis credentials
    // =======================================================================

    @Nested
    @Order(1)
    @DisplayName("1 — Setup: Create Tenants & Resolve Credentials")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class Setup {

        @Test
        @Order(1)
        @DisplayName("resolve Redis connection from config")
        void resolveRedisConnection() {
            OperioConfig.RedisConfig redis = config.redis()
                .orElseThrow(() -> new AssertionError(
                    "Redis config must be present in production mode"
                ));

            redisHost = redis.host();
            redisPort = redis.port();
            redisAdminPassword = redis.password().orElse(null);

            assertNotNull(redisHost, "Redis host must be resolved");
            assertTrue(redisPort > 0, "Redis port must be positive");
        }

        @Test
        @Order(2)
        @DisplayName("create tenant A")
        void createTenantA() {
            given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + BOOTSTRAP_KEY)
                .body(Map.of(
                    "tenantId", TENANT_A,
                    "displayName", "ACL Test Tenant A",
                    "plan", "pro"
                ))
                .when()
                .post("/api/v1/platform/tenants")
                .then()
                .statusCode(anyOf(is(201), is(200)));
        }

        @Test
        @Order(3)
        @DisplayName("create tenant B")
        void createTenantB() {
            given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + BOOTSTRAP_KEY)
                .body(Map.of(
                    "tenantId", TENANT_B,
                    "displayName", "ACL Test Tenant B",
                    "plan", "pro"
                ))
                .when()
                .post("/api/v1/platform/tenants")
                .then()
                .statusCode(anyOf(is(201), is(200)));
        }

        @Test
        @Order(4)
        @DisplayName("create API key for tenant A")
        void createApiKeyA() {
            Response resp = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + BOOTSTRAP_KEY)
                .body(Map.of(
                    "tenantId", TENANT_A,
                    "subject", "acl-test-a",
                    "roles", Set.of("consent:read", "consent:write", "admin:read", "admin:write")
                ))
                .when()
                .post("/api/v1/platform/api-keys")
                .then()
                .statusCode(anyOf(is(201), is(200)))
                .extract()
                .response();

            tenantAApiKey = resp.jsonPath().getString("rawKey");
            assertNotNull(tenantAApiKey, "API key for tenant A must be returned");
            assertFalse(tenantAApiKey.isBlank());
        }

        @Test
        @Order(5)
        @DisplayName("create API key for tenant B")
        void createApiKeyB() {
            Response resp = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + BOOTSTRAP_KEY)
                .body(Map.of(
                    "tenantId", TENANT_B,
                    "subject", "acl-test-b",
                    "roles", Set.of("consent:read", "consent:write", "admin:read", "admin:write")
                ))
                .when()
                .post("/api/v1/platform/api-keys")
                .then()
                .statusCode(anyOf(is(201), is(200)))
                .extract()
                .response();

            tenantBApiKey = resp.jsonPath().getString("rawKey");
            assertNotNull(tenantBApiKey, "API key for tenant B must be returned");
            assertFalse(tenantBApiKey.isBlank());
        }

        @Test
        @Order(6)
        @DisplayName("resolve Redis ACL credentials from platform DB")
        void resolveAclCredentials() {
            // Look up tenant credentials directly from the platform admin service.
            // The TenantManager stores redis_acl_user and redis_acl_password in
            // the tenant table during provisioning.
            var tenantA = platformAdminService.getTenant(TENANT_A);
            var tenantB = platformAdminService.getTenant(TENANT_B);

            assertNotNull(tenantA, "Tenant A must exist");
            assertNotNull(tenantB, "Tenant B must exist");

            // Use the admin Redis connection to look up the ACL user list
            // and verify the tenant ACL users were created.
            try (Jedis adminJedis = createAdminJedis()) {
                // ACL LIST returns rules for all users
                var aclList = adminJedis.aclList();
                assertNotNull(aclList, "ACL LIST must return results");

                // Find tenant A's ACL user
                tenantAAclUser = "t_" + TENANT_A;
                boolean foundA = aclList.stream()
                    .anyMatch(rule -> rule.contains(tenantAAclUser));
                assertTrue(foundA,
                    "Redis ACL user '" + tenantAAclUser + "' must exist. ACL LIST: " + aclList);

                // Find tenant B's ACL user
                tenantBAclUser = "t_" + TENANT_B;
                boolean foundB = aclList.stream()
                    .anyMatch(rule -> rule.contains(tenantBAclUser));
                assertTrue(foundB,
                    "Redis ACL user '" + tenantBAclUser + "' must exist. ACL LIST: " + aclList);

                // Retrieve the plaintext passwords from the admin connection.
                // We can't read them from Redis (ACL GETUSER doesn't expose passwords),
                // so we read them from the platform database via jOOQ.
                // Access the internal DSLContext from the platform admin service.
                var jooqService = (com.koostack.operio.plugin.platform.jooq.JooqPlatformAdminService)
                    platformAdminService;
                var tenantRepo = jooqService.tenantRepository();

                var tenantARow = tenantRepo.findById(TENANT_A).orElseThrow();
                var tenantBRow = tenantRepo.findById(TENANT_B).orElseThrow();

                tenantAAclPassword = tenantARow.getRedisAclPassword();
                tenantBAclPassword = tenantBRow.getRedisAclPassword();

                assertNotNull(tenantAAclPassword,
                    "Tenant A redis_acl_password must not be null");
                assertFalse(tenantAAclPassword.isBlank(),
                    "Tenant A redis_acl_password must not be blank");
                assertNotNull(tenantBAclPassword,
                    "Tenant B redis_acl_password must not be null");
                assertFalse(tenantBAclPassword.isBlank(),
                    "Tenant B redis_acl_password must not be blank");
            }
        }
    }

    // =======================================================================
    // 2. Write isolation — each tenant can write own keys, not others'
    // =======================================================================

    @Nested
    @Order(2)
    @DisplayName("2 — Write Isolation: Own Namespace vs Cross-Tenant")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class WriteIsolation {

        @Test
        @Order(1)
        @DisplayName("tenant A can write keys in its own namespace")
        void tenantACanWriteOwnKeys() {
            try (JedisPooled pool = createTenantPool(tenantAAclUser, tenantAAclPassword)) {
                String key = tenantAKey("consent:policy:test_field");
                String result = pool.set(key, "granted");
                assertEquals("OK", result,
                    "Tenant A should be able to SET keys in its own namespace");
            }
        }

        @Test
        @Order(2)
        @DisplayName("tenant B can write keys in its own namespace")
        void tenantBCanWriteOwnKeys() {
            try (JedisPooled pool = createTenantPool(tenantBAclUser, tenantBAclPassword)) {
                String key = tenantBKey("consent:policy:test_field");
                String result = pool.set(key, "denied");
                assertEquals("OK", result,
                    "Tenant B should be able to SET keys in its own namespace");
            }
        }

        @Test
        @Order(3)
        @DisplayName("tenant A CANNOT write keys in tenant B's namespace")
        void tenantACannotWriteTenantBKeys() {
            try (JedisPooled pool = createTenantPool(tenantAAclUser, tenantAAclPassword)) {
                String tenantBKey = tenantBKey("consent:policy:cross_tenant_write");

                JedisAccessControlException ex = assertThrows(
                    JedisAccessControlException.class,
                    () -> pool.set(tenantBKey, "should-fail"),
                    "Tenant A's ACL user must NOT be allowed to SET keys in tenant B's namespace"
                );

                assertTrue(
                    ex.getMessage().toLowerCase().contains("noperm") ||
                    ex.getMessage().toLowerCase().contains("no permissions"),
                    "Redis error should indicate permission denial: " + ex.getMessage()
                );
            }
        }

        @Test
        @Order(4)
        @DisplayName("tenant B CANNOT write keys in tenant A's namespace")
        void tenantBCannotWriteTenantAKeys() {
            try (JedisPooled pool = createTenantPool(tenantBAclUser, tenantBAclPassword)) {
                String tenantAKey = tenantAKey("consent:policy:cross_tenant_write");

                JedisAccessControlException ex = assertThrows(
                    JedisAccessControlException.class,
                    () -> pool.set(tenantAKey, "should-fail"),
                    "Tenant B's ACL user must NOT be allowed to SET keys in tenant A's namespace"
                );

                assertTrue(
                    ex.getMessage().toLowerCase().contains("noperm") ||
                    ex.getMessage().toLowerCase().contains("no permissions"),
                    "Redis error should indicate permission denial: " + ex.getMessage()
                );
            }
        }
    }

    // =======================================================================
    // 3. Read isolation — each tenant can read own keys, not others'
    // =======================================================================

    @Nested
    @Order(3)
    @DisplayName("3 — Read Isolation: Own Namespace vs Cross-Tenant")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class ReadIsolation {

        @Test
        @Order(1)
        @DisplayName("tenant A can read its own keys")
        void tenantACanReadOwnKeys() {
            try (JedisPooled pool = createTenantPool(tenantAAclUser, tenantAAclPassword)) {
                String key = tenantAKey("consent:policy:test_field");
                String value = pool.get(key);
                assertEquals("granted", value,
                    "Tenant A should be able to GET keys from its own namespace");
            }
        }

        @Test
        @Order(2)
        @DisplayName("tenant B can read its own keys")
        void tenantBCanReadOwnKeys() {
            try (JedisPooled pool = createTenantPool(tenantBAclUser, tenantBAclPassword)) {
                String key = tenantBKey("consent:policy:test_field");
                String value = pool.get(key);
                assertEquals("denied", value,
                    "Tenant B should be able to GET keys from its own namespace");
            }
        }

        @Test
        @Order(3)
        @DisplayName("tenant A CANNOT read keys in tenant B's namespace")
        void tenantACannotReadTenantBKeys() {
            try (JedisPooled pool = createTenantPool(tenantAAclUser, tenantAAclPassword)) {
                String tenantBKey = tenantBKey("consent:policy:test_field");

                JedisAccessControlException ex = assertThrows(
                    JedisAccessControlException.class,
                    () -> pool.get(tenantBKey),
                    "Tenant A's ACL user must NOT be allowed to GET keys from tenant B's namespace"
                );

                assertTrue(
                    ex.getMessage().toLowerCase().contains("noperm") ||
                    ex.getMessage().toLowerCase().contains("no permissions"),
                    "Redis error should indicate permission denial: " + ex.getMessage()
                );
            }
        }

        @Test
        @Order(4)
        @DisplayName("tenant B CANNOT read keys in tenant A's namespace")
        void tenantBCannotReadTenantAKeys() {
            try (JedisPooled pool = createTenantPool(tenantBAclUser, tenantBAclPassword)) {
                String tenantAKey = tenantAKey("consent:policy:test_field");

                JedisAccessControlException ex = assertThrows(
                    JedisAccessControlException.class,
                    () -> pool.get(tenantAKey),
                    "Tenant B's ACL user must NOT be allowed to GET keys from tenant A's namespace"
                );

                assertTrue(
                    ex.getMessage().toLowerCase().contains("noperm") ||
                    ex.getMessage().toLowerCase().contains("no permissions"),
                    "Redis error should indicate permission denial: " + ex.getMessage()
                );
            }
        }
    }

    // =======================================================================
    // 4. Delete isolation — tenants cannot delete each other's keys
    // =======================================================================

    @Nested
    @Order(4)
    @DisplayName("4 — Delete Isolation: Cross-Tenant DEL Rejected")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class DeleteIsolation {

        @Test
        @Order(1)
        @DisplayName("tenant A can delete its own keys")
        void tenantACanDeleteOwnKeys() {
            try (JedisPooled pool = createTenantPool(tenantAAclUser, tenantAAclPassword)) {
                // Write a test key, then delete it
                String key = tenantAKey("consent:deletable:test_key");
                pool.set(key, "to-be-deleted");
                long deleted = pool.del(key);
                assertEquals(1, deleted, "Tenant A should delete its own key");
            }
        }

        @Test
        @Order(2)
        @DisplayName("tenant A CANNOT delete keys in tenant B's namespace")
        void tenantACannotDeleteTenantBKeys() {
            try (JedisPooled pool = createTenantPool(tenantAAclUser, tenantAAclPassword)) {
                String tenantBKey = tenantBKey("consent:policy:test_field");

                JedisAccessControlException ex = assertThrows(
                    JedisAccessControlException.class,
                    () -> pool.del(tenantBKey),
                    "Tenant A's ACL user must NOT be allowed to DEL keys in tenant B's namespace"
                );

                assertTrue(
                    ex.getMessage().toLowerCase().contains("noperm") ||
                    ex.getMessage().toLowerCase().contains("no permissions"),
                    "Redis error should indicate permission denial: " + ex.getMessage()
                );
            }
        }
    }

    // =======================================================================
    // 5. Platform key isolation — tenant users cannot touch ratelimit keys
    // =======================================================================

    @Nested
    @Order(5)
    @DisplayName("5 — Platform Key Isolation: Tenant Cannot Access ratelimit:* Keys")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class PlatformKeyIsolation {

        @Test
        @Order(1)
        @DisplayName("admin user can write platform-level ratelimit keys")
        void adminCanWriteRateLimitKeys() {
            try (Jedis admin = createAdminJedis()) {
                String key = "ratelimit:" + TENANT_A + ":burst:202601011200";
                String result = admin.set(key, "42");
                assertEquals("OK", result,
                    "Admin user should be able to write ratelimit keys");
            }
        }

        @Test
        @Order(2)
        @DisplayName("tenant A CANNOT read platform ratelimit keys")
        void tenantACannotReadRateLimitKeys() {
            try (JedisPooled pool = createTenantPool(tenantAAclUser, tenantAAclPassword)) {
                String rateLimitKey = "ratelimit:" + TENANT_A + ":burst:202601011200";

                JedisAccessControlException ex = assertThrows(
                    JedisAccessControlException.class,
                    () -> pool.get(rateLimitKey),
                    "Tenant ACL user must NOT be allowed to read ratelimit keys"
                );

                assertTrue(
                    ex.getMessage().toLowerCase().contains("noperm") ||
                    ex.getMessage().toLowerCase().contains("no permissions"),
                    "Redis error should indicate permission denial: " + ex.getMessage()
                );
            }
        }

        @Test
        @Order(3)
        @DisplayName("tenant A CANNOT write platform ratelimit keys")
        void tenantACannotWriteRateLimitKeys() {
            try (JedisPooled pool = createTenantPool(tenantAAclUser, tenantAAclPassword)) {
                String rateLimitKey = "ratelimit:" + TENANT_A + ":burst:202601011200";

                JedisAccessControlException ex = assertThrows(
                    JedisAccessControlException.class,
                    () -> pool.set(rateLimitKey, "9999"),
                    "Tenant ACL user must NOT be allowed to write ratelimit keys"
                );

                assertTrue(
                    ex.getMessage().toLowerCase().contains("noperm") ||
                    ex.getMessage().toLowerCase().contains("no permissions"),
                    "Redis error should indicate permission denial: " + ex.getMessage()
                );
            }
        }

        @Test
        @Order(4)
        @DisplayName("tenant A CANNOT access arbitrary non-namespaced keys")
        void tenantACannotAccessArbitraryKeys() {
            // First, set an arbitrary key as admin
            try (Jedis admin = createAdminJedis()) {
                admin.set("some:random:key", "secret");
            }

            // Tenant A should not be able to read it
            try (JedisPooled pool = createTenantPool(tenantAAclUser, tenantAAclPassword)) {
                JedisAccessControlException ex = assertThrows(
                    JedisAccessControlException.class,
                    () -> pool.get("some:random:key"),
                    "Tenant ACL user must NOT be allowed to access arbitrary keys"
                );

                assertTrue(
                    ex.getMessage().toLowerCase().contains("noperm") ||
                    ex.getMessage().toLowerCase().contains("no permissions"),
                    "Redis error should indicate permission denial: " + ex.getMessage()
                );
            }
        }
    }

    // =======================================================================
    // 6. Admin access — admin user can access all namespaces
    // =======================================================================

    @Nested
    @Order(6)
    @DisplayName("6 — Admin Access: Default User Can Access All Keys")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class AdminAccess {

        @Test
        @Order(1)
        @DisplayName("admin can read tenant A's keys")
        void adminCanReadTenantAKeys() {
            try (Jedis admin = createAdminJedis()) {
                // Write a fresh key to ensure it exists
                String key = tenantAKey("consent:admin_test:field1");
                admin.set(key, "admin-value");
                String value = admin.get(key);
                assertEquals("admin-value", value,
                    "Admin should be able to read tenant A's keys");
            }
        }

        @Test
        @Order(2)
        @DisplayName("admin can read tenant B's keys")
        void adminCanReadTenantBKeys() {
            try (Jedis admin = createAdminJedis()) {
                String key = tenantBKey("consent:policy:test_field");
                String value = admin.get(key);
                assertEquals("denied", value,
                    "Admin should be able to read tenant B's keys");
            }
        }

        @Test
        @Order(3)
        @DisplayName("admin can read platform ratelimit keys")
        void adminCanReadRateLimitKeys() {
            try (Jedis admin = createAdminJedis()) {
                String key = "ratelimit:" + TENANT_A + ":burst:202601011200";
                String value = admin.get(key);
                assertEquals("42", value,
                    "Admin should be able to read ratelimit keys");
            }
        }
    }

    // =======================================================================
    // 7. ACL reprovisioning (credential rotation)
    // =======================================================================

    @Nested
    @Order(7)
    @DisplayName("7 — ACL Reprovisioning: Credential Rotation")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class AclReprovisioning {

        @Test
        @Order(1)
        @DisplayName("reprovision tenant A — generates new password")
        void reprovisionTenantA() {
            // Access TenantManager through the service to reprovision
            var jooqService = (com.koostack.operio.plugin.platform.jooq.JooqPlatformAdminService)
                platformAdminService;

            var result = jooqService.reprovisionRedisAcl(TENANT_A);

            assertNotNull(result, "ReprovisionResult must not be null");
            assertEquals(TENANT_A, result.tenantId());
            assertEquals("t_" + TENANT_A, result.redisAclUser());
            assertNotNull(result.redisAclPassword());
            assertFalse(result.redisAclPassword().isBlank());
            assertNotNull(result.redisAclPassHash());
            assertFalse(result.redisAclPassHash().isBlank());

            // The new password should be different from the original
            assertNotEquals(tenantAAclPassword, result.redisAclPassword(),
                "Reprovisioned password should be different from the original");

            // Verify the DB was updated
            var tenantRow = jooqService.tenantRepository().findById(TENANT_A).orElseThrow();
            assertEquals(result.redisAclPassword(), tenantRow.getRedisAclPassword(),
                "Database should store the new plaintext password");
            assertEquals(result.redisAclPassHash(), tenantRow.getRedisAclPassHash(),
                "Database should store the new password hash");
        }

        @Test
        @Order(2)
        @DisplayName("old password is rejected after reprovisioning")
        void oldPasswordRejected() {
            // The old password should no longer work
            assertThrows(
                Exception.class,
                () -> {
                    try (JedisPooled pool = new JedisPooled(
                        redisHost, redisPort, tenantAAclUser, tenantAAclPassword
                    )) {
                        // Force a connection by issuing a command
                        pool.get(tenantAKey("consent:policy:test_field"));
                    }
                },
                "Old ACL password should be rejected after reprovisioning"
            );
        }

        @Test
        @Order(3)
        @DisplayName("new password authenticates and can access own keys")
        void newPasswordWorks() {
            // Read the new password from the DB
            var jooqService = (com.koostack.operio.plugin.platform.jooq.JooqPlatformAdminService)
                platformAdminService;
            var tenantRow = jooqService.tenantRepository().findById(TENANT_A).orElseThrow();
            String newPassword = tenantRow.getRedisAclPassword();

            // Write a new key with the new credentials
            try (JedisPooled pool = new JedisPooled(
                redisHost, redisPort, tenantAAclUser, newPassword
            )) {
                String key = tenantAKey("consent:after_rotation:field1");
                String result = pool.set(key, "rotated-value");
                assertEquals("OK", result,
                    "New password should work for writing to own namespace");

                String value = pool.get(key);
                assertEquals("rotated-value", value,
                    "New password should work for reading from own namespace");
            }

            // Update the shared state for subsequent tests
            tenantAAclPassword = newPassword;
        }

        @Test
        @Order(4)
        @DisplayName("cross-tenant isolation still enforced after reprovisioning")
        void isolationPreservedAfterReprovisioning() {
            // Tenant A (with new credentials) still can't access tenant B
            var jooqService = (com.koostack.operio.plugin.platform.jooq.JooqPlatformAdminService)
                platformAdminService;
            var tenantRow = jooqService.tenantRepository().findById(TENANT_A).orElseThrow();
            String newPassword = tenantRow.getRedisAclPassword();

            try (JedisPooled pool = new JedisPooled(
                redisHost, redisPort, tenantAAclUser, newPassword
            )) {
                String tenantBKey = tenantBKey("consent:policy:test_field");

                JedisAccessControlException ex = assertThrows(
                    JedisAccessControlException.class,
                    () -> pool.get(tenantBKey),
                    "Cross-tenant isolation must still be enforced after credential rotation"
                );

                assertTrue(
                    ex.getMessage().toLowerCase().contains("noperm") ||
                    ex.getMessage().toLowerCase().contains("no permissions"),
                    "Redis error should indicate permission denial: " + ex.getMessage()
                );
            }
        }
    }

    // =======================================================================
    // 8. ACL command restrictions — tenant users cannot run admin commands
    // =======================================================================

    @Nested
    @Order(8)
    @DisplayName("8 — ACL Command Restrictions: @admin/@dangerous Denied")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class CommandRestrictions {

        @Test
        @Order(1)
        @DisplayName("tenant user cannot execute ACL LIST (admin command)")
        void tenantCannotAclList() {
            try (Jedis tenantJedis = new Jedis(redisHost, redisPort)) {
                tenantJedis.auth(tenantBAclUser, tenantBAclPassword);

                JedisAccessControlException ex = assertThrows(
                    JedisAccessControlException.class,
                    () -> tenantJedis.aclList(),
                    "Tenant ACL user must NOT be allowed to run ACL LIST"
                );

                assertTrue(
                    ex.getMessage().toLowerCase().contains("noperm") ||
                    ex.getMessage().toLowerCase().contains("no permissions"),
                    "Redis error should indicate permission denial: " + ex.getMessage()
                );
            }
        }

        @Test
        @Order(2)
        @DisplayName("tenant user cannot execute FLUSHDB (dangerous command)")
        void tenantCannotFlushDb() {
            try (Jedis tenantJedis = new Jedis(redisHost, redisPort)) {
                tenantJedis.auth(tenantBAclUser, tenantBAclPassword);

                JedisAccessControlException ex = assertThrows(
                    JedisAccessControlException.class,
                    () -> tenantJedis.flushDB(),
                    "Tenant ACL user must NOT be allowed to run FLUSHDB"
                );

                assertTrue(
                    ex.getMessage().toLowerCase().contains("noperm") ||
                    ex.getMessage().toLowerCase().contains("no permissions"),
                    "Redis error should indicate permission denial: " + ex.getMessage()
                );
            }
        }

        @Test
        @Order(3)
        @DisplayName("tenant user cannot execute CONFIG (dangerous command)")
        void tenantCannotConfig() {
            try (Jedis tenantJedis = new Jedis(redisHost, redisPort)) {
                tenantJedis.auth(tenantBAclUser, tenantBAclPassword);

                JedisAccessControlException ex = assertThrows(
                    JedisAccessControlException.class,
                    () -> tenantJedis.configGet("*"),
                    "Tenant ACL user must NOT be allowed to run CONFIG GET"
                );

                assertTrue(
                    ex.getMessage().toLowerCase().contains("noperm") ||
                    ex.getMessage().toLowerCase().contains("no permissions"),
                    "Redis error should indicate permission denial: " + ex.getMessage()
                );
            }
        }
    }

    // =======================================================================
    // 9. End-to-end consent flow isolation via REST API
    // =======================================================================

    @Nested
    @Order(9)
    @DisplayName("9 — End-to-End: Consent Data Isolated Between Tenants via REST")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class EndToEndConsentIsolation {

        @Test
        @Order(1)
        @DisplayName("register schema for tenant A")
        void registerSchemaA() {
            // Register a field + category for tenant A
            given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + tenantAApiKey)
                .body(Map.of(
                    "fieldName", "email",
                    "entityName", "user",
                    "description", "Email address"
                ))
                .when()
                .post("/api/v1/admin/fields")
                .then()
                .statusCode(anyOf(is(200), is(201), is(204)));

            given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + tenantAApiKey)
                .body(Map.of(
                    "categoryName", "pii",
                    "description", "Personal info"
                ))
                .when()
                .post("/api/v1/admin/categories")
                .then()
                .statusCode(anyOf(is(200), is(201), is(204)));

            given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + tenantAApiKey)
                .body(Map.of(
                    "fieldName", "email",
                    "entityName", "user",
                    "categoryName", "pii"
                ))
                .when()
                .post("/api/v1/admin/fields/link-category")
                .then()
                .statusCode(anyOf(is(200), is(201), is(204)));
        }

        @Test
        @Order(2)
        @DisplayName("register schema for tenant B")
        void registerSchemaB() {
            given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + tenantBApiKey)
                .body(Map.of(
                    "fieldName", "email",
                    "entityName", "user",
                    "description", "Email address"
                ))
                .when()
                .post("/api/v1/admin/fields")
                .then()
                .statusCode(anyOf(is(200), is(201), is(204)));

            given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + tenantBApiKey)
                .body(Map.of(
                    "categoryName", "pii",
                    "description", "Personal info"
                ))
                .when()
                .post("/api/v1/admin/categories")
                .then()
                .statusCode(anyOf(is(200), is(201), is(204)));

            given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + tenantBApiKey)
                .body(Map.of(
                    "fieldName", "email",
                    "entityName", "user",
                    "categoryName", "pii"
                ))
                .when()
                .post("/api/v1/admin/fields/link-category")
                .then()
                .statusCode(anyOf(is(200), is(201), is(204)));
        }

        @Test
        @Order(3)
        @DisplayName("grant consent for tenant A subject — does NOT appear in tenant B")
        void grantConsentTenantA() {
            // Grant consent for subject "alice" in tenant A
            given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + tenantAApiKey)
                .body(Map.of(
                    "subject", "alice",
                    "scope", Map.of(
                        "category", "pii",
                        "fieldName", "email",
                        "entityName", "user"
                    )
                ))
                .when()
                .post("/api/v1/consent/grant")
                .then()
                .statusCode(anyOf(is(200), is(201), is(204)));

            // Evaluate in tenant A — should be granted
            given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + tenantAApiKey)
                .body(Map.of(
                    "subject", "alice",
                    "scopes", Set.of(Map.of(
                        "category", "pii",
                        "fieldName", "email",
                        "entityName", "user"
                    ))
                ))
                .when()
                .post("/api/v1/consent/evaluate")
                .then()
                .statusCode(200)
                .body("granted", hasSize(1));

            // Evaluate in tenant B — "alice" should NOT have any grants
            // (tenant B has never granted consent for alice)
            given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + tenantBApiKey)
                .body(Map.of(
                    "subject", "alice",
                    "scopes", Set.of(Map.of(
                        "category", "pii",
                        "fieldName", "email",
                        "entityName", "user"
                    ))
                ))
                .when()
                .post("/api/v1/consent/evaluate")
                .then()
                .statusCode(200)
                .body("granted", hasSize(0),
                      "denied", hasSize(1));
        }

        @Test
        @Order(4)
        @DisplayName("grant consent for tenant B subject — does NOT appear in tenant A")
        void grantConsentTenantB() {
            // Grant consent for subject "bob" in tenant B
            given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + tenantBApiKey)
                .body(Map.of(
                    "subject", "bob",
                    "scope", Map.of(
                        "category", "pii",
                        "fieldName", "email",
                        "entityName", "user"
                    )
                ))
                .when()
                .post("/api/v1/consent/grant")
                .then()
                .statusCode(anyOf(is(200), is(201), is(204)));

            // Evaluate "bob" in tenant B — should be granted
            given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + tenantBApiKey)
                .body(Map.of(
                    "subject", "bob",
                    "scopes", Set.of(Map.of(
                        "category", "pii",
                        "fieldName", "email",
                        "entityName", "user"
                    ))
                ))
                .when()
                .post("/api/v1/consent/evaluate")
                .then()
                .statusCode(200)
                .body("granted", hasSize(1));

            // Evaluate "bob" in tenant A — should NOT have any grants
            given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + tenantAApiKey)
                .body(Map.of(
                    "subject", "bob",
                    "scopes", Set.of(Map.of(
                        "category", "pii",
                        "fieldName", "email",
                        "entityName", "user"
                    ))
                ))
                .when()
                .post("/api/v1/consent/evaluate")
                .then()
                .statusCode(200)
                .body("granted", hasSize(0),
                      "denied", hasSize(1));
        }
    }

    // =======================================================================
    // 10. Cleanup — delete both tenants
    // =======================================================================

    @Nested
    @Order(10)
    @DisplayName("10 — Cleanup: Delete Test Tenants")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class Cleanup {

        @Test
        @Order(1)
        @DisplayName("delete tenant A")
        void deleteTenantA() {
            given()
                .header("Authorization", "Bearer " + BOOTSTRAP_KEY)
                .when()
                .delete("/api/v1/platform/tenants/" + TENANT_A)
                .then()
                .statusCode(anyOf(is(200), is(204)));
        }

        @Test
        @Order(2)
        @DisplayName("delete tenant B")
        void deleteTenantB() {
            given()
                .header("Authorization", "Bearer " + BOOTSTRAP_KEY)
                .when()
                .delete("/api/v1/platform/tenants/" + TENANT_B)
                .then()
                .statusCode(anyOf(is(200), is(204)));
        }

        @Test
        @Order(3)
        @DisplayName("Redis ACL users removed after tenant deletion")
        void aclUsersRemoved() {
            try (Jedis admin = createAdminJedis()) {
                var aclList = admin.aclList();

                boolean foundA = aclList.stream()
                    .anyMatch(rule -> rule.contains(tenantAAclUser));
                assertFalse(foundA,
                    "Tenant A's ACL user should be removed after deletion");

                boolean foundB = aclList.stream()
                    .anyMatch(rule -> rule.contains(tenantBAclUser));
                assertFalse(foundB,
                    "Tenant B's ACL user should be removed after deletion");
            }
        }

        @Test
        @Order(4)
        @DisplayName("tenant A's keys are still accessible by admin (orphaned — expected)")
        void orphanedKeysAccessible() {
            // After tenant deletion, leftover keys are accessible by admin.
            // This verifies that key cleanup is a separate concern from ACL
            // user removal. In production, a background job or the
            // TenantManager.dropTenant() should also flush tenant keys.
            try (Jedis admin = createAdminJedis()) {
                // The admin_test key we wrote earlier may still exist
                // This is expected — we're just verifying admin can still see it
                String key = tenantAKey("consent:admin_test:field1");
                // No assertion on value — it may or may not exist after rotation
                // The point is admin access doesn't throw
                assertDoesNotThrow(() -> admin.get(key));
            }
        }
    }

    // =======================================================================
    // Helpers
    // =======================================================================

    /**
     * Creates a short-lived admin {@link Jedis} connection (default user).
     */
    private Jedis createAdminJedis() {
        Jedis jedis = new Jedis(redisHost, redisPort);
        if (redisAdminPassword != null && !redisAdminPassword.isBlank()) {
            jedis.auth(redisAdminPassword);
        }
        return jedis;
    }

    /**
     * Creates a per-tenant {@link JedisPooled} authenticated as the given
     * ACL user. Callers must close this pool when done.
     */
    private JedisPooled createTenantPool(String aclUser, String aclPassword) {
        return new JedisPooled(redisHost, redisPort, aclUser, aclPassword);
    }

    /**
     * Constructs a key in tenant A's namespace.
     * Pattern: {@code operio:{tenantId}:{suffix}}
     */
    private static String tenantAKey(String suffix) {
        return APP_NAME + ":" + TENANT_A + ":" + suffix;
    }

    /**
     * Constructs a key in tenant B's namespace.
     * Pattern: {@code operio:{tenantId}:{suffix}}
     */
    private static String tenantBKey(String suffix) {
        return APP_NAME + ":" + TENANT_B + ":" + suffix;
    }
}
