package com.koostack.operio.platform.core.jooq;

import com.koostack.operio.core.platform.core.CoreQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JooqCoreQueryService implements CoreQueryService<Void> {

    private static final Logger LOG = LoggerFactory.getLogger(JooqCoreQueryService.class);

    @Override public String getName() { return "jooq-core-query"; }
    @Override public int getPriority() { return 100; }
    @Override public Class<Void> getConfigurationType() { return Void.class; }
    @Override public void configure(Void config) { LOG.info("JooqCoreQueryService configured"); }
}
