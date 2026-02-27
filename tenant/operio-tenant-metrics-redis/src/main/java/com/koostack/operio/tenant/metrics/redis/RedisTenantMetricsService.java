package com.koostack.operio.tenant.metrics.redis;

import com.koostack.operio.core.metrics.TenantMetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RedisTenantMetricsService implements TenantMetricsService<Void> {

    private static final Logger LOG = LoggerFactory.getLogger(RedisTenantMetricsService.class);

    @Override
    public String getName() {
        return "redis-tenant-metrics";
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
        LOG.info("RedisTenantMetricsService configured");
    }
}
