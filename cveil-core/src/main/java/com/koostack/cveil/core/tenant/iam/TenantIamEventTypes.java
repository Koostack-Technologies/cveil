package com.koostack.cveil.core.tenant.iam;

/**
 * String constants for tenant-scoped IAM domain event types published to the tenant event store.
 *
 * @deprecated Use {@link com.koostack.cveil.core.platform.iam.IamEventTypes} with the unified
 *     event store. All IAM events are now published with the {@code iam.*} prefix to the single
 *     event store with {@code tenant_id} for scoping. Will be removed in the module cleanup phase.
 */
@Deprecated(forRemoval = true)
public final class TenantIamEventTypes {

    private TenantIamEventTypes() {}

    // -- User lifecycle -------------------------------------------------------
    public static final String USER_REGISTERED = "tenant-iam.user.registered";
    public static final String USER_SUSPENDED = "tenant-iam.user.suspended";
    public static final String USER_ACTIVATED = "tenant-iam.user.activated";

    // -- Authentication lifecycle ---------------------------------------------
    public static final String USER_AUTHENTICATED = "tenant-iam.user.authenticated";
    public static final String TOKEN_REFRESHED = "tenant-iam.token.refreshed";
    public static final String USER_LOGGED_OUT = "tenant-iam.user.logged-out";

    // -- Service account lifecycle --------------------------------------------
    public static final String SERVICE_ACCOUNT_CREATED = "tenant-iam.service-account.created";
    public static final String SERVICE_ACCOUNT_REVOKED = "tenant-iam.service-account.revoked";

    // -- Role management ------------------------------------------------------
    public static final String ROLE_ASSIGNED = "tenant-iam.role.assigned";
    public static final String ROLE_REMOVED = "tenant-iam.role.removed";
}
