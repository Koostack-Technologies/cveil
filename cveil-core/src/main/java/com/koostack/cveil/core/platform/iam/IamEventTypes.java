package com.koostack.cveil.core.platform.iam;

/**
 * String constants for platform IAM domain event types published to the platform event store.
 *
 * <p>These events drive projection handlers for access token lifecycle and Keycloak Admin API calls
 * (user creation, role assignment, organization management, etc.).
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

    // -- Role management ------------------------------------------------------
    public static final String ROLE_ASSIGNED = "iam.role.assigned";
    public static final String ROLE_REMOVED = "iam.role.removed";

    // -- Role definition management -------------------------------------------
    public static final String ROLE_DEFINITION_CREATED = "iam.role-definition.created";
    public static final String ROLE_DEFINITION_DELETED = "iam.role-definition.deleted";

    // -- User deletion (compensation) -----------------------------------------
    public static final String USER_DELETED = "iam.user.deleted";

    // -- Organization lifecycle -----------------------------------------------
    public static final String ORGANIZATION_CREATED = "iam.organization.created";
    public static final String ORGANIZATION_MEMBER_ADDED = "iam.organization.member-added";
    public static final String ORGANIZATION_MEMBER_REMOVED = "iam.organization.member-removed";
    public static final String ORGANIZATION_MEMBER_INVITED = "iam.organization.member-invited";
    public static final String ORGANIZATION_UPDATED = "iam.organization.updated";
    public static final String ORGANIZATION_DELETED = "iam.organization.deleted";

    // -- Infrastructure provisioning ------------------------------------------
    public static final String REDIS_PROVISIONED = "iam.redis.provisioned";

    // -- Access token lifecycle -----------------------------------------------
    public static final String ACCESS_TOKEN_CREATED = "iam.access-token.created";
    public static final String ACCESS_TOKEN_REVOKED = "iam.access-token.revoked";

    // -- User profile ---------------------------------------------------------
    public static final String USER_PROFILE_UPDATED = "iam.user.profile-updated";
    public static final String USER_APP_PROFILE_UPDATED = "iam.user.app-profile-updated";
    public static final String PASSWORD_CHANGED = "iam.user.password-changed";

    // -- Email verification ---------------------------------------------------
    public static final String EMAIL_VERIFICATION_SENT = "iam.user.email-verification-sent";
    public static final String EMAIL_VERIFIED = "iam.user.email-verified";
}
