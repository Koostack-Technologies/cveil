package com.koostack.operio.tenant.consent.schema;

import com.koostack.operio.plugin.jooq.common.SchemaManager;

/** Schema manager for the consent admin module. */
public final class ConsentSchemaManager extends SchemaManager {

    public static final ConsentSchemaManager INSTANCE = new ConsentSchemaManager();

    public ConsentSchemaManager() {
        super("consent");
    }
}
