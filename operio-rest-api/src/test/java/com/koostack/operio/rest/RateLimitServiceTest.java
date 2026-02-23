package com.koostack.operio.rest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.koostack.operio.core.Models.TenantInfo;
import com.koostack.operio.core.service.PlatformAdminService;
import com.koostack.operio.rest.config.OperioConfig;
import com.koostack.operio.rest.ratelimit.RateLimitResult;
import com.koostack.operio.rest.ratelimit.RateLimitService;
import jakarta.enterprise.inject.Instance;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import redis.clients.jedis.JedisPooled;

/**
 * Unit tests for {@link RateLimitService} — the application-scoped CDI bean
 * that manages per-tenant burst and monthly rate limiting via Redis.
 *
 * <p>Focuses on:
 * <ul>
 *   <li>Task 1.3 — Atomic Lua script INCR + EXPIRE (replaces non-atomic
 *       {@code INCR} then {@code EXPIRE} pattern)</li>
 *   <li>Correct Lua script invocation with proper KEYS and ARGV</li>
 *   <li>Burst limit enforcement (under limit, at limit, exceeded)</li>
 *   <li>Monthly evaluation limit enforcement (under, exceeded, warning at 90%)</li>
 *   <li>Graceful degradation — Redis errors return {@code UNLIMITED}</li>
 *   <li>Unavailable state — no Redis or no PlatformAdminService</li>
 *   <li>Tenant not found — returns {@code UNLIMITED}</li>
 *   <li>Tenant limit caching — re-uses cached values within TTL</li>
 *   <li>Cache eviction via {@code evictTenantCache}</li>
 *   <li>{@code peekMonthlyCount} read-only behaviour</li>
 * </ul>
 */
@DisplayName("RateLimitService")
@ExtendWith(MockitoExtension.class)
class RateLimitServiceTest {

    // -----------------------------------------------------------------------
    // Constants matching RateLimitService's private statics
    // -----------------------------------------------------------------------

    private static final String KEY_PREFIX = "ratelimit:";
    private static final String BURST_INFIX = ":burst:";
    private static final String EVAL_INFIX = ":eval:";
    private static final long BURST_TTL_SECONDS = 120;
    private static final long MONTHLY_TTL_SECONDS = Duration.ofDays(
        35
    ).toSeconds();

    /**
     * The Lua script that must be used for atomic INCR + conditional EXPIRE.
     * Kept in sync with the constant in {@code RateLimitService}.
     */
    private static final String EXPECTED_LUA_SCRIPT =
        "local curr = redis.call('INCR', KEYS[1]) " +
        "if curr == 1 then " +
        "  redis.call('EXPIRE', KEYS[1], ARGV[1]) " +
        "end " +
        "return curr";

    private static final DateTimeFormatter MINUTE_FORMATTER =
        DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC);

    private static final DateTimeFormatter MONTH_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM");

    private static final String TENANT_ID = "test-tenant-42";

    // -----------------------------------------------------------------------
    // Mocks
    // -----------------------------------------------------------------------

    @Mock
    private JedisPooled jedis;

    @Mock
    @SuppressWarnings("rawtypes")
    private Instance platformAdminInstance;

    @Mock
    @SuppressWarnings("rawtypes")
    private PlatformAdminService platformAdmin;

    @Mock
    private OperioConfig config;

    private RateLimitService service;

    // -----------------------------------------------------------------------
    // Setup
    // -----------------------------------------------------------------------

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        service = new RateLimitService();
        injectField(service, "jedis", jedis);
        injectField(service, "platformAdminInstance", platformAdminInstance);
        injectField(service, "config", config);

        // Default: platform admin service is resolvable and returns a tenant
        lenient().when(platformAdminInstance.isResolvable()).thenReturn(true);
        lenient().when(platformAdminInstance.get()).thenReturn(platformAdmin);
        lenient()
            .when(platformAdmin.getTenant(TENANT_ID))
            .thenReturn(tenantInfo(60, 10_000L, 1_000_000L));
    }

    // -----------------------------------------------------------------------
    // Helper methods
    // -----------------------------------------------------------------------

    private static TenantInfo tenantInfo(
        int burstLimitPerMin,
        long evalLimitMonthly,
        long subjectLimit
    ) {
        return new TenantInfo(
            TENANT_ID,
            "Test Tenant",
            "pro",
            "ACTIVE",
            evalLimitMonthly,
            subjectLimit,
            burstLimitPerMin,
            Instant.now()
        );
    }

    private static void injectField(
        Object target,
        String fieldName,
        Object value
    ) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Field findField(Class<?> clazz, String name) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new RuntimeException(
            "Field '" + name + "' not found in " + clazz
        );
    }

    /**
     * Stubs the Lua eval call to return the given count.
     */
    private void stubEvalReturns(long count) {
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(count);
    }

    /**
     * Stubs the Lua eval call to throw a RuntimeException simulating a
     * Redis connection failure.
     */
    private void stubEvalThrows() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenThrow(
            new RuntimeException("Redis connection lost")
        );
    }

    // =====================================================================
    // isAvailable()
    // =====================================================================

    @Nested
    @DisplayName("isAvailable()")
    class IsAvailable {

        @Test
        @DisplayName(
            "returns true when Redis and PlatformAdminService are present"
        )
        void availableWhenBothPresent() {
            assertTrue(service.isAvailable());
        }

        @Test
        @DisplayName("returns false when jedis is null (embedded mode)")
        void unavailableWhenNoRedis() throws Exception {
            injectField(service, "jedis", null);
            assertFalse(service.isAvailable());
        }

        @Test
        @DisplayName(
            "returns false when PlatformAdminService is not resolvable"
        )
        void unavailableWhenNoPlatformAdmin() {
            when(platformAdminInstance.isResolvable()).thenReturn(false);
            assertFalse(service.isAvailable());
        }
    }

    // =====================================================================
    // checkBurst() — Lua script atomicity
    // =====================================================================

    @Nested
    @DisplayName("checkBurst() — atomic Lua script")
    class CheckBurstLuaScript {

        @Test
        @DisplayName(
            "calls jedis.eval (not incr+expire) with correct Lua script"
        )
        void usesLuaScript() {
            stubEvalReturns(1L);
            service.checkBurst(TENANT_ID);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<String> scriptCaptor = ArgumentCaptor.forClass(
                String.class
            );
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(
                List.class
            );
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> argsCaptor = ArgumentCaptor.forClass(
                List.class
            );

            verify(jedis).eval(
                scriptCaptor.capture(),
                keysCaptor.capture(),
                argsCaptor.capture()
            );

            assertEquals(
                EXPECTED_LUA_SCRIPT,
                scriptCaptor.getValue(),
                "Must use the atomic INCR+EXPIRE Lua script"
            );
        }

        @Test
        @DisplayName("passes correct Redis key as KEYS[1]")
        void correctRedisKey() {
            stubEvalReturns(1L);
            service.checkBurst(TENANT_ID);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(
                List.class
            );

            verify(jedis).eval(anyString(), keysCaptor.capture(), anyList());

            List<String> keys = keysCaptor.getValue();
            assertEquals(1, keys.size(), "Should pass exactly 1 key");

            String expectedPrefix = KEY_PREFIX + TENANT_ID + BURST_INFIX;
            assertTrue(
                keys.get(0).startsWith(expectedPrefix),
                "Key should start with '" +
                    expectedPrefix +
                    "' but was: " +
                    keys.get(0)
            );
        }

        @Test
        @DisplayName("passes burst TTL (120) as ARGV[1]")
        void correctTtlArgument() {
            stubEvalReturns(1L);
            service.checkBurst(TENANT_ID);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> argsCaptor = ArgumentCaptor.forClass(
                List.class
            );

            verify(jedis).eval(anyString(), anyList(), argsCaptor.capture());

            List<String> args = argsCaptor.getValue();
            assertEquals(1, args.size(), "Should pass exactly 1 arg");
            assertEquals(
                String.valueOf(BURST_TTL_SECONDS),
                args.get(0),
                "ARGV[1] should be the burst TTL in seconds"
            );
        }

        @Test
        @DisplayName("does NOT call incr() or expire() separately")
        void doesNotCallIncrOrExpireSeparately() {
            stubEvalReturns(1L);
            service.checkBurst(TENANT_ID);

            verify(jedis, never()).incr(anyString());
            verify(jedis, never()).expire(anyString(), anyLong());
        }

        @Test
        @DisplayName("Redis key includes current UTC minute for windowing")
        void keyIncludesCurrentMinute() {
            stubEvalReturns(1L);

            Instant now = Instant.now();
            String expectedMinute = MINUTE_FORMATTER.format(now);

            service.checkBurst(TENANT_ID);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(
                List.class
            );

            verify(jedis).eval(anyString(), keysCaptor.capture(), anyList());

            String key = keysCaptor.getValue().get(0);
            assertTrue(
                key.contains(expectedMinute),
                "Key should contain current minute '" +
                    expectedMinute +
                    "' but was: " +
                    key
            );
        }
    }

    // =====================================================================
    // checkBurst() — result computation
    // =====================================================================

    @Nested
    @DisplayName("checkBurst() — result computation")
    class CheckBurstResults {

        @Test
        @DisplayName(
            "first request (count=1) — remaining = limit-1, not exceeded"
        )
        void firstRequest() {
            stubEvalReturns(1L);
            RateLimitResult result = service.checkBurst(TENANT_ID);

            assertEquals(60, result.limit());
            assertEquals(59, result.remaining());
            assertFalse(result.burstExceeded());
            assertFalse(result.isUnlimited());
        }

        @Test
        @DisplayName("under limit (count=30) — remaining = 30, not exceeded")
        void underLimit() {
            stubEvalReturns(30L);
            RateLimitResult result = service.checkBurst(TENANT_ID);

            assertEquals(60, result.limit());
            assertEquals(30, result.remaining());
            assertFalse(result.burstExceeded());
        }

        @Test
        @DisplayName("at limit (count=60) — remaining = 0, not exceeded")
        void atLimit() {
            stubEvalReturns(60L);
            RateLimitResult result = service.checkBurst(TENANT_ID);

            assertEquals(60, result.limit());
            assertEquals(0, result.remaining());
            assertFalse(
                result.burstExceeded(),
                "At the limit is not exceeded (count == limit, not count > limit)"
            );
        }

        @Test
        @DisplayName(
            "exceeded (count=61) — remaining = 0, burstExceeded = true"
        )
        void exceeded() {
            stubEvalReturns(61L);
            RateLimitResult result = service.checkBurst(TENANT_ID);

            assertEquals(60, result.limit());
            assertEquals(0, result.remaining());
            assertTrue(result.burstExceeded());
            assertTrue(result.isExceeded());
        }

        @Test
        @DisplayName("well over limit (count=200) — remaining clamped to 0")
        void wellOverLimit() {
            stubEvalReturns(200L);
            RateLimitResult result = service.checkBurst(TENANT_ID);

            assertEquals(
                0,
                result.remaining(),
                "remaining should never be negative"
            );
            assertTrue(result.burstExceeded());
        }

        @Test
        @DisplayName("burst result never sets monthlyExceeded or warning")
        void noMonthlyFlags() {
            stubEvalReturns(100L);
            RateLimitResult result = service.checkBurst(TENANT_ID);

            assertFalse(result.monthlyExceeded());
            assertFalse(result.warning());
        }

        @Test
        @DisplayName("resetAt is start of next UTC minute")
        void resetAtIsNextMinute() {
            stubEvalReturns(1L);
            RateLimitResult result = service.checkBurst(TENANT_ID);

            Instant now = Instant.now();
            Instant expectedReset = now
                .truncatedTo(ChronoUnit.MINUTES)
                .plus(1, ChronoUnit.MINUTES);

            // Allow 2-second tolerance for test execution time
            long diffSeconds = Math.abs(
                Duration.between(expectedReset, result.resetAt()).toSeconds()
            );
            assertTrue(
                diffSeconds <= 2,
                "resetAt should be ~start of next minute, diff was " +
                    diffSeconds +
                    "s"
            );
        }
    }

    // =====================================================================
    // checkBurst() — edge cases
    // =====================================================================

    @Nested
    @DisplayName("checkBurst() — edge cases")
    class CheckBurstEdgeCases {

        @Test
        @DisplayName("returns UNLIMITED when service is unavailable (no Redis)")
        void unlimitedWhenNoRedis() throws Exception {
            injectField(service, "jedis", null);

            RateLimitResult result = service.checkBurst(TENANT_ID);

            assertTrue(result.isUnlimited());
            assertFalse(result.isExceeded());
        }

        @Test
        @DisplayName(
            "returns UNLIMITED when PlatformAdminService not resolvable"
        )
        void unlimitedWhenNoPlatformAdmin() {
            when(platformAdminInstance.isResolvable()).thenReturn(false);

            RateLimitResult result = service.checkBurst(TENANT_ID);

            assertTrue(result.isUnlimited());
        }

        @Test
        @DisplayName("returns UNLIMITED when tenant not found in platform DB")
        @SuppressWarnings("unchecked")
        void unlimitedWhenTenantNotFound() {
            when(platformAdmin.getTenant("unknown-tenant")).thenReturn(null);

            RateLimitResult result = service.checkBurst("unknown-tenant");

            assertTrue(result.isUnlimited());
        }

        @Test
        @DisplayName("returns UNLIMITED on Redis eval error (fail-open)")
        void failOpenOnRedisError() {
            stubEvalThrows();

            RateLimitResult result = service.checkBurst(TENANT_ID);

            assertTrue(
                result.isUnlimited(),
                "Should fail open — allow request through on Redis error"
            );
            assertFalse(result.isExceeded());
        }

        @Test
        @DisplayName("Redis eval exception does not propagate")
        void exceptionDoesNotPropagate() {
            stubEvalThrows();
            assertDoesNotThrow(() -> service.checkBurst(TENANT_ID));
        }
    }

    // =====================================================================
    // checkMonthly() — Lua script atomicity
    // =====================================================================

    @Nested
    @DisplayName("checkMonthly() — atomic Lua script")
    class CheckMonthlyLuaScript {

        @Test
        @DisplayName("calls jedis.eval with correct Lua script")
        void usesLuaScript() {
            stubEvalReturns(1L);
            service.checkMonthly(TENANT_ID);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<String> scriptCaptor = ArgumentCaptor.forClass(
                String.class
            );

            verify(jedis).eval(scriptCaptor.capture(), anyList(), anyList());

            assertEquals(
                EXPECTED_LUA_SCRIPT,
                scriptCaptor.getValue(),
                "Monthly check must use the same atomic Lua script"
            );
        }

        @Test
        @DisplayName("passes correct Redis key with eval infix and month")
        void correctRedisKey() {
            stubEvalReturns(1L);
            service.checkMonthly(TENANT_ID);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(
                List.class
            );

            verify(jedis).eval(anyString(), keysCaptor.capture(), anyList());

            String key = keysCaptor.getValue().get(0);
            String expectedMonth = YearMonth.now(ZoneOffset.UTC).format(
                MONTH_FORMATTER
            );
            String expectedKey =
                KEY_PREFIX + TENANT_ID + EVAL_INFIX + expectedMonth;

            assertEquals(expectedKey, key);
        }

        @Test
        @DisplayName("passes monthly TTL (35 days in seconds) as ARGV[1]")
        void correctTtlArgument() {
            stubEvalReturns(1L);
            service.checkMonthly(TENANT_ID);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> argsCaptor = ArgumentCaptor.forClass(
                List.class
            );

            verify(jedis).eval(anyString(), anyList(), argsCaptor.capture());

            assertEquals(
                String.valueOf(MONTHLY_TTL_SECONDS),
                argsCaptor.getValue().get(0),
                "ARGV[1] should be the monthly TTL (35 days in seconds)"
            );
        }

        @Test
        @DisplayName("does NOT call incr() or expire() separately")
        void doesNotCallIncrOrExpireSeparately() {
            stubEvalReturns(1L);
            service.checkMonthly(TENANT_ID);

            verify(jedis, never()).incr(anyString());
            verify(jedis, never()).expire(anyString(), anyLong());
        }
    }

    // =====================================================================
    // checkMonthly() — result computation
    // =====================================================================

    @Nested
    @DisplayName("checkMonthly() — result computation")
    class CheckMonthlyResults {

        @Test
        @DisplayName(
            "first evaluation (count=1) — remaining = 9999, not exceeded"
        )
        void firstEvaluation() {
            stubEvalReturns(1L);
            RateLimitResult result = service.checkMonthly(TENANT_ID);

            assertEquals(10_000L, result.limit());
            assertEquals(9_999L, result.remaining());
            assertFalse(result.monthlyExceeded());
            assertFalse(result.warning());
            assertFalse(result.isUnlimited());
        }

        @Test
        @DisplayName(
            "under limit (count=5000) — remaining = 5000, not exceeded, no warning"
        )
        void underLimit() {
            stubEvalReturns(5_000L);
            RateLimitResult result = service.checkMonthly(TENANT_ID);

            assertEquals(5_000L, result.remaining());
            assertFalse(result.monthlyExceeded());
            assertFalse(
                result.warning(),
                "5000/10000 = 50% — not at 90% threshold"
            );
        }

        @Test
        @DisplayName(
            "at 90% threshold (count=9000) — warning = true, not exceeded"
        )
        void atWarningThreshold() {
            stubEvalReturns(9_000L);
            RateLimitResult result = service.checkMonthly(TENANT_ID);

            assertEquals(1_000L, result.remaining());
            assertTrue(
                result.warning(),
                "9000/10000 = 90% — should trigger warning"
            );
            assertFalse(result.monthlyExceeded());
        }

        @Test
        @DisplayName("above 90% (count=9500) — warning = true, not exceeded")
        void aboveWarningThreshold() {
            stubEvalReturns(9_500L);
            RateLimitResult result = service.checkMonthly(TENANT_ID);

            assertEquals(500L, result.remaining());
            assertTrue(result.warning());
            assertFalse(result.monthlyExceeded());
        }

        @Test
        @DisplayName("at limit (count=10000) — remaining = 0, not exceeded")
        void atLimit() {
            stubEvalReturns(10_000L);
            RateLimitResult result = service.checkMonthly(TENANT_ID);

            assertEquals(0L, result.remaining());
            assertFalse(
                result.monthlyExceeded(),
                "At the limit is not exceeded (count == limit)"
            );
            // Warning should still be true (count >= 90%)
            assertTrue(result.warning());
        }

        @Test
        @DisplayName(
            "exceeded (count=10001) — remaining = 0, monthlyExceeded = true"
        )
        void exceeded() {
            stubEvalReturns(10_001L);
            RateLimitResult result = service.checkMonthly(TENANT_ID);

            assertEquals(0L, result.remaining());
            assertTrue(result.monthlyExceeded());
            assertTrue(result.isExceeded());
        }

        @Test
        @DisplayName("well over limit (count=50000) — remaining clamped to 0")
        void wellOverLimit() {
            stubEvalReturns(50_000L);
            RateLimitResult result = service.checkMonthly(TENANT_ID);

            assertEquals(0L, result.remaining());
            assertTrue(result.monthlyExceeded());
        }

        @Test
        @DisplayName("monthly result never sets burstExceeded")
        void noBurstFlag() {
            stubEvalReturns(99_999L);
            RateLimitResult result = service.checkMonthly(TENANT_ID);

            assertFalse(
                result.burstExceeded(),
                "Monthly check should never set burstExceeded"
            );
        }

        @Test
        @DisplayName("warning is false when exceeded (only one flag set)")
        void warningFalseWhenExceeded() {
            stubEvalReturns(10_001L);
            RateLimitResult result = service.checkMonthly(TENANT_ID);

            // The code: boolean warning = !exceeded && count >= (long)(limit * 0.9)
            // So warning should be false when exceeded is true
            assertFalse(
                result.warning(),
                "warning should be false when monthlyExceeded is true"
            );
        }

        @Test
        @DisplayName("resetAt is first instant of next UTC month")
        void resetAtIsNextMonth() {
            stubEvalReturns(1L);
            RateLimitResult result = service.checkMonthly(TENANT_ID);

            YearMonth currentMonth = YearMonth.now(ZoneOffset.UTC);
            Instant expectedReset = currentMonth
                .plusMonths(1)
                .atDay(1)
                .atStartOfDay()
                .toInstant(ZoneOffset.UTC);

            assertEquals(expectedReset, result.resetAt());
        }
    }

    // =====================================================================
    // checkMonthly() — edge cases
    // =====================================================================

    @Nested
    @DisplayName("checkMonthly() — edge cases")
    class CheckMonthlyEdgeCases {

        @Test
        @DisplayName("returns UNLIMITED when service is unavailable")
        void unlimitedWhenUnavailable() throws Exception {
            injectField(service, "jedis", null);

            RateLimitResult result = service.checkMonthly(TENANT_ID);

            assertTrue(result.isUnlimited());
        }

        @Test
        @DisplayName("returns UNLIMITED when tenant not found")
        @SuppressWarnings("unchecked")
        void unlimitedWhenTenantNotFound() {
            when(platformAdmin.getTenant("ghost")).thenReturn(null);

            RateLimitResult result = service.checkMonthly("ghost");

            assertTrue(result.isUnlimited());
        }

        @Test
        @DisplayName("returns UNLIMITED on Redis eval error (fail-open)")
        void failOpenOnRedisError() {
            stubEvalThrows();

            RateLimitResult result = service.checkMonthly(TENANT_ID);

            assertTrue(result.isUnlimited());
        }

        @Test
        @DisplayName("Redis eval exception does not propagate")
        void exceptionDoesNotPropagate() {
            stubEvalThrows();
            assertDoesNotThrow(() -> service.checkMonthly(TENANT_ID));
        }
    }

    // =====================================================================
    // peekMonthlyCount()
    // =====================================================================

    @Nested
    @DisplayName("peekMonthlyCount()")
    class PeekMonthlyCount {

        @Test
        @DisplayName("returns count from Redis GET (no increment)")
        void returnsParsedCount() {
            String expectedKey =
                KEY_PREFIX +
                TENANT_ID +
                EVAL_INFIX +
                YearMonth.now(ZoneOffset.UTC).format(MONTH_FORMATTER);
            when(jedis.get(expectedKey)).thenReturn("4242");

            long count = service.peekMonthlyCount(TENANT_ID);

            assertEquals(4242L, count);
            // Verify it uses GET, not INCR or eval
            verify(jedis).get(expectedKey);
            verify(jedis, never()).eval(anyString(), anyList(), anyList());
            verify(jedis, never()).incr(anyString());
        }

        @Test
        @DisplayName("returns 0 when key does not exist (null)")
        void returnsZeroWhenKeyMissing() {
            when(jedis.get(anyString())).thenReturn(null);

            assertEquals(0L, service.peekMonthlyCount(TENANT_ID));
        }

        @Test
        @DisplayName("returns 0 when service is unavailable")
        void returnsZeroWhenUnavailable() throws Exception {
            injectField(service, "jedis", null);
            assertEquals(0L, service.peekMonthlyCount(TENANT_ID));
        }

        @Test
        @DisplayName("returns 0 on Redis error (does not throw)")
        void returnsZeroOnError() {
            when(jedis.get(anyString())).thenThrow(
                new RuntimeException("connection reset")
            );

            assertEquals(0L, service.peekMonthlyCount(TENANT_ID));
        }
    }

    // =====================================================================
    // Tenant limit caching
    // =====================================================================

    @Nested
    @DisplayName("Tenant limit caching")
    class TenantLimitCaching {

        @Test
        @DisplayName(
            "caches tenant limits — second call does not re-fetch from DB"
        )
        @SuppressWarnings("unchecked")
        void cachesLimitsOnSecondCall() {
            stubEvalReturns(1L);

            service.checkBurst(TENANT_ID);
            service.checkBurst(TENANT_ID);

            // getTenant should only be called once (cached on second call)
            verify(platformAdmin, times(1)).getTenant(TENANT_ID);
        }

        @Test
        @DisplayName("evictTenantCache forces re-fetch on next call")
        @SuppressWarnings("unchecked")
        void evictForcesRefetch() {
            stubEvalReturns(1L);

            service.checkBurst(TENANT_ID);
            verify(platformAdmin, times(1)).getTenant(TENANT_ID);

            service.evictTenantCache(TENANT_ID);
            service.checkBurst(TENANT_ID);

            // After eviction, should fetch again
            verify(platformAdmin, times(2)).getTenant(TENANT_ID);
        }

        @Test
        @DisplayName("evictTenantCache for unknown tenant does not throw")
        void evictUnknownTenantSafe() {
            assertDoesNotThrow(() ->
                service.evictTenantCache("nonexistent-tenant")
            );
        }

        @Test
        @DisplayName("different tenants use separate cache entries")
        @SuppressWarnings("unchecked")
        void separateCachePerTenant() {
            stubEvalReturns(1L);

            String otherTenant = "other-tenant";
            when(platformAdmin.getTenant(otherTenant)).thenReturn(
                new TenantInfo(
                    otherTenant,
                    "Other",
                    "basic",
                    "ACTIVE",
                    5_000L,
                    500_000L,
                    30,
                    Instant.now()
                )
            );

            service.checkBurst(TENANT_ID);
            service.checkBurst(otherTenant);

            verify(platformAdmin, times(1)).getTenant(TENANT_ID);
            verify(platformAdmin, times(1)).getTenant(otherTenant);
        }
    }

    // =====================================================================
    // Burst + Monthly use same Lua script (consistency check)
    // =====================================================================

    @Nested
    @DisplayName("Script consistency between burst and monthly")
    class ScriptConsistency {

        @Test
        @DisplayName("both burst and monthly use identical Lua script text")
        void sameScript() {
            stubEvalReturns(1L);

            service.checkBurst(TENANT_ID);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<String> burstScript = ArgumentCaptor.forClass(
                String.class
            );
            verify(jedis).eval(burstScript.capture(), anyList(), anyList());

            reset(jedis);
            stubEvalReturns(1L);

            service.checkMonthly(TENANT_ID);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<String> monthlyScript = ArgumentCaptor.forClass(
                String.class
            );
            verify(jedis).eval(monthlyScript.capture(), anyList(), anyList());

            assertEquals(
                burstScript.getValue(),
                monthlyScript.getValue(),
                "Burst and monthly checks should use the same Lua script"
            );
        }

        @Test
        @DisplayName("burst and monthly use different TTL values")
        void differentTtls() {
            stubEvalReturns(1L);
            service.checkBurst(TENANT_ID);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> burstArgs = ArgumentCaptor.forClass(
                List.class
            );
            verify(jedis).eval(anyString(), anyList(), burstArgs.capture());
            String burstTtl = burstArgs.getValue().get(0);

            reset(jedis);
            stubEvalReturns(1L);
            service.checkMonthly(TENANT_ID);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> monthlyArgs = ArgumentCaptor.forClass(
                List.class
            );
            verify(jedis).eval(anyString(), anyList(), monthlyArgs.capture());
            String monthlyTtl = monthlyArgs.getValue().get(0);

            assertNotEquals(
                burstTtl,
                monthlyTtl,
                "Burst and monthly should use different TTL values"
            );
            assertEquals("120", burstTtl);
            assertEquals(String.valueOf(MONTHLY_TTL_SECONDS), monthlyTtl);
        }

        @Test
        @DisplayName("burst and monthly use different key infixes")
        void differentKeyInfixes() {
            stubEvalReturns(1L);
            service.checkBurst(TENANT_ID);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> burstKeys = ArgumentCaptor.forClass(
                List.class
            );
            verify(jedis).eval(anyString(), burstKeys.capture(), anyList());
            String burstKey = burstKeys.getValue().get(0);

            reset(jedis);
            stubEvalReturns(1L);
            service.checkMonthly(TENANT_ID);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> monthlyKeys = ArgumentCaptor.forClass(
                List.class
            );
            verify(jedis).eval(anyString(), monthlyKeys.capture(), anyList());
            String monthlyKey = monthlyKeys.getValue().get(0);

            assertTrue(
                burstKey.contains(BURST_INFIX),
                "Burst key should contain ':burst:'"
            );
            assertTrue(
                monthlyKey.contains(EVAL_INFIX),
                "Monthly key should contain ':eval:'"
            );
            assertNotEquals(
                burstKey,
                monthlyKey,
                "Burst and monthly should use different Redis keys"
            );
        }
    }

    // =====================================================================
    // Lua script correctness (structural assertions)
    // =====================================================================

    @Nested
    @DisplayName("Lua script structure")
    class LuaScriptStructure {

        @Test
        @DisplayName("script contains INCR command")
        void containsIncr() {
            stubEvalReturns(1L);
            service.checkBurst(TENANT_ID);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<String> scriptCaptor = ArgumentCaptor.forClass(
                String.class
            );
            verify(jedis).eval(scriptCaptor.capture(), anyList(), anyList());

            String script = scriptCaptor.getValue();
            assertTrue(
                script.contains("redis.call('INCR', KEYS[1])"),
                "Script must call INCR on KEYS[1]"
            );
        }

        @Test
        @DisplayName("script contains conditional EXPIRE (only when curr == 1)")
        void containsConditionalExpire() {
            stubEvalReturns(1L);
            service.checkBurst(TENANT_ID);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<String> scriptCaptor = ArgumentCaptor.forClass(
                String.class
            );
            verify(jedis).eval(scriptCaptor.capture(), anyList(), anyList());

            String script = scriptCaptor.getValue();
            assertTrue(
                script.contains("if curr == 1 then"),
                "Script must conditionally EXPIRE only on first increment"
            );
            assertTrue(
                script.contains("redis.call('EXPIRE', KEYS[1], ARGV[1])"),
                "Script must call EXPIRE with KEYS[1] and ARGV[1]"
            );
        }

        @Test
        @DisplayName("script returns the counter value")
        void returnsCounter() {
            stubEvalReturns(1L);
            service.checkBurst(TENANT_ID);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<String> scriptCaptor = ArgumentCaptor.forClass(
                String.class
            );
            verify(jedis).eval(scriptCaptor.capture(), anyList(), anyList());

            String script = scriptCaptor.getValue();
            assertTrue(
                script.contains("return curr"),
                "Script must return the counter value"
            );
        }
    }

    // =====================================================================
    // Different tenant configurations
    // =====================================================================

    @Nested
    @DisplayName("Different tenant configurations")
    class DifferentTenantConfigs {

        @Test
        @DisplayName("burst limit of 1 — exceeded on second request")
        @SuppressWarnings("unchecked")
        void burstLimitOfOne() {
            when(platformAdmin.getTenant("tiny-tenant")).thenReturn(
                new TenantInfo(
                    "tiny-tenant",
                    "Tiny",
                    "free",
                    "ACTIVE",
                    100L,
                    10_000L,
                    1,
                    Instant.now()
                )
            );

            stubEvalReturns(2L);
            RateLimitResult result = service.checkBurst("tiny-tenant");

            assertEquals(1, result.limit());
            assertEquals(0, result.remaining());
            assertTrue(result.burstExceeded());
        }

        @Test
        @DisplayName("large monthly limit — 90% warning at correct threshold")
        @SuppressWarnings("unchecked")
        void largeMonthlyLimit() {
            when(platformAdmin.getTenant("enterprise")).thenReturn(
                new TenantInfo(
                    "enterprise",
                    "Enterprise Corp",
                    "enterprise",
                    "ACTIVE",
                    1_000_000L,
                    50_000_000L,
                    1000,
                    Instant.now()
                )
            );

            // 90% of 1,000,000 = 900,000
            stubEvalReturns(900_000L);
            RateLimitResult result = service.checkMonthly("enterprise");

            assertEquals(1_000_000L, result.limit());
            assertEquals(100_000L, result.remaining());
            assertTrue(result.warning());
            assertFalse(result.monthlyExceeded());
        }

        @Test
        @DisplayName("just below 90% — no warning")
        @SuppressWarnings("unchecked")
        void justBelowWarningThreshold() {
            when(platformAdmin.getTenant("almost")).thenReturn(
                new TenantInfo(
                    "almost",
                    "Almost There",
                    "pro",
                    "ACTIVE",
                    10_000L,
                    100_000L,
                    100,
                    Instant.now()
                )
            );

            // 89.99% → count = 8999 out of 10000
            stubEvalReturns(8_999L);
            RateLimitResult result = service.checkMonthly("almost");

            assertFalse(
                result.warning(),
                "8999/10000 = 89.99% which is below 90%"
            );
        }
    }

    // =====================================================================
    // Lifecycle
    // =====================================================================

    @Nested
    @DisplayName("Lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("init() with no Redis config leaves service unavailable")
        void initNoRedis() throws Exception {
            RateLimitService freshService = new RateLimitService();
            injectField(freshService, "config", config);
            injectField(
                freshService,
                "platformAdminInstance",
                platformAdminInstance
            );

            when(config.redis()).thenReturn(Optional.empty());

            // Call @PostConstruct init (package-private — need setAccessible)
            var initMethod = freshService.getClass().getDeclaredMethod("init");
            initMethod.setAccessible(true);
            initMethod.invoke(freshService);

            assertFalse(freshService.isAvailable());
        }

        @Test
        @DisplayName("shutdown() with null jedis does not throw")
        void shutdownNullJedis() throws Exception {
            RateLimitService freshService = new RateLimitService();
            injectField(freshService, "jedis", null);
            injectField(freshService, "config", config);
            injectField(
                freshService,
                "platformAdminInstance",
                platformAdminInstance
            );

            assertDoesNotThrow(() -> {
                try {
                    var shutdownMethod = freshService
                        .getClass()
                        .getDeclaredMethod("shutdown");
                    shutdownMethod.setAccessible(true);
                    shutdownMethod.invoke(freshService);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    // =====================================================================
    // Concurrent safety (structural / design assertions)
    // =====================================================================

    @Nested
    @DisplayName("Concurrency safety")
    class ConcurrencySafety {

        @Test
        @DisplayName("jedis field is volatile (checked via reflection)")
        void jedisFieldIsVolatile() throws Exception {
            Field field = findField(RateLimitService.class, "jedis");
            assertTrue(
                java.lang.reflect.Modifier.isVolatile(field.getModifiers()),
                "jedis field must be volatile for safe publication"
            );
        }

        @Test
        @DisplayName("limitsCache field is ConcurrentMap")
        void limitsCacheIsConcurrent() throws Exception {
            Field field = findField(RateLimitService.class, "limitsCache");
            field.setAccessible(true);
            Object cache = field.get(service);
            assertInstanceOf(
                java.util.concurrent.ConcurrentMap.class,
                cache,
                "limitsCache must be a ConcurrentMap"
            );
        }
    }
}
