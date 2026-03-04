package com.koostack.operio.tenant.consent.projection.decision;

import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.core.tenant.consent.invalidation.CacheInvalidationMessage;
import com.koostack.operio.core.tenant.consent.invalidation.InvalidationAction;
import com.koostack.operio.core.tenant.consent.invalidation.InvalidationPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Background scheduled task that scans for subject→policy consent edges
 * whose {@code expiresAt} timestamp has passed, marks them as
 * {@code action=EXPIRE}, and publishes invalidation signals.
 *
 * <p>Follows the same lifecycle pattern as {@code OutboxPoller}:
 * call {@link #start()} to begin periodic reaping, {@link #stop()} to halt.
 */
public class ExpiredConsentReaper {

    private static final Logger log = LoggerFactory.getLogger(ExpiredConsentReaper.class);

    private static final long DEFAULT_INTERVAL_SECONDS = 60;
    private static final int DEFAULT_SCAN_BATCH_SIZE = 200;

    private final ConsentGraphRedisRepository graph;
    private final InvalidationPublisher invalidationPublisher;
    private final Supplier<List<String>> tenantDiscovery;
    private final long intervalSeconds;
    private final int scanBatchSize;

    private ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public ExpiredConsentReaper(ConsentGraphRedisRepository graph,
                                InvalidationPublisher invalidationPublisher,
                                Supplier<List<String>> tenantDiscovery) {
        this(graph, invalidationPublisher, tenantDiscovery,
                DEFAULT_INTERVAL_SECONDS, DEFAULT_SCAN_BATCH_SIZE);
    }

    public ExpiredConsentReaper(ConsentGraphRedisRepository graph,
                                InvalidationPublisher invalidationPublisher,
                                Supplier<List<String>> tenantDiscovery,
                                long intervalSeconds,
                                int scanBatchSize) {
        this.graph = graph;
        this.invalidationPublisher = invalidationPublisher;
        this.tenantDiscovery = tenantDiscovery;
        this.intervalSeconds = intervalSeconds;
        this.scanBatchSize = scanBatchSize;
    }

    /**
     * Starts the periodic reaper. Idempotent — calling again while running is a no-op.
     */
    public void start() {
        if (!running.compareAndSet(false, true)) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "expired-consent-reaper");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::reapAll, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        log.info("ExpiredConsentReaper started — interval={}s, batchSize={}", intervalSeconds, scanBatchSize);
    }

    /**
     * Stops the periodic reaper. Blocks up to 5 seconds for graceful shutdown.
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("ExpiredConsentReaper stopped");
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * Runs a single reap cycle across all tenants. Can also be called
     * manually for on-demand cleanup.
     */
    public void reapAll() {
        try {
            List<String> tenants = tenantDiscovery.get();
            if (tenants == null || tenants.isEmpty()) return;

            Instant now = Instant.now();
            for (String tenantId : tenants) {
                try {
                    TenantContext.runWhere(tenantId, () -> reapTenant(tenantId, now));
                } catch (Exception e) {
                    log.warn("Reaper failed for tenant {} — continuing with next", tenantId, e);
                }
            }
        } catch (Exception e) {
            log.error("Reaper cycle failed", e);
        }
    }

    private void reapTenant(String tenantId, Instant cutoff) {
        List<String> expiredKeys = graph.scanExpiredConsentEdges(cutoff, scanBatchSize);
        if (expiredKeys.isEmpty()) return;

        log.debug("Reaping {} candidate expired consent edge(s) for tenant {}", expiredKeys.size(), tenantId);
        int marked = graph.markEdgesExpired(expiredKeys, cutoff);
        if (marked == 0) return;

        log.info("Reaped {} expired consent edge(s) for tenant {}", marked, tenantId);

        // Publish invalidation per subject
        for (String epKey : expiredKeys) {
            String subjectId = ConsentGraphRedisRepository.extractSubjectFromEpKey(epKey);
            if (subjectId == null) continue;
            publishInvalidation(tenantId, subjectId);
        }
    }

    private void publishInvalidation(String tenantId, String subjectId) {
        if (invalidationPublisher == null) return;
        try {
            invalidationPublisher.publish(new CacheInvalidationMessage(
                    UUID.randomUUID().toString(), tenantId, subjectId,
                    null, null, null, null, InvalidationAction.REVOKED));
            invalidationPublisher.publish(new CacheInvalidationMessage(
                    UUID.randomUUID().toString(), tenantId, subjectId,
                    null, null, null, null, InvalidationAction.CONSENT_ENTRY_CHANGED));
        } catch (Exception e) {
            log.warn("Failed to publish reaper invalidation for subject={}", subjectId, e);
        }
    }
}
