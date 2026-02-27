package com.koostack.operio.platform.core.jooq;

import com.koostack.operio.core.platform.core.CoreProjection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JooqCoreProjection implements CoreProjection<Void> {

    private static final Logger LOG = LoggerFactory.getLogger(JooqCoreProjection.class);

    @Override public String getName() { return "jooq-core-projection"; }
    @Override public int getPriority() { return 100; }
    @Override public Class<Void> getConfigurationType() { return Void.class; }
    @Override public void configure(Void config) { LOG.info("JooqCoreProjection configured"); }
}
