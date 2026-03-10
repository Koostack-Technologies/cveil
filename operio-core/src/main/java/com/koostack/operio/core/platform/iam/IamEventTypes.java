package com.koostack.operio.core.platform.iam;

/**
 * String constants for platform IAM domain event types published to the platform event store.
 *
 * <p>These events drive the Keycloak projection handler which translates them into Keycloak Admin
 * API calls (user creation, role assignment, service account provisioning, etc.).
 */
public final class IamEventTypes {

    private IamEventTypes() {}

    // -- User lifecycle -------------------------------------------------------
    public static final String USER_REGISTERED = "iam.user.registered";
    public static final String USER_SUSPENDED = "iam.user.suspended";
    public static final String USER_ACTIVATED = "iam.user.activated";

    // -- Authentication lifecycle ---------------------------------------------
    public static final String USER_AUTHENTICATED = "iam.user.authenticated";
    public static final String TOKEN_REFRESHED = "iam.token.refreshed";
    public static final String USER_LOGGED_OUT = "iam.user.logged-out";

    // -- Service account lifecycle --------------------------------------------
    public static final String SERVICE_ACCOUNT_CREATED = "iam.service-account.created";
    public static final String SERVICE_ACCOUNT_REVOKED = "iam.service-account.revoked";

    // -- Role management ------------------------------------------------------
    public static final String ROLE_ASSIGNED = "iam.role.assigned";
    public static final String ROLE_REMOVED = "iam.role.removed";

    // -- Organization lifecycle -----------------------------------------------
    public static final String ORGANIZATION_CREATED = "iam.organization.created";
    public static final String ORGANIZATION_MEMBER_ADDED = "iam.organization.member-added";
    public static final String ORGANIZATION_MEMBER_REMOVED = "iam.organization.member-removed";
    public static final String ORGANIZATION_DELETED = "iam.organization.deleted";

    // -- Infrastructure provisioning ------------------------------------------
    public static final String REDIS_PROVISIONED = "iam.redis.provisioned";

    // -- Legacy (kept for backward compatibility with existing event streams) --
    /**
     * @deprecated Use {@link #SERVICE_ACCOUNT_CREATED} for new code.
     */
    @Deprecated(forRemoval = true)
    public static final String API_KEY_GENERATED = "iam.api-key.generated";

    /**
     * @deprecated Use {@link #SERVICE_ACCOUNT_REVOKED} for new code.
     */
    @Deprecated(forRemoval = true)
    public static final String API_KEY_REVOKED = "iam.api-key.revoked";
}
