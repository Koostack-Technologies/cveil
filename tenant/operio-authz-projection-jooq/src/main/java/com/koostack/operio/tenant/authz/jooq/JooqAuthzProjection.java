package com.koostack.operio.tenant.authz.jooq;

import com.koostack.operio.core.tenant.authz.AuthzProjection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JooqAuthzProjection implements AuthzProjection<Void> {

    private static final Logger LOG = LoggerFactory.getLogger(JooqAuthzProjection.class);

    @Override public String getName() { return "jooq-authz-projection"; }
    @Override public int getPriority() { return 100; }
    @Override public Class<Void> getConfigurationType() { return Void.class; }
    @Override public void configure(Void config) { LOG.info("JooqAuthzProjection configured"); }
}
