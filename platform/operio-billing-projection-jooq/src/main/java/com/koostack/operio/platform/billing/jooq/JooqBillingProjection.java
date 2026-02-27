package com.koostack.operio.platform.billing.jooq;

import com.koostack.operio.core.platform.billing.BillingProjection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JooqBillingProjection implements BillingProjection<Void> {

    private static final Logger LOG = LoggerFactory.getLogger(JooqBillingProjection.class);

    @Override public String getName() { return "jooq-billing-projection"; }
    @Override public int getPriority() { return 100; }
    @Override public Class<Void> getConfigurationType() { return Void.class; }
    @Override public void configure(Void config) { LOG.info("JooqBillingProjection configured"); }
}
