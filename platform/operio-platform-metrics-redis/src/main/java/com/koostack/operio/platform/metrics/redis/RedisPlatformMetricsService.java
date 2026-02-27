package com.koostack.operio.platform.metrics.redis;

import com.koostack.operio.core.metrics.PlatformMetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RedisPlatformMetricsService implements PlatformMetricsService<Void> {

    private static final Logger LOG = LoggerFactory.getLogger(RedisPlatformMetricsService.class);

    @Override
    public String getName() {
        return "redis-platform-metrics";
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
        LOG.info("RedisPlatformMetricsService configured");
    }
}
