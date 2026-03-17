package com.koostack.cveil.rest.lifecycle;

import com.koostack.cveil.common.metrics.DefaultMetricsSweeper;
import com.koostack.cveil.core.metrics.MetricsSweeper;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scheduled job that invokes the {@link DefaultMetricsSweeper} daily to delete telemetry rows older
 * than the configured retention period and trim Redis metric streams.
 */
@ApplicationScoped
public class MetricsSweeperScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(MetricsSweeperScheduler.class);

    @Inject
    @SuppressWarnings("rawtypes")
    jakarta.enterprise.inject.Instance<MetricsSweeper> sweeperInstance;

    @Scheduled(cron = "0 0 3 * * ?", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void sweepMetrics() {
        if (!sweeperInstance.isResolvable()) {
            return;
        }
        var sweeper = sweeperInstance.get();
        if (sweeper instanceof DefaultMetricsSweeper defaultSweeper && defaultSweeper.isReady()) {
            try {
                int deleted = defaultSweeper.sweep();
                LOG.info("MetricsSweeperScheduler: swept {} rows", deleted);
            } catch (Exception e) {
                LOG.warn("MetricsSweeperScheduler: sweep failed", e);
            }
        }
    }
}
