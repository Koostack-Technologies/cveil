package com.koostack.operio.core.spi;

/**
 * Plugin<T> extends Configurable<T>, NamedPlugin, Prioritized.
 *
 * <p>All service interfaces (ConsentAdminService, ConsentDecisionService,
 * ConsentSyncService, IdentityResolver, AuditLogger, MaskingStrategy,
 * SensitiveFieldResolver) extend this interface, making every plugin
 * configurable, named, prioritized, and health-checkable.
 */
public interface Plugin<T> extends Configurable<T>, NamedPlugin, Prioritized {
    /**
     * Returns {@code true} if this plugin is configured and its backing
     * infrastructure is reachable.
     *
     * <p>The default implementation returns {@code true}, which is the
     * optimistic local-resource assumption (local DB/Redis is usually
     * available). Managed/remote plugins should override this to perform
     * a lightweight connectivity check (e.g. HTTP ping, Redis PING).
     *
     * <p>This method should be cheap and safe to call repeatedly. It must
     * not throw — return {@code false} on any error.
     *
     * @return {@code true} if the plugin is ready to serve requests
     */
    default boolean isReady() {
        return true;
    }
}
