package com.koostack.operio.platform.iam.schema;

import com.koostack.operio.plugin.jooq.common.SchemaManager;

/** Schema manager for the platform IAM module. */
public final class IamSchemaManager extends SchemaManager {

    public static final IamSchemaManager INSTANCE = new IamSchemaManager();

    public IamSchemaManager() {
        super("platform-iam");
    }
}
