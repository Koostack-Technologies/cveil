package com.koostack.cveil.platform.billing.schema;

import com.koostack.cveil.plugin.jooq.common.SchemaManager;

/** Schema manager for the platform billing module. */
public final class BillingSchemaManager extends SchemaManager {

    public static final BillingSchemaManager INSTANCE = new BillingSchemaManager();

    public BillingSchemaManager() {
        super("platform-billing");
    }
}
