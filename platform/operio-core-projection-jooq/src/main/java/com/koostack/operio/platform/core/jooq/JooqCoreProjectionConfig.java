package com.koostack.operio.platform.core.jooq;

import org.jooq.DSLContext;

/**
 * Configuration for {@link JooqCoreProjection} and {@link CoreProjectionHandler}.
 *
 * @param dsl jOOQ context pointing at the platform database
 */
public record JooqCoreProjectionConfig(DSLContext dsl) {
    public JooqCoreProjectionConfig {
        if (dsl == null) {
            throw new IllegalArgumentException("dsl must not be null");
        }
    }
}
