package com.koostack.cveil.plugin.redis.common;

import static org.junit.jupiter.api.Assertions.*;

import com.koostack.cveil.core.models.TenantContext;
import com.koostack.cveil.plugin.redis.common.TenantAwareJedisProvider.TenantCredentialLookup;
import com.koostack.cveil.plugin.redis.common.TenantAwareJedisProvider.TenantRedisCredentials;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.JedisPooled;

/**
 * Unit tests for {@link TenantAwareJedisProvider}.
 *
 * <p>These tests verify the provider's behavior without connecting to a real Redis server. Since
 * {@link JedisPooled} creates an internal connection pool lazily (connections are only established
 * on the first command), we can safely create instances pointed at a dummy host/port for structural
 * tests.
 *
 * <p>Tests that verify actual Redis ACL enforcement (cross-tenant key isolation) belong in an
 * integration test suite with a real Redis instance (e.g. via Testcontainers).
 */
@DisplayName("TenantAwareJedisProvider")
class TenantAwareJedisProviderTest {

    // Dummy Redis coordinates — no actual connection is made in these tests
    private static final String HOST = "localhost";
    private static final int PORT = 16379;

    // Reusable admin pool (never actually connects in these tests)
    private JedisPooled adminPool = new JedisPooled(HOST, PORT);

    @AfterEach
    void closeAdminPool() {
        if (adminPool != null) {
            try {
                adminPool.close();
            } catch (Exception ignored) {
            }
        }
    }

    // -----------------------------------------------------------------------
    // Construction validation
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("constructor validation")
    class ConstructorValidation {

        @Test
        @DisplayName("rejects null host")
        void nullHost() {
            assertThrows(
                    NullPointerException.class,
                    () -> new TenantAwareJedisProvider(null, PORT, adminPool, tid -> null));
        }

        @Test
        @DisplayName("rejects blank host")
        void blankHost() {
            assertThrows(
                    NullPointerException.class,
                    () -> new TenantAwareJedisProvider("  ", PORT, adminPool, tid -> null));
        }

        @Test
        @DisplayName("rejects null adminPool")
        void nullAdminPool() {
            assertThrows(
                    NullPointerException.class,
                    () -> new TenantAwareJedisProvider(HOST, PORT, null, tid -> null));
        }

        @Test
        @DisplayName("rejects null credentialLookup")
        void nullLookup() {
            assertThrows(
                    NullPointerException.class,
                    () -> new TenantAwareJedisProvider(HOST, PORT, adminPool, null));
        }

        @Test
        @DisplayName("accepts valid arguments without throwing")
        void validArguments() {
            var provider = new TenantAwareJedisProvider(HOST, PORT, adminPool, tid -> null);
            assertNotNull(provider);
            provider.close();
        }
    }

    // -----------------------------------------------------------------------
    // TenantRedisCredentials validation
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("TenantRedisCredentials")
    class CredentialsValidation {

        @Test
        @DisplayName("rejects null username")
        void nullUsername() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new TenantRedisCredentials(null, "password123"));
        }

        @Test
        @DisplayName("rejects blank username")
        void blankUsername() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new TenantRedisCredentials("  ", "password123"));
        }

        @Test
        @DisplayName("rejects null password")
        void nullPassword() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new TenantRedisCredentials("t_acme", null));
        }

        @Test
        @DisplayName("rejects blank password")
        void blankPassword() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new TenantRedisCredentials("t_acme", "  "));
        }

        @Test
        @DisplayName("accepts valid username and password")
        void valid() {
            var creds = new TenantRedisCredentials("t_acme", "s3cr3t");
            assertEquals("t_acme", creds.aclUsername());
            assertEquals("s3cr3t", creds.aclPassword());
        }
    }

    // -----------------------------------------------------------------------
    // get() — no tenant context → admin pool
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("get() without TenantContext")
    class NoTenantContext {

        @Test
        @DisplayName("returns admin pool when no TenantContext is active")
        void returnsAdminPool() {
            var provider = new TenantAwareJedisProvider(HOST, PORT, adminPool, tid -> null);

            // No TenantContext bound → should return admin pool
            JedisPooled result = provider.get();
            assertSame(
                    adminPool, result, "Without TenantContext, get() must return the admin pool");

            assertEquals(0, provider.cachedPoolCount(), "No tenant pool should be cached");

            provider.close();
        }
    }

    // -----------------------------------------------------------------------
    // get() — with tenant context
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("get() with TenantContext")
    class WithTenantContext {

        @Test
        @DisplayName("creates and returns a per-tenant pool when credentials are available")
        void createsTenantPool() throws Exception {
            TenantCredentialLookup lookup =
                    tid -> {
                        if ("acme".equals(tid)) {
                            return new TenantRedisCredentials("t_acme", "pw_acme");
                        }
                        return null;
                    };

            var provider = new TenantAwareJedisProvider(HOST, PORT, adminPool, lookup);

            JedisPooled pool = TenantContext.callWhere("acme", () -> provider.get());

            assertNotNull(pool, "Should return a non-null per-tenant pool");
            assertNotSame(adminPool, pool, "Per-tenant pool must be distinct from admin pool");
            assertEquals(1, provider.cachedPoolCount(), "One tenant pool should be cached");

            provider.close();
        }

        @Test
        @DisplayName("returns the same cached pool on subsequent calls for the same tenant")
        void cachesTenantPool() throws Exception {
            AtomicInteger lookupCount = new AtomicInteger(0);
            TenantCredentialLookup lookup =
                    tid -> {
                        lookupCount.incrementAndGet();
                        return new TenantRedisCredentials("t_" + tid, "pw_" + tid);
                    };

            var provider = new TenantAwareJedisProvider(HOST, PORT, adminPool, lookup);

            JedisPooled first = TenantContext.callWhere("acme", () -> provider.get());
            JedisPooled second = TenantContext.callWhere("acme", () -> provider.get());
            JedisPooled third = TenantContext.callWhere("acme", () -> provider.get());

            assertSame(first, second, "Cached pool should be reused");
            assertSame(second, third, "Cached pool should be reused");
            assertEquals(
                    1,
                    lookupCount.get(),
                    "Credential lookup should be called exactly once (cached)");
            assertEquals(1, provider.cachedPoolCount());

            provider.close();
        }

        @Test
        @DisplayName("creates separate pools for different tenants")
        void separatePoolsPerTenant() throws Exception {
            TenantCredentialLookup lookup =
                    tid -> new TenantRedisCredentials("t_" + tid, "pw_" + tid);

            var provider = new TenantAwareJedisProvider(HOST, PORT, adminPool, lookup);

            JedisPooled acmePool = TenantContext.callWhere("acme", () -> provider.get());
            JedisPooled betaPool = TenantContext.callWhere("beta", () -> provider.get());

            assertNotSame(acmePool, betaPool, "Different tenants must get different pools");
            assertNotSame(acmePool, adminPool);
            assertNotSame(betaPool, adminPool);
            assertEquals(2, provider.cachedPoolCount());

            provider.close();
        }

        @Test
        @DisplayName("throws IllegalStateException when credentials are null (not found)")
        void throwsOnMissingCredentials() {
            TenantCredentialLookup lookup = tid -> null; // always returns null

            var provider = new TenantAwareJedisProvider(HOST, PORT, adminPool, lookup);

            IllegalStateException ex =
                    assertThrows(
                            IllegalStateException.class,
                            () -> TenantContext.callWhere("unknown", () -> provider.get()));

            assertTrue(
                    ex.getMessage().contains("unknown"),
                    "Exception should mention the tenant ID: " + ex.getMessage());
            assertTrue(
                    ex.getMessage().contains("No Redis ACL credentials"),
                    "Exception should describe the problem: " + ex.getMessage());
            assertEquals(0, provider.cachedPoolCount(), "Failed lookup should not cache anything");

            provider.close();
        }
    }

    // -----------------------------------------------------------------------
    // evict()
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("evict()")
    class Eviction {

        @Test
        @DisplayName("removes and closes the cached pool for a specific tenant")
        void evictsTenantPool() throws Exception {
            TenantCredentialLookup lookup =
                    tid -> new TenantRedisCredentials("t_" + tid, "pw_" + tid);

            var provider = new TenantAwareJedisProvider(HOST, PORT, adminPool, lookup);

            // Create pools for two tenants
            TenantContext.callWhere("acme", () -> provider.get());
            TenantContext.callWhere("beta", () -> provider.get());
            assertEquals(2, provider.cachedPoolCount());

            // Evict one
            provider.evict("acme");
            assertEquals(
                    1, provider.cachedPoolCount(), "Only one pool should remain after eviction");

            // The beta pool should still be there
            JedisPooled betaPool = TenantContext.callWhere("beta", () -> provider.get());
            assertNotNull(betaPool);

            provider.close();
        }

        @Test
        @DisplayName("evicting a non-cached tenant is a no-op")
        void evictNonExistent() {
            var provider = new TenantAwareJedisProvider(HOST, PORT, adminPool, tid -> null);

            // Should not throw
            provider.evict("nonexistent");
            assertEquals(0, provider.cachedPoolCount());

            provider.close();
        }

        @Test
        @DisplayName("after eviction, next get() triggers a fresh credential lookup")
        void evictionTriggersNewLookup() throws Exception {
            AtomicInteger lookupCount = new AtomicInteger(0);
            TenantCredentialLookup lookup =
                    tid -> {
                        lookupCount.incrementAndGet();
                        return new TenantRedisCredentials("t_" + tid, "pw_" + tid);
                    };

            var provider = new TenantAwareJedisProvider(HOST, PORT, adminPool, lookup);

            // First access — lookup #1
            JedisPooled first = TenantContext.callWhere("acme", () -> provider.get());
            assertEquals(1, lookupCount.get());

            // Second access — cached, no new lookup
            TenantContext.callWhere("acme", () -> provider.get());
            assertEquals(1, lookupCount.get());

            // Evict
            provider.evict("acme");

            // Third access — must trigger lookup #2
            JedisPooled afterEvict = TenantContext.callWhere("acme", () -> provider.get());
            assertEquals(2, lookupCount.get(), "Eviction should force a fresh credential lookup");

            // The pool instance should be new (not the same object)
            assertNotSame(
                    first, afterEvict, "After eviction, a new pool instance should be created");

            provider.close();
        }
    }

    // -----------------------------------------------------------------------
    // close()
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("close()")
    class Close {

        @Test
        @DisplayName("closes all tenant pools and admin pool")
        void closesAllPools() throws Exception {
            TenantCredentialLookup lookup =
                    tid -> new TenantRedisCredentials("t_" + tid, "pw_" + tid);

            var provider = new TenantAwareJedisProvider(HOST, PORT, adminPool, lookup);

            TenantContext.callWhere("acme", () -> provider.get());
            TenantContext.callWhere("beta", () -> provider.get());
            assertEquals(2, provider.cachedPoolCount());

            // close() should not throw
            assertDoesNotThrow(() -> provider.close());

            // After close, the cache should be empty
            assertEquals(
                    0,
                    provider.cachedPoolCount(),
                    "All cached pools should be cleared after close()");

            // Recreate adminPool for @AfterEach cleanup since close() closed it
            adminPool = new JedisPooled(HOST, PORT);
        }

        @Test
        @DisplayName("close is idempotent — calling twice does not throw")
        void closeIdempotent() throws Exception {
            TenantCredentialLookup lookup =
                    tid -> new TenantRedisCredentials("t_" + tid, "pw_" + tid);

            var provider = new TenantAwareJedisProvider(HOST, PORT, adminPool, lookup);
            TenantContext.callWhere("acme", () -> provider.get());

            // Close twice
            assertDoesNotThrow(() -> provider.close());
            assertDoesNotThrow(() -> provider.close());

            adminPool = new JedisPooled(HOST, PORT);
        }
    }

    // -----------------------------------------------------------------------
    // cachedPoolCount()
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("cachedPoolCount()")
    class CachedPoolCount {

        @Test
        @DisplayName("starts at zero")
        void initiallyZero() {
            var provider = new TenantAwareJedisProvider(HOST, PORT, adminPool, tid -> null);
            assertEquals(0, provider.cachedPoolCount());
            provider.close();
            adminPool = new JedisPooled(HOST, PORT);
        }

        @Test
        @DisplayName("increments as tenant pools are created")
        void incrementsOnCreation() throws Exception {
            TenantCredentialLookup lookup =
                    tid -> new TenantRedisCredentials("t_" + tid, "pw_" + tid);

            var provider = new TenantAwareJedisProvider(HOST, PORT, adminPool, lookup);

            TenantContext.callWhere("t1", () -> provider.get());
            assertEquals(1, provider.cachedPoolCount());

            TenantContext.callWhere("t2", () -> provider.get());
            assertEquals(2, provider.cachedPoolCount());

            TenantContext.callWhere("t3", () -> provider.get());
            assertEquals(3, provider.cachedPoolCount());

            // Accessing existing tenant doesn't increment
            TenantContext.callWhere("t1", () -> provider.get());
            assertEquals(3, provider.cachedPoolCount());

            provider.close();
            adminPool = new JedisPooled(HOST, PORT);
        }
    }

    // -----------------------------------------------------------------------
    // Thread safety
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("thread safety")
    class ThreadSafety {

        @Test
        @DisplayName("concurrent get() calls for the same tenant return the same pool")
        void concurrentSameTenant() throws Exception {
            AtomicInteger lookupCount = new AtomicInteger(0);
            TenantCredentialLookup lookup =
                    tid -> {
                        lookupCount.incrementAndGet();
                        // Small delay to increase chance of race
                        try {
                            Thread.sleep(5);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return new TenantRedisCredentials("t_" + tid, "pw_" + tid);
                    };

            var provider = new TenantAwareJedisProvider(HOST, PORT, adminPool, lookup);

            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            ConcurrentMap<Integer, JedisPooled> results = new ConcurrentHashMap<>();

            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                executor.submit(
                        () -> {
                            try {
                                startLatch.await(); // All threads start together
                                JedisPooled pool =
                                        TenantContext.callWhere(
                                                "concurrent_tenant", () -> provider.get());
                                results.put(idx, pool);
                            } catch (Exception e) {
                                // Swallow — we check results below
                            }
                        });
            }

            // Release all threads simultaneously
            startLatch.countDown();
            executor.shutdown();
            executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);

            // All threads should get the same pool instance
            assertEquals(
                    threadCount, results.size(), "All threads should have completed successfully");

            JedisPooled expected = results.values().iterator().next();
            for (var entry : results.entrySet()) {
                assertSame(
                        expected,
                        entry.getValue(),
                        "Thread " + entry.getKey() + " got a different pool instance");
            }

            assertEquals(
                    1, provider.cachedPoolCount(), "Only one pool should exist for the tenant");

            // ConcurrentHashMap.computeIfAbsent guarantees at-most-once
            // creation, but the lookup might be called once or a few times
            // depending on implementation. The key guarantee is that only
            // one pool ends up in the cache.
            assertTrue(lookupCount.get() >= 1, "Lookup should have been called at least once");

            provider.close();
            adminPool = new JedisPooled(HOST, PORT);
        }

        @Test
        @DisplayName("concurrent get() calls for different tenants create separate pools")
        void concurrentDifferentTenants() throws Exception {
            TenantCredentialLookup lookup =
                    tid -> new TenantRedisCredentials("t_" + tid, "pw_" + tid);

            var provider = new TenantAwareJedisProvider(HOST, PORT, adminPool, lookup);

            int tenantCount = 20;
            ExecutorService executor = Executors.newFixedThreadPool(tenantCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            ConcurrentMap<String, JedisPooled> results = new ConcurrentHashMap<>();

            for (int i = 0; i < tenantCount; i++) {
                final String tenantId = "tenant_" + i;
                executor.submit(
                        () -> {
                            try {
                                startLatch.await();
                                JedisPooled pool =
                                        TenantContext.callWhere(tenantId, () -> provider.get());
                                results.put(tenantId, pool);
                            } catch (Exception e) {
                                // Swallow
                            }
                        });
            }

            startLatch.countDown();
            executor.shutdown();
            executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);

            assertEquals(tenantCount, results.size(), "All tenants should have pools");
            assertEquals(
                    tenantCount,
                    provider.cachedPoolCount(),
                    "Each tenant should have its own cached pool");

            // Verify all pools are distinct
            var uniquePools = new java.util.HashSet<>(results.values());
            assertEquals(tenantCount, uniquePools.size(), "All pools should be distinct objects");

            provider.close();
            adminPool = new JedisPooled(HOST, PORT);
        }
    }

    // -----------------------------------------------------------------------
    // Admin pool fallback — mixed context
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("mixed context (admin + tenant)")
    class MixedContext {

        @Test
        @DisplayName("returns admin pool outside TenantContext, tenant pool inside")
        void switchesBetweenContexts() throws Exception {
            TenantCredentialLookup lookup =
                    tid -> new TenantRedisCredentials("t_" + tid, "pw_" + tid);

            var provider = new TenantAwareJedisProvider(HOST, PORT, adminPool, lookup);

            // Outside tenant context → admin
            JedisPooled outside = provider.get();
            assertSame(adminPool, outside);

            // Inside tenant context → per-tenant
            JedisPooled inside = TenantContext.callWhere("acme", () -> provider.get());
            assertNotSame(adminPool, inside);

            // Back outside → admin again
            JedisPooled outsideAgain = provider.get();
            assertSame(adminPool, outsideAgain);

            provider.close();
            adminPool = new JedisPooled(HOST, PORT);
        }
    }

    // -----------------------------------------------------------------------
    // RedisClientFactory ACL overload
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("RedisClientFactory ACL overload")
    class FactoryAclOverload {

        @Test
        @DisplayName("null username creates client with default user (no ACL)")
        void nullUsername() {
            // null username means "use default Redis user" — should not throw
            assertDoesNotThrow(() -> RedisClientFactory.createClient(HOST, PORT, null, "password"));
        }

        @Test
        @DisplayName("rejects blank username")
        void blankUsername() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> RedisClientFactory.createClient(HOST, PORT, "  ", "password"));
        }

        @Test
        @DisplayName("rejects null password")
        void nullPassword() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> RedisClientFactory.createClient(HOST, PORT, "t_acme", (String) null));
        }

        @Test
        @DisplayName("rejects blank password")
        void blankPassword() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> RedisClientFactory.createClient(HOST, PORT, "t_acme", "  "));
        }

        @Test
        @DisplayName("creates client with valid username and password")
        void validAclClient() {
            JedisPooled pool = RedisClientFactory.createClient(HOST, PORT, "t_acme", "s3cr3t");
            assertNotNull(pool);
            pool.close();
        }
    }
}
