package com.koostack.cveil.platform.billing.jooq;

import org.jooq.DSLContext;

/**
 * Configuration for {@link JooqBillingQueryService}.
 *
 * @param dsl the platform {@link DSLContext} targeting the cveilbilling schema
 */
public record JooqBillingQueryConfig(DSLContext dsl) {
    public JooqBillingQueryConfig {
        if (dsl == null) {
            throw new IllegalArgumentException("dsl must not be null");
        }
    }
}
