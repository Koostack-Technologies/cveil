package com.koostack.cveil.platform.core.jooq;

import java.util.function.UnaryOperator;
import org.jooq.DSLContext;

/**
 * Configuration for {@link JooqCoreQueryService}.
 *
 * @param dsl the platform {@link DSLContext} targeting the cveilcore schema
 * @param decryptor optional function to decrypt encrypted column values (e.g. {@code
 *     redis_acl_password}); pass {@code null} or {@link UnaryOperator#identity()} to disable
 *     decryption
 */
public record JooqCoreQueryConfig(DSLContext dsl, UnaryOperator<String> decryptor) {
    public JooqCoreQueryConfig {
        if (dsl == null) {
            throw new IllegalArgumentException("dsl must not be null");
        }
        if (decryptor == null) {
            decryptor = UnaryOperator.identity();
        }
    }

    /** Backward-compatible constructor — no decryption. */
    public JooqCoreQueryConfig(DSLContext dsl) {
        this(dsl, null);
    }
}
