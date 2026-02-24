package com.koostack.operio.plugin.platform.jooq;

import static com.koostack.operio.plugin.platform.jooq.generated.Tables.RATE_LIMIT_COUNTER;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.jooq.Record3;
import org.jooq.impl.DSL;

/**
 * Repository for the {@code rate_limit_counter} table — per-tenant, per-month
 * quota tracking.
 *
 * <p>Each row tracks a single metric (e.g. {@code "evaluations"},
 * {@code "subjects"}) for a single tenant in a single calendar month
 * (e.g. {@code "2025-07"}). The composite primary key is
 * {@code (tenant_id, metric, period)}.
 *
 * <p>The {@link #increment} method performs an atomic upsert: if no row
 * exists for the given {@code (tenant_id, metric, period)} triple, one is
 * inserted with {@code counter = 1}; otherwise the existing counter is
 * incremented by 1. The new counter value is returned so callers can
 * compare it against the tenant's configured limit in a single round trip.
 *
 * <p>This repository targets the <strong>shared platform schema</strong>
 * (never tenant-routed). The {@link org.jooq.DSLContext} passed in must
 * be created with {@link com.koostack.operio.plugin.jooq.common.JooqContextFactory#createContext}
 * (plain, non-tenant-aware).
 *
 * <h2>Metrics</h2>
 * <ul>
 *   <li>{@code "evaluations"} — number of consent evaluations in the month</li>
 *   <li>{@code "subjects"} — number of distinct subjects with consent
 *       records (tracked externally, incremented here)</li>
 * </ul>
 *
 * <h2>Period Format</h2>
 * <p>Periods follow the {@code YYYY-MM} format (e.g. {@code "2025-07"}).
 * The caller is responsible for computing the current period string —
 * this repository does not derive it from the system clock.
 */
public class RateLimitCounterRepository {

    private final DSLContext dsl;

    public RateLimitCounterRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    // -----------------------------------------------------------------------
    // Atomic increment (upsert)
    // -----------------------------------------------------------------------

    /**
     * Atomically increments the counter for the given tenant, metric, and
     * period. If no row exists, one is inserted with {@code counter = 1}.
     *
     * <p>Implementation uses an {@code INSERT ... ON CONFLICT DO UPDATE}
     * upsert so the operation is atomic at the database level. The
     * returned value is the counter <em>after</em> the increment —
     * callers can compare it directly against the tenant's configured
     * limit.
     *
     * @param tenantId the tenant whose counter to increment
     * @param metric   the metric name (e.g. {@code "evaluations"},
     *                 {@code "subjects"})
     * @param period   the calendar month in {@code YYYY-MM} format
     *                 (e.g. {@code "2025-07"})
     * @return the new counter value after incrementing
     */
    public long increment(String tenantId, String metric, String period) {
        // Upsert: insert with counter=1 or increment existing counter
        dsl
            .insertInto(RATE_LIMIT_COUNTER)
            .set(RATE_LIMIT_COUNTER.TENANT_ID, tenantId)
            .set(RATE_LIMIT_COUNTER.METRIC, metric)
            .set(RATE_LIMIT_COUNTER.PERIOD, period)
            .set(RATE_LIMIT_COUNTER.COUNTER, 1L)
            .onConflict(
                RATE_LIMIT_COUNTER.TENANT_ID,
                RATE_LIMIT_COUNTER.METRIC,
                RATE_LIMIT_COUNTER.PERIOD
            )
            .doUpdate()
            .set(
                RATE_LIMIT_COUNTER.COUNTER,
                RATE_LIMIT_COUNTER.COUNTER.plus(1L)
            )
            .execute();

        // Read back the current value
        return get(tenantId, metric, period);
    }

    // -----------------------------------------------------------------------
    // Queries
    // -----------------------------------------------------------------------

    /**
     * Returns the current counter value for the given tenant, metric, and
     * period. Returns {@code 0} if no row exists (the tenant has not used
     * any of the metric's quota in the given period).
     *
     * @param tenantId the tenant to query
     * @param metric   the metric name (e.g. {@code "evaluations"},
     *                 {@code "subjects"})
     * @param period   the calendar month in {@code YYYY-MM} format
     * @return the current counter value, or {@code 0} if no row exists
     */
    public long get(String tenantId, String metric, String period) {
        Long value = dsl
            .select(RATE_LIMIT_COUNTER.COUNTER)
            .from(RATE_LIMIT_COUNTER)
            .where(RATE_LIMIT_COUNTER.TENANT_ID.eq(tenantId))
            .and(RATE_LIMIT_COUNTER.METRIC.eq(metric))
            .and(RATE_LIMIT_COUNTER.PERIOD.eq(period))
            .fetchOne(RATE_LIMIT_COUNTER.COUNTER);
        return value != null ? value : 0L;
    }

    /**
     * Returns all metric counters for a given tenant and period.
     *
     * <p>Used by the usage API to retrieve a tenant's full usage snapshot
     * for a billing period (e.g. evaluations + subjects for {@code "2026-02"}).
     *
     * @param tenantId the tenant to query
     * @param period   the calendar month in {@code YYYY-MM} format
     * @return a map of metric name → counter value (empty if no rows exist)
     */
    public Map<String, Long> getByTenantAndPeriod(
        String tenantId,
        String period
    ) {
        return dsl
            .select(RATE_LIMIT_COUNTER.METRIC, RATE_LIMIT_COUNTER.COUNTER)
            .from(RATE_LIMIT_COUNTER)
            .where(RATE_LIMIT_COUNTER.TENANT_ID.eq(tenantId))
            .and(RATE_LIMIT_COUNTER.PERIOD.eq(period))
            .fetchMap(RATE_LIMIT_COUNTER.METRIC, RATE_LIMIT_COUNTER.COUNTER);
    }

    /**
     * Returns all tenant counters for a given period, grouped by tenant.
     *
     * <p>Used by the usage export API to generate a billing report for all
     * tenants in a period. Each entry in the returned list is a record
     * containing the tenant ID, metric name, and counter value.
     *
     * @param period the calendar month in {@code YYYY-MM} format
     * @return all counter rows for the period, possibly empty
     */
    public List<CounterRow> getAllByPeriod(String period) {
        return dsl
            .select(
                RATE_LIMIT_COUNTER.TENANT_ID,
                RATE_LIMIT_COUNTER.METRIC,
                RATE_LIMIT_COUNTER.COUNTER
            )
            .from(RATE_LIMIT_COUNTER)
            .where(RATE_LIMIT_COUNTER.PERIOD.eq(period))
            .orderBy(RATE_LIMIT_COUNTER.TENANT_ID, RATE_LIMIT_COUNTER.METRIC)
            .fetch(r ->
                new CounterRow(
                    r.get(RATE_LIMIT_COUNTER.TENANT_ID),
                    r.get(RATE_LIMIT_COUNTER.METRIC),
                    r.get(RATE_LIMIT_COUNTER.COUNTER)
                )
            );
    }

    /**
     * Upserts a counter value using high-water-mark semantics: sets the
     * counter to {@code GREATEST(existing_counter, value)}.
     *
     * <p>This is used by the reconciliation job to sync Redis counters
     * into Postgres without double-counting. The counter only moves
     * forward — never backwards.
     *
     * @param tenantId the tenant whose counter to upsert
     * @param metric   the metric name (e.g. {@code "evaluations"})
     * @param period   the calendar month in {@code YYYY-MM} format
     * @param value    the counter value from Redis
     */
    public void upsert(
        String tenantId,
        String metric,
        String period,
        long value
    ) {
        dsl
            .insertInto(RATE_LIMIT_COUNTER)
            .set(RATE_LIMIT_COUNTER.TENANT_ID, tenantId)
            .set(RATE_LIMIT_COUNTER.METRIC, metric)
            .set(RATE_LIMIT_COUNTER.PERIOD, period)
            .set(RATE_LIMIT_COUNTER.COUNTER, value)
            .onConflict(
                RATE_LIMIT_COUNTER.TENANT_ID,
                RATE_LIMIT_COUNTER.METRIC,
                RATE_LIMIT_COUNTER.PERIOD
            )
            .doUpdate()
            .set(
                RATE_LIMIT_COUNTER.COUNTER,
                DSL.greatest(RATE_LIMIT_COUNTER.COUNTER, DSL.val(value))
            )
            .execute();
    }

    // -----------------------------------------------------------------------
    // Reset / Delete
    // -----------------------------------------------------------------------

    /**
     * Resets (deletes) a specific counter row. Typically used in tests or
     * for manual quota resets by a super-admin.
     *
     * @param tenantId the tenant whose counter to reset
     * @param metric   the metric name
     * @param period   the calendar month in {@code YYYY-MM} format
     * @return the number of rows deleted (0 or 1)
     */
    public int reset(String tenantId, String metric, String period) {
        return dsl
            .deleteFrom(RATE_LIMIT_COUNTER)
            .where(RATE_LIMIT_COUNTER.TENANT_ID.eq(tenantId))
            .and(RATE_LIMIT_COUNTER.METRIC.eq(metric))
            .and(RATE_LIMIT_COUNTER.PERIOD.eq(period))
            .execute();
    }

    /**
     * Bulk-deletes all counter rows belonging to a tenant. Called during
     * tenant teardown by {@link JooqPlatformAdminService#deleteTenant}.
     *
     * @param tenantId the tenant whose counters to delete
     * @return the number of rows deleted
     */
    public int deleteByTenant(String tenantId) {
        return dsl
            .deleteFrom(RATE_LIMIT_COUNTER)
            .where(RATE_LIMIT_COUNTER.TENANT_ID.eq(tenantId))
            .execute();
    }

    // -----------------------------------------------------------------------
    // Inner types
    // -----------------------------------------------------------------------

    /**
     * A single counter row from the {@code rate_limit_counter} table.
     *
     * @param tenantId the tenant identifier
     * @param metric   the metric name
     * @param counter  the current counter value
     */
    public record CounterRow(String tenantId, String metric, long counter) {}
}
