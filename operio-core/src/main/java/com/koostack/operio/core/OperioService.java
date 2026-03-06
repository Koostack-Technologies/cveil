package com.koostack.operio.core;

/**
 * Polymorphic base interface for Operio application services.
 *
 * <p>{@link TenantService} and {@link PlatformService} extend this interface to provide
 * tenant-scoped and platform-scoped business operations respectively. Both compose lower-level
 * command and query services into cohesive application-layer workflows.
 *
 * <p>{@link Operio} is the abstract base class that implements this interface, providing the
 * builder and plugin-wiring infrastructure shared by all concrete service implementations.
 */
public interface OperioService {

    /**
     * Returns the application name that namespaces all entities, fields, and Redis keys managed by
     * this instance.
     *
     * @return the application name, never {@code null}
     */
    String appName();

    /**
     * Returns {@code true} if all required plugins are configured and their backing infrastructure
     * is reachable.
     *
     * @return {@code true} if the service is ready to handle requests
     */
    boolean isReady();

    /**
     * Verifies that all configured plugins are ready.
     *
     * @throws com.koostack.operio.core.exception.OperioConfigurationException if any plugin is not
     *     ready
     */
    void verifyReady();
}
