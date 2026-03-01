package com.koostack.operio.core.platform.core;

/**
 * Event type constants for core platform domain events.
 *
 * <p>Each constant corresponds to a mutation published to the platform event
 * store by the {@link CoreCommandService}. Projections consume these event
 * types to materialize the tenant read model.
 */
public final class CoreEventTypes {

    private CoreEventTypes() {}

    // -- Tenant lifecycle -----------------------------------------------------
    public static final String TENANT_CREATED              = "core.tenant.created";
    public static final String TENANT_DISPLAY_NAME_UPDATED = "core.tenant.display-name-updated";
    public static final String TENANT_PLAN_CHANGED         = "core.tenant.plan-changed";
    public static final String TENANT_SUSPENDED            = "core.tenant.suspended";
    public static final String TENANT_ACTIVATED            = "core.tenant.activated";
    public static final String TENANT_DELETED              = "core.tenant.deleted";
    public static final String TENANT_PROVISIONED          = "core.tenant.provisioned";
}
