package com.koostack.operio.core.tenant.iam;

/**
 * String constants for tenant-scoped IAM domain event types published to the tenant event store.
 *
 * <p>Mirrors {@link com.koostack.operio.core.platform.iam.IamEventTypes} but prefixed with {@code
 * tenant-iam.} and published to the per-tenant event stream ({@code t_{tenantId}_event}).
 */
public final class TenantIamEventTypes {

    private TenantIamEventTypes() {}

    // -- User lifecycle -------------------------------------------------------
    public static final String USER_REGISTERED = "tenant-iam.user.registered";
    public static final String USER_SUSPENDED = "tenant-iam.user.suspended";
    public static final String USER_ACTIVATED = "tenant-iam.user.activated";

    // -- Service account lifecycle --------------------------------------------
    public static final String SERVICE_ACCOUNT_CREATED = "tenant-iam.service-account.created";
    public static final String SERVICE_ACCOUNT_REVOKED = "tenant-iam.service-account.revoked";

    // -- Role management ------------------------------------------------------
    public static final String ROLE_ASSIGNED = "tenant-iam.role.assigned";
    public static final String ROLE_REMOVED = "tenant-iam.role.removed";
}
