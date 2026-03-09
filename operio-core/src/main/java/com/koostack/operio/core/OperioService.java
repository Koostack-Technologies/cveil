package com.koostack.operio.core;

/**
 * Base interface for Operio application services.
 *
 * <p>{@link Operio} is the abstract base class that implements this interface, providing the
 * builder and plugin-wiring infrastructure for the consent management platform.
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
