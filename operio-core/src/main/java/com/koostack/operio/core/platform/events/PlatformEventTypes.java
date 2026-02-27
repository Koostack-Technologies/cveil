package com.koostack.operio.core.platform.events;

/**
 * Event type constants for platform domain events.
 */
public final class PlatformEventTypes {

    private PlatformEventTypes() {}

    // -- Tenant lifecycle -----------------------------------------------------
    public static final String TENANT_REGISTERED = "platform.tenant.registered";
    public static final String TENANT_SUSPENDED  = "platform.tenant.suspended";
    public static final String TENANT_DELETED    = "platform.tenant.deleted";

    // -- Security / API keys --------------------------------------------------
    public static final String API_KEY_GENERATED  = "platform.security.api-key-generated";
    public static final String API_KEY_REVOKED    = "platform.security.api-key-revoked";
    public static final String IDP_CONFIG_UPDATED = "platform.security.idp-config-updated";

    // -- Billing / Usage ------------------------------------------------------
    public static final String BILLING_PLAN_CHANGED   = "platform.billing.plan-changed";
    public static final String USAGE_LIMIT_ADJUSTED   = "platform.usage.limit-adjusted";
}
