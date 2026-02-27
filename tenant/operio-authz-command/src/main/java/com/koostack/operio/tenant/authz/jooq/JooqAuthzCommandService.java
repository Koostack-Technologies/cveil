package com.koostack.operio.tenant.authz.jooq;

import com.koostack.operio.core.tenant.authz.AuthzCommandService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JooqAuthzCommandService implements AuthzCommandService<Void> {

    private static final Logger LOG = LoggerFactory.getLogger(JooqAuthzCommandService.class);

    @Override public String getName() { return "jooq-authz-command"; }
    @Override public int getPriority() { return 100; }
    @Override public Class<Void> getConfigurationType() { return Void.class; }
    @Override public void configure(Void config) { LOG.info("JooqAuthzCommandService configured"); }
}
