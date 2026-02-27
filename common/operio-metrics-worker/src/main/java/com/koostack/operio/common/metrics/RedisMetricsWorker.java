package com.koostack.operio.common.metrics;

import com.koostack.operio.core.metrics.MetricsWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RedisMetricsWorker implements MetricsWorker<Void> {

    private static final Logger LOG = LoggerFactory.getLogger(RedisMetricsWorker.class);

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
