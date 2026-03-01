package com.koostack.operio.core.platform.iam;

/**
 * String constants for IAM domain event types published to the platform
 * event store.
 */
public final class IamEventTypes {

    private IamEventTypes() {}

    public static final String API_KEY_GENERATED = "iam.api-key.generated";
    public static final String API_KEY_REVOKED = "iam.api-key.revoked";
    public static final String IDP_CONFIG_UPDATED = "iam.idp-config.updated";
}
