package com.koostack.operio.rest.ratelimit;

import com.koostack.operio.core.Models.TenantInfo;
import com.koostack.operio.core.service.PlatformAdminService;
import com.koostack.operio.plugin.platform.jooq.JooqPlatformAdminService;
import com.koostack.operio.plugin.platform.jooq.RateLimitCounterRepository;
import com.koostack.operio.rest.config.OperioConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;

/**
 * Application-scoped CDI bean that encapsulates all rate-limiting logic for
 * the Operio REST API.
 *
 * <p>This service manages two independent rate-limiting dimensions per tenant:
 *
 * <ol>
 *   <li><strong>Burst limit</strong> — per-minute request cap enforced via
 *       Redis {@code INCR} on a key with 60-second TTL. Prevents any single
 *       tenant from overwhelming the service in a short time window.</li>
 *   <li><strong>Monthly evaluation limit</strong> — per-calendar-month cap
 *       on consent evaluation requests, enforced via Redis {@code INCR} on
 *       a key with ~35-day TTL. This is the billable usage counter that
 *       corresponds to the tenant's subscription plan.</li>
 * </ol>
 *
 * <h2>Redis Key Patterns</h2>
 * <table>
 *   <tr><th>Dimension</th><th>Key Pattern</th><th>TTL</th></tr>
 *   <tr><td>Burst</td>
 *       <td>{@code ratelimit:{tenantId}:burst:{YYYYMMddHHmm}}</td>
 *       <td>120 seconds (2 × window for safety)</td></tr>
 *   <tr><td>Monthly eval</td>
 *       <td>{@code ratelimit:{tenantId}:eval:{YYYY-MM}}</td>
 *       <td>35 days (covers month rollover + grace)</td></tr>
 * </table>
 *
 * <h2>Tenant Limit Caching</h2>
 * <p>To avoid hitting the platform database on every request, tenant rate
 * limits ({@code burstLimitPerMin}, {@code evalLimitMonthly}) are cached
 * in a local {@link ConcurrentHashMap} with a configurable TTL (default:
 * 5 minutes). Cache entries are lazily loaded on first access and evicted
 * after TTL expiry. This provides a good balance between freshness (limit
 * changes propagate within minutes) and performance (one DB call per tenant
 * per cache TTL window).
 *
 * <h2>Graceful Degradation</h2>
 * <p>If Redis is unavailable (e.g. connection failure), the service logs a
 * warning and <strong>allows</strong> the request through rather than
 * blocking all traffic. This is a deliberate fail-open strategy: it's
 * better to temporarily lose rate limiting than to deny all service during
 * a transient Redis outage.
 *
 * <h2>Embedded Mode</h2>
 * <p>When {@code operio.mode=embedded}, Redis is not configured and the
 * {@link PlatformAdminService} is null. In this case the service reports
 * itself as unavailable ({@link #isAvailable()} returns {@code false}) and
 * the {@code RateLimitFilter} skips rate limiting entirely.
 *
 * @see RateLimitFilter
 * @see RateLimitResult
 */
@ApplicationScoped
public class RateLimitService {

    private static final Logger LOG = LoggerFactory.getLogger(
        RateLimitService.class
    );

    // -----------------------------------------------------------------------
    // Redis key patterns
    // -----------------------------------------------------------------------

    /**
     * Lua script that atomically increments a key and sets its TTL on first
     * creation. This eliminates the race condition in the previous non-atomic
     * {@code INCR} + {@code EXPIRE} sequence where a crash between the two
     * commands could leave a key without a TTL, causing it to persist
     * indefinitely and permanently block the tenant.
     *
     * <p>KEYS[1] = the rate-limit counter key<br>
     * ARGV[1] = the TTL in seconds to set when the key is first created
     *
     * <p>Returns the new counter value (as a Lua number / Java {@code Long}).
     */
    private static final String LUA_INCR_EXPIRE =
        "local curr = redis.call('INCR', KEYS[1]) " +
        "if curr == 1 then " +
        "  redis.call('EXPIRE', KEYS[1], ARGV[1]) " +
        "end " +
        "return curr";

    /** Prefix for all rate-limit Redis keys. */
    private static final String KEY_PREFIX = "ratelimit:";

    /** Infix for burst (per-minute) counters. */
    private static final String BURST_INFIX = ":burst:";

    /** Infix for monthly evaluation counters. */
    private static final String EVAL_INFIX = ":eval:";

    /** Infix for monthly subject HyperLogLog keys. */
    private static final String SUBJECT_INFIX = ":subjects:";

    // -----------------------------------------------------------------------
    // TTL constants
    // -----------------------------------------------------------------------

    /**
     * TTL for burst counter keys — 120 seconds (2× the 60-second window).
     * The extra headroom ensures the key is still readable for response
     * headers even if the request spans the minute boundary.
     */
    private static final long BURST_TTL_SECONDS = 120;

    /**
     * TTL for monthly evaluation counter keys — 35 days in seconds.
     * Covers the longest calendar month (31 days) plus grace for
     * month-boundary queries and periodic DB sync.
     */
    private static final long MONTHLY_TTL_SECONDS = Duration.ofDays(
        35
    ).toSeconds();

    // -----------------------------------------------------------------------
    // Tenant limit cache
    // -----------------------------------------------------------------------

    /** How long cached tenant limits remain valid before re-fetch. */
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    /**
     * Formatter for the burst key's minute component.
     * Format: {@code yyyyMMddHHmm} (e.g. {@code 202501151423}).
     */
    private static final DateTimeFormatter MINUTE_FORMATTER =
        DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC);

    /**
     * Formatter for the monthly evaluation key's month component.
     * Format: {@code yyyy-MM} (e.g. {@code 2025-01}).
     */
    private static final DateTimeFormatter MONTH_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM");

    // -----------------------------------------------------------------------
    // Injected dependencies
    // -----------------------------------------------------------------------

    @Inject
    OperioConfig config;

    @Inject
    @SuppressWarnings("rawtypes")
    jakarta.enterprise.inject.Instance<
        PlatformAdminService
    > platformAdminInstance;

    // -----------------------------------------------------------------------
    // Internal state
    // -----------------------------------------------------------------------

    /**
     * Redis client for rate-limit counters. Created at {@code @PostConstruct}
     * if Redis is configured (production mode). Null in embedded mode.
     */
    private volatile JedisPooled jedis;

    /**
     * Local cache of tenant rate limits, keyed by tenant ID. Entries are
     * lazily loaded from {@link PlatformAdminService} and evicted after
     * {@link #CACHE_TTL}.
     */
    private final ConcurrentMap<String, CachedTenantLimits> limitsCache =
        new ConcurrentHashMap<>();

    /**
     * Counter repository for durable Postgres persistence of usage metrics.
     * Lazily resolved from the {@link PlatformAdminService} on first use.
     * Null if the platform admin service is not available (embedded mode).
     */
    private volatile RateLimitCounterRepository counterRepository;

    /** Whether the counter repository has been resolved (even if null). */
    private volatile boolean counterRepositoryResolved;

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    /**
     * Initializes the Redis client connection if Redis is configured.
     *
     * <p>In embedded mode ({@code operio.mode=embedded}) or when Redis
     * configuration is absent, the client remains {@code null} and all
     * rate-limiting operations are no-ops.
     *
     * <h2>Security Note — Admin Redis Connection (Intentional)</h2>
     * <p>This service intentionally connects as the Redis {@code default}
     * (admin) user, <strong>not</strong> a per-tenant ACL user. Rate-limit
     * counters are platform-level administrative data — not tenant consent
     * data. The keys use the prefix {@code ratelimit:{tenantId}:...} which
     * is deliberately <em>outside</em> the tenant ACL key pattern
     * ({@code {appName}:{tenantId}:*}), so tenant ACL users cannot read
     * or manipulate their own rate-limit counters.
     *
     * <p>This is the correct security boundary: consent data is isolated
     * per-tenant via {@link com.koostack.operio.plugin.redis.common.TenantAwareJedisProvider}
     * (each tenant's Redis operations authenticate as that tenant's ACL
     * user), while rate limiting remains a platform-level concern managed
     * exclusively through the admin connection.
     */
    @PostConstruct
    void init() {
        Optional<OperioConfig.RedisConfig> redisOpt = config.redis();
        if (redisOpt.isEmpty()) {
            LOG.info(
                "RateLimitService: Redis not configured — rate limiting disabled"
            );
            return;
        }

        OperioConfig.RedisConfig redis = redisOpt.get();
        try {
            // Authenticate as the Redis default (admin) user — see the
            // "Security Note" in the Javadoc above for why this is
            // intentional and correct for rate-limit counters.
            jedis = redis
                .password()
                .filter(pw -> !pw.isBlank())
                .map(pw ->
                    new JedisPooled(redis.host(), redis.port(), null, pw)
                )
                .orElseGet(() -> new JedisPooled(redis.host(), redis.port()));

            LOG.info(
                "RateLimitService: initialized (redis={}:{})",
                redis.host(),
                redis.port()
            );
        } catch (Exception e) {
            LOG.error(
                "RateLimitService: failed to create Redis client — " +
                    "rate limiting will be disabled",
                e
            );
            jedis = null;
        }
    }

    /**
     * Closes the Redis client on shutdown to release pooled connections.
     */
    @PreDestroy
    void shutdown() {
        if (jedis != null) {
            try {
                jedis.close();
                LOG.debug("RateLimitService: Redis client closed");
            } catch (Exception e) {
                LOG.warn("RateLimitService: error closing Redis client", e);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Whether rate limiting is operationally available.
     *
     * <p>Returns {@code true} only when both Redis is connected and a
     * {@link PlatformAdminService} is available for looking up tenant
     * limits. In embedded mode this returns {@code false}.
     *
     * @return {@code true} if rate limiting is active
     */
    public boolean isAvailable() {
        return jedis != null && platformAdminInstance.isResolvable();
    }

    /**
     * Checks and increments the burst (per-minute) rate limit for the given
     * tenant.
     *
     * <p>This method atomically increments a Redis counter keyed to the
     * current UTC minute and checks whether the new count exceeds the
     * tenant's configured {@code burstLimitPerMin}.
     *
     * @param tenantId the tenant identifier — must not be {@code null}
     * @return the rate limit result containing the limit, current count,
     *         and reset time
     */
    public RateLimitResult checkBurst(String tenantId) {
        if (!isAvailable()) {
            return RateLimitResult.UNLIMITED;
        }

        TenantLimits limits = getTenantLimits(tenantId);
        if (limits == null) {
            // Tenant not found in platform DB — allow through but log
            LOG.warn(
                "RateLimitService: tenant '{}' not found — " +
                    "skipping burst check",
                tenantId
            );
            return RateLimitResult.UNLIMITED;
        }

        Instant now = Instant.now();
        String minuteKey = MINUTE_FORMATTER.format(now);
        String redisKey = KEY_PREFIX + tenantId + BURST_INFIX + minuteKey;

        try {
            // Atomic INCR + conditional EXPIRE via Lua script.
            // Guarantees the key always has a TTL even if the process
            // crashes immediately after the increment.
            long count = (long) jedis.eval(
                LUA_INCR_EXPIRE,
                List.of(redisKey),
                List.of(String.valueOf(BURST_TTL_SECONDS))
            );

            int limit = limits.burstLimitPerMin();

            // Reset time is the start of the next minute
            Instant resetAt = now
                .truncatedTo(ChronoUnit.MINUTES)
                .plus(1, ChronoUnit.MINUTES);

            long remaining = Math.max(0, limit - count);
            boolean exceeded = count > limit;

            return new RateLimitResult(
                limit,
                remaining,
                resetAt,
                exceeded,
                /* warning */ false,
                /* monthlyExceeded */ false,
                /* subjectLimitExceeded */ false
            );
        } catch (Exception e) {
            LOG.warn(
                "RateLimitService: Redis error during burst check for " +
                    "tenant '{}' — allowing request through",
                tenantId,
                e
            );
            return RateLimitResult.UNLIMITED;
        }
    }

    /**
     * Checks and increments the monthly evaluation counter for the given
     * tenant.
     *
     * <p>This method should be called only for consent evaluation endpoints
     * (e.g. {@code POST /api/v1/consent/evaluate}). It atomically
     * increments a Redis counter keyed to the current UTC year-month and
     * checks against the tenant's configured {@code evalLimitMonthly}.
     *
     * <p>After the Redis increment succeeds, the count is also persisted
     * to Postgres via {@link RateLimitCounterRepository#increment} as the
     * durable billing record. The Postgres write is best-effort — if it
     * fails, the Redis counter (which powers rate limiting) still works
     * and the reconciliation job will catch up.
     *
     * <p>At 90% utilization, the result includes a warning flag so the
     * filter can set the {@code X-RateLimit-Warning} header.
     *
     * @param tenantId the tenant identifier — must not be {@code null}
     * @return the rate limit result with monthly limit details
     */
    public RateLimitResult checkMonthly(String tenantId) {
        if (!isAvailable()) {
            return RateLimitResult.UNLIMITED;
        }

        TenantLimits limits = getTenantLimits(tenantId);
        if (limits == null) {
            LOG.warn(
                "RateLimitService: tenant '{}' not found — " +
                    "skipping monthly check",
                tenantId
            );
            return RateLimitResult.UNLIMITED;
        }

        YearMonth currentMonth = YearMonth.now(ZoneOffset.UTC);
        String monthKey = currentMonth.format(MONTH_FORMATTER);
        String redisKey = KEY_PREFIX + tenantId + EVAL_INFIX + monthKey;

        try {
            // Atomic INCR + conditional EXPIRE via Lua script.
            long count = (long) jedis.eval(
                LUA_INCR_EXPIRE,
                List.of(redisKey),
                List.of(String.valueOf(MONTHLY_TTL_SECONDS))
            );

            // Best-effort: persist the count to Postgres for durable billing
            persistCounterAsync(tenantId, "evaluations", monthKey);

            long limit = limits.evalLimitMonthly();

            // Reset time is the first instant of the next month
            Instant resetAt = currentMonth
                .plusMonths(1)
                .atDay(1)
                .atStartOfDay()
                .toInstant(ZoneOffset.UTC);

            long remaining = Math.max(0, limit - count);
            boolean exceeded = count > limit;
            boolean warning = !exceeded && count >= (long) (limit * 0.9);

            return new RateLimitResult(
                limit,
                remaining,
                resetAt,
                /* burstExceeded */ false,
                warning,
                exceeded,
                /* subjectLimitExceeded */ false
            );
        } catch (Exception e) {
            LOG.warn(
                "RateLimitService: Redis error during monthly check for " +
                    "tenant '{}' — allowing request through",
                tenantId,
                e
            );
            return RateLimitResult.UNLIMITED;
        }
    }

    /**
     * Checks whether adding a new subject would exceed the tenant's
     * subject limit for the current billing period.
     *
     * <p>Uses Redis HyperLogLog ({@code PFADD}/{@code PFCOUNT}) for
     * approximate distinct subject counting with ~0.81% error rate and
     * only 12KB memory per tenant per month. This is accurate enough
     * for limit enforcement while being extremely memory-efficient.
     *
     * <p>The subject is always added to the HyperLogLog (even if the
     * limit is not exceeded) so that the count is accurate for future
     * checks. When {@code PFADD} returns 1 (new subject), the count
     * is also persisted to Postgres as a durable billing record.
     *
     * @param tenantId  the tenant identifier — must not be {@code null}
     * @param subjectId the data subject being granted/revoked consent
     * @return the rate limit result with subject limit details
     */
    public RateLimitResult checkSubjectLimit(
        String tenantId,
        String subjectId
    ) {
        if (!isAvailable()) {
            return RateLimitResult.UNLIMITED;
        }

        TenantLimits limits = getTenantLimits(tenantId);
        if (limits == null) {
            return RateLimitResult.UNLIMITED;
        }

        long subjectLimit = limits.subjectLimit();
        if (subjectLimit <= 0) {
            // No subject limit configured — allow through
            return RateLimitResult.UNLIMITED;
        }

        YearMonth currentMonth = YearMonth.now(ZoneOffset.UTC);
        String monthKey = currentMonth.format(MONTH_FORMATTER);
        String hllKey = KEY_PREFIX + tenantId + SUBJECT_INFIX + monthKey;

        try {
            // PFADD returns 1 if the HLL internal registers were altered
            // (i.e. the subject is likely new)
            long added = jedis.pfadd(hllKey, subjectId);

            // Ensure the HLL key has a TTL (35 days like other monthly keys)
            if (added == 1) {
                jedis.expire(hllKey, MONTHLY_TTL_SECONDS);

                // Best-effort: persist new subject count to Postgres
                persistCounterAsync(tenantId, "subjects", monthKey);
            }

            // PFCOUNT gives approximate distinct count
            long count = jedis.pfcount(hllKey);

            Instant resetAt = currentMonth
                .plusMonths(1)
                .atDay(1)
                .atStartOfDay()
                .toInstant(ZoneOffset.UTC);

            boolean exceeded = count > subjectLimit;
            long remaining = Math.max(0, subjectLimit - count);

            return new RateLimitResult(
                subjectLimit,
                remaining,
                resetAt,
                /* burstExceeded */ false,
                /* warning */ false,
                /* monthlyExceeded */ false,
                exceeded
            );
        } catch (Exception e) {
            LOG.warn(
                "RateLimitService: Redis error during subject limit check " +
                    "for tenant '{}' — allowing request through",
                tenantId,
                e
            );
            return RateLimitResult.UNLIMITED;
        }
    }

    /**
     * Returns the approximate number of distinct subjects for a tenant
     * in the current month without modifying the HyperLogLog.
     *
     * @param tenantId the tenant identifier
     * @return the approximate subject count, or {@code 0} if unavailable
     */
    public long peekSubjectCount(String tenantId) {
        if (!isAvailable()) {
            return 0;
        }

        YearMonth currentMonth = YearMonth.now(ZoneOffset.UTC);
        String monthKey = currentMonth.format(MONTH_FORMATTER);
        String hllKey = KEY_PREFIX + tenantId + SUBJECT_INFIX + monthKey;

        try {
            return jedis.pfcount(hllKey);
        } catch (Exception e) {
            LOG.debug(
                "RateLimitService: failed to peek subject count for '{}'",
                tenantId,
                e
            );
            return 0;
        }
    }

    /**
     * Returns the current monthly evaluation count for a tenant without
     * incrementing.
     *
     * <p>Used by the filter to include monthly usage information in
     * response headers even on non-evaluation endpoints.
     *
     * @param tenantId the tenant identifier
     * @return the current count, or {@code 0} if unavailable
     */
    public long peekMonthlyCount(String tenantId) {
        if (!isAvailable()) {
            return 0;
        }

        YearMonth currentMonth = YearMonth.now(ZoneOffset.UTC);
        String monthKey = currentMonth.format(MONTH_FORMATTER);
        String redisKey = KEY_PREFIX + tenantId + EVAL_INFIX + monthKey;

        try {
            String value = jedis.get(redisKey);
            return value != null ? Long.parseLong(value) : 0;
        } catch (Exception e) {
            LOG.debug(
                "RateLimitService: failed to peek monthly count for '{}'",
                tenantId,
                e
            );
            return 0;
        }
    }

    /**
     * Evicts the cached tenant limits for the given tenant, forcing a
     * re-fetch on the next rate-limit check.
     *
     * <p>Call this after a plan change or limit update so the new limits
     * take effect immediately rather than waiting for the cache TTL to
     * expire.
     *
     * @param tenantId the tenant whose cached limits should be evicted
     */
    public void evictTenantCache(String tenantId) {
        limitsCache.remove(tenantId);
        LOG.debug(
            "RateLimitService: evicted cached limits for tenant '{}'",
            tenantId
        );
    }

    // -----------------------------------------------------------------------
    // Postgres counter persistence (best-effort)
    // -----------------------------------------------------------------------

    /**
     * Persists a counter increment to Postgres via the
     * {@link RateLimitCounterRepository}. Best-effort: failures are logged
     * but never propagate to the caller.
     *
     * @param tenantId the tenant whose counter to persist
     * @param metric   the metric name
     * @param period   the billing period (YYYY-MM)
     */
    private void persistCounterAsync(
        String tenantId,
        String metric,
        String period
    ) {
        try {
            RateLimitCounterRepository repo = getCounterRepository();
            if (repo != null) {
                repo.increment(tenantId, metric, period);
            }
        } catch (Exception e) {
            LOG.debug(
                "RateLimitService: best-effort Postgres counter sync failed " +
                    "for tenant='{}', metric='{}', period='{}' — " +
                    "reconciliation job will catch up",
                tenantId,
                metric,
                period,
                e
            );
        }
    }

    /**
     * Lazily resolves the {@link RateLimitCounterRepository} from the
     * platform admin service. Cached after first resolution.
     */
    RateLimitCounterRepository getCounterRepository() {
        if (!counterRepositoryResolved) {
            synchronized (this) {
                if (!counterRepositoryResolved) {
                    try {
                        if (platformAdminInstance.isResolvable()) {
                            Object platformAdmin = platformAdminInstance.get();
                            if (
                                platformAdmin instanceof
                                    JooqPlatformAdminService jooqAdmin
                            ) {
                                counterRepository =
                                    jooqAdmin.rateLimitCounterRepository();
                            }
                        }
                    } catch (Exception e) {
                        LOG.debug(
                            "RateLimitService: could not resolve counter repository",
                            e
                        );
                    }
                    counterRepositoryResolved = true;
                }
            }
        }
        return counterRepository;
    }

    // -----------------------------------------------------------------------
    // Tenant limit caching
    // -----------------------------------------------------------------------

    /**
     * Retrieves the rate limits for a tenant, using the local cache when
     * available and not expired.
     *
     * @param tenantId the tenant identifier
     * @return the tenant's limits, or {@code null} if the tenant is not
     *         found in the platform database
     */
    private TenantLimits getTenantLimits(String tenantId) {
        CachedTenantLimits cached = limitsCache.get(tenantId);
        if (cached != null && !cached.isExpired()) {
            return cached.limits();
        }

        // Fetch from platform admin service
        try {
            @SuppressWarnings("rawtypes")
            PlatformAdminService platformAdmin = platformAdminInstance.get();
            TenantInfo tenant = platformAdmin.getTenant(tenantId);
            if (tenant == null) {
                return null;
            }

            TenantLimits limits = new TenantLimits(
                tenant.burstLimitPerMin(),
                tenant.evalLimitMonthly(),
                tenant.subjectLimit()
            );

            limitsCache.put(
                tenantId,
                new CachedTenantLimits(limits, Instant.now())
            );

            LOG.debug(
                "RateLimitService: loaded limits for tenant '{}' " +
                    "(burst={}/min, eval={}/month)",
                tenantId,
                limits.burstLimitPerMin(),
                limits.evalLimitMonthly()
            );

            return limits;
        } catch (Exception e) {
            LOG.warn(
                "RateLimitService: failed to load limits for tenant '{}' " +
                    "— using cached value if available, else null",
                tenantId,
                e
            );
            // Return stale cached value if available (better than nothing)
            return cached != null ? cached.limits() : null;
        }
    }

    // -----------------------------------------------------------------------
    // Inner types
    // -----------------------------------------------------------------------

    /**
     * Immutable snapshot of a tenant's rate limit configuration, extracted
     * from the platform {@code tenant} table.
     *
     * @param burstLimitPerMin  maximum requests per minute
     * @param evalLimitMonthly  maximum consent evaluations per month
     * @param subjectLimit      maximum distinct data subjects (reserved for
     *                          future use)
     */
    record TenantLimits(
        int burstLimitPerMin,
        long evalLimitMonthly,
        long subjectLimit
    ) {}

    /**
     * Cache entry wrapping {@link TenantLimits} with a timestamp for
     * TTL-based expiry.
     */
    private record CachedTenantLimits(TenantLimits limits, Instant fetchedAt) {
        /**
         * Whether this cache entry has exceeded the {@link #CACHE_TTL} and
         * should be re-fetched from the database.
         *
         * @return {@code true} if expired
         */
        boolean isExpired() {
            return Instant.now().isAfter(fetchedAt.plus(CACHE_TTL));
        }
    }
}
