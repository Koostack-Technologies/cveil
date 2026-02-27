package com.koostack.operio.common.metrics;

import com.koostack.operio.core.metrics.MetricsSweeper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RedisMetricsSweeper implements MetricsSweeper<Void> {

    private static final Logger LOG = LoggerFactory.getLogger(RedisMetricsSweeper.class);

    @Override
    public String getName() {
        return "redis-metrics-worker";
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public Class<Void> getConfigurationType() {
        return Void.class;
    }

    @Override
    public void configure(Void config) {
        LOG.info("RedisMetricsWorker configured");
    }
}
