package com.koostack.operio.tenant.authz.schema;

import com.koostack.operio.plugin.jooq.common.SchemaManager;

/**
 * Schema manager for the tenant authz module.
 */
public final class AuthzSchemaManager extends SchemaManager {

    public static final AuthzSchemaManager INSTANCE = new AuthzSchemaManager();

    public AuthzSchemaManager() {
        super("authz");
    }
}

