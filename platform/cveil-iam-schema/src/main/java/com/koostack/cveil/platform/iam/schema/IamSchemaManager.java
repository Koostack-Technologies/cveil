package com.koostack.cveil.platform.iam.schema;

import com.koostack.cveil.plugin.jooq.common.SchemaManager;

/** Schema manager for the platform IAM module. */
public final class IamSchemaManager extends SchemaManager {

    public static final IamSchemaManager INSTANCE = new IamSchemaManager();

    public IamSchemaManager() {
        super("platform-iam");
    }
}
