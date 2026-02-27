package com.koostack.operio.platform.billing.jooq;

import com.koostack.operio.core.platform.billing.BillingQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JooqBillingQueryService implements BillingQueryService<Void> {

    private static final Logger LOG = LoggerFactory.getLogger(JooqBillingQueryService.class);

    @Override public String getName() { return "jooq-billing-query"; }
    @Override public int getPriority() { return 100; }
    @Override public Class<Void> getConfigurationType() { return Void.class; }
    @Override public void configure(Void config) { LOG.info("JooqBillingQueryService configured"); }
}
