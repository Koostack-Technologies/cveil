package com.koostack.operio.core;

/** Constants for platform-scoped operations. */
public final class PlatformConstants {

    private PlatformConstants() {}

    /**
     * The tenant ID used for platform-scoped events and aggregates.
     *
     * <p>Platform-level domain events (tenant lifecycle, billing, security) are published with this
     * value as the {@code tenantId} on the {@link com.koostack.operio.core.models.AggregateKey},
     * routing them to the platform event store rather than a per-tenant store.
     */
    public static final String PLATFORM_TENANT_ID = "platform";
}
