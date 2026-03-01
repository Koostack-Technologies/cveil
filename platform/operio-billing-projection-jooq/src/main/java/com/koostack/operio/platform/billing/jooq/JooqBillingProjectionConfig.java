package com.koostack.operio.platform.billing.jooq;

import org.jooq.DSLContext;

/**
 * Configuration for {@link JooqBillingProjection} and {@link BillingProjectionHandler}.
 *
 * @param dsl jOOQ context pointing at the platform database
 */
public record JooqBillingProjectionConfig(
    DSLContext dsl
) {
    public JooqBillingProjectionConfig {
        if (dsl == null) {
            throw new IllegalArgumentException("dsl must not be null");
        }
    }
}
