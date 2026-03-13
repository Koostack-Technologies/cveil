package com.koostack.operio.platform.core.jooq;

import com.koostack.operio.core.platform.core.CoreProjection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JooqCoreProjection implements CoreProjection<JooqCoreProjectionConfig> {

    private static final Logger LOG = LoggerFactory.getLogger(JooqCoreProjection.class);

    private CoreProjectionHandler handler;

    @Override
    public String getName() {
        return "jooq-core-projection";
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public Class<JooqCoreProjectionConfig> getConfigurationType() {
        return JooqCoreProjectionConfig.class;
    }

    @Override
    public void configure(JooqCoreProjectionConfig config) {
        this.handler = new CoreProjectionHandler(config.dsl());
        LOG.info("JooqCoreProjection configured");
    }

    /** Returns the handler for registration with a platform stream consumer. */
    public CoreProjectionHandler getHandler() {
        return handler;
    }
}
