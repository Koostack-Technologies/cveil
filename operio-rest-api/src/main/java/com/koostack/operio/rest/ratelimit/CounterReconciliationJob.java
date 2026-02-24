package com.koostack.operio.rest.ratelimit;

import com.koostack.operio.core.Models.TenantInfo;
import com.koostack.operio.core.service.PlatformAdminService;
import com.koostack.operio.plugin.platform.jooq.JooqPlatformAdminService;
import com.koostack.operio.plugin.platform.jooq.RateLimitCounterRepository;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scheduled job that reconciles Redis rate-limit counters with the
 * durable Postgres {@code rate_limit_counter} table.
 *
 * <p>Runs every 5 minutes. For each active tenant, reads the current
 * Redis monthly evaluation counter and subject HyperLogLog count, then
 * upserts them into Postgres using high-water-mark semantics
 * ({@code GREATEST(existing, redis_value)}). This catches any counter
 * increments that were lost due to transient Postgres errors in the
 * real-time best-effort sync path.
 *
 * <h2>Idempotency</h2>
 * <p>The {@link RateLimitCounterRepository#upsert} method uses
 * {@code GREATEST(counter, ?)} — the counter only moves forward, never
 * backwards. Running this job more frequently than needed is harmless.
 *
 * <h2>Graceful degradation</h2>
 * <p>If the platform admin service is not available (embedded mode) or
 * Redis is not configured, the job exits immediately. Individual tenant
 * failures are logged and do not abort the batch.
 *
 * @see RateLimitService
 * @see RateLimitCounterRepository
 */
@ApplicationScoped
public class CounterReconciliationJob {

    private static final Logger LOG = LoggerFactory.getLogger(
        CounterReconciliationJob.class
    );

    private static final DateTimeFormatter MONTH_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM");

    @Inject
    RateLimitService rateLimitService;

    @Inject
    @SuppressWarnings("rawtypes")
    jakarta.enterprise.inject.Instance<PlatformAdminService> platformAdminInstance;

    /**
     * Reconciles Redis counters → Postgres every 5 minutes.
     *
     * <p>Iterates all active tenants and upserts their Redis evaluation
     * count and subject count into the durable {@code rate_limit_counter}
     * table.
     */
    @Scheduled(every = "5m", identity = "counter-reconciliation")
    void reconcile() {
        if (!rateLimitService.isAvailable()) {
            return;
        }

        RateLimitCounterRepository repo = rateLimitService.getCounterRepository();
        if (repo == null) {
            LOG.debug(
                "CounterReconciliationJob: counter repository not available — skipping"
            );
            return;
        }

        List<TenantInfo> tenants;
        try {
            @SuppressWarnings("rawtypes")
            PlatformAdminService admin = platformAdminInstance.get();
            tenants = admin.listTenants();
        } catch (Exception e) {
            LOG.warn(
                "CounterReconciliationJob: failed to list tenants — skipping",
                e
            );
            return;
        }

        if (tenants.isEmpty()) {
            return;
        }

        String period = YearMonth.now(ZoneOffset.UTC).format(MONTH_FORMATTER);
        int synced = 0;
        int errors = 0;

        for (TenantInfo tenant : tenants) {
            try {
                String tenantId = tenant.tenantId();

                // Reconcile evaluations counter
                long evalCount = rateLimitService.peekMonthlyCount(tenantId);
                if (evalCount > 0) {
                    repo.upsert(tenantId, "evaluations", period, evalCount);
                }

                // Reconcile subjects counter (from HyperLogLog)
                long subjectCount = rateLimitService.peekSubjectCount(tenantId);
                if (subjectCount > 0) {
                    repo.upsert(tenantId, "subjects", period, subjectCount);
                }

                synced++;
            } catch (Exception e) {
                errors++;
                LOG.debug(
                    "CounterReconciliationJob: failed to reconcile tenant '{}' — continuing",
                    tenant.tenantId(),
                    e
                );
            }
        }

        if (synced > 0 || errors > 0) {
            LOG.info(
                "CounterReconciliationJob: reconciled {}/{} tenants for period {} ({} errors)",
                synced,
                tenants.size(),
                period,
                errors
            );
        }
    }
}
