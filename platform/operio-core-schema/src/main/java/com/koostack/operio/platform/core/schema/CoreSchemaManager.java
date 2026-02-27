package com.koostack.operio.platform.core.schema;

import com.koostack.operio.plugin.jooq.common.SchemaManager;

/**
 * Schema manager for the platform core module.
 */
public final class CoreSchemaManager extends SchemaManager {

    public static final CoreSchemaManager INSTANCE = new CoreSchemaManager();

    public CoreSchemaManager() {
        super("platform-core");
    }
}

