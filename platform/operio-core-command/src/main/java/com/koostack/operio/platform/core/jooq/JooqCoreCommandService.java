package com.koostack.operio.platform.core.jooq;

import com.koostack.operio.core.platform.core.CoreCommandService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JooqCoreCommandService implements CoreCommandService<Void> {

    private static final Logger LOG = LoggerFactory.getLogger(JooqCoreCommandService.class);

    @Override public String getName() { return "jooq-core-command"; }
    @Override public int getPriority() { return 100; }
    @Override public Class<Void> getConfigurationType() { return Void.class; }
    @Override public void configure(Void config) { LOG.info("JooqCoreCommandService configured"); }
}
