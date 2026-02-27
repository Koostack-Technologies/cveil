package com.koostack.operio.tenant.authz.jooq;

import com.koostack.operio.core.tenant.authz.AuthzQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JooqAuthzQueryService implements AuthzQueryService<Void> {

    private static final Logger LOG = LoggerFactory.getLogger(JooqAuthzQueryService.class);

    @Override public String getName() { return "jooq-authz-query"; }
    @Override public int getPriority() { return 100; }
    @Override public Class<Void> getConfigurationType() { return Void.class; }
    @Override public void configure(Void config) { LOG.info("JooqAuthzQueryService configured"); }
}
