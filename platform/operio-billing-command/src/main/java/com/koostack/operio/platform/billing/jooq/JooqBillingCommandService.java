package com.koostack.operio.platform.billing.jooq;

import com.koostack.operio.core.platform.billing.BillingCommandService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JooqBillingCommandService implements BillingCommandService<Void> {

    private static final Logger LOG = LoggerFactory.getLogger(JooqBillingCommandService.class);

    @Override public String getName() { return "jooq-billing-command"; }
    @Override public int getPriority() { return 100; }
    @Override public Class<Void> getConfigurationType() { return Void.class; }
    @Override public void configure(Void config) { LOG.info("JooqBillingCommandService configured"); }
}
