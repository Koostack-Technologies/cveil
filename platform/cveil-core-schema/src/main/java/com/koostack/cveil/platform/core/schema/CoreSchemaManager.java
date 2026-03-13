package com.koostack.cveil.platform.core.schema;

import com.koostack.cveil.plugin.jooq.common.SchemaManager;

/** Schema manager for the platform core module. */
public final class CoreSchemaManager extends SchemaManager {

    public static final CoreSchemaManager INSTANCE = new CoreSchemaManager();

    public CoreSchemaManager() {
        super("platform-core");
    }
}
