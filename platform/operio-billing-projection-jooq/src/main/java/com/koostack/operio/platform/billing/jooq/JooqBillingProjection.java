package com.koostack.operio.platform.billing.jooq;

import com.koostack.operio.core.platform.billing.BillingProjection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JooqBillingProjection implements BillingProjection<JooqBillingProjectionConfig> {

    private static final Logger LOG = LoggerFactory.getLogger(JooqBillingProjection.class);

    private BillingProjectionHandler handler;

    @Override
    public String getName() {
        return "jooq-billing-projection";
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public Class<JooqBillingProjectionConfig> getConfigurationType() {
        return JooqBillingProjectionConfig.class;
    }

    @Override
    public void configure(JooqBillingProjectionConfig config) {
        this.handler = new BillingProjectionHandler(config.dsl());
        LOG.info("JooqBillingProjection configured");
    }

    /** Returns the handler for registration with a platform stream consumer. */
    public BillingProjectionHandler getHandler() {
        return handler;
    }
}
