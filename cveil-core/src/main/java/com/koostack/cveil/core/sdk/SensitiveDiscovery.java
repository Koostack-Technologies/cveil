package com.koostack.cveil.core.sdk;

import com.koostack.cveil.core.models.DiscoveryResult;
import com.koostack.cveil.core.spi.Plugin;

/**
 * SPI for plugins that scan a codebase (classpath, source files, external metadata, etc.) to
 * discover sensitive fields and their categories.
 *
 * <p>Implementations define <em>how</em> to look — the annotation-based implementation introspects
 * {@code @Sensitive} annotations via reflection, but other implementations could parse OpenAPI
 * specs, scan database schemas, read external configuration files, or query a remote metadata
 * service.
 *
 * <h2>Lifecycle</h2>
 *
 * <ol>
 *   <li>The plugin is resolved and configured via the standard {@link
 *       com.koostack.cveil.core.registry.PluginRegistry} mechanism.
 *   <li>The caller invokes {@link #discover()} to perform the scan.
 *   <li>The returned {@link DiscoveryResult} is fed into {@link
 *       com.koostack.cveil.core.TenantService} registration methods (e.g. {@code registerField},
 *       {@code assignFieldCategory}) to populate the consent admin schema.
 * </ol>
 *
 * <p>{@link com.koostack.cveil.core.Cveil#syncDiscoveredFields()} orchestrates steps 2 and 3
 * automatically when both a {@code SensitiveDiscovery} and a {@code TenantService} are wired into
 * the Cveil instance.
 *
 * <h2>Idempotency</h2>
 *
 * <p>{@link #discover()} is expected to be safe to call multiple times — it performs a read-only
 * scan and returns a snapshot. The downstream registration methods use upsert semantics, so
 * re-running the full pipeline is idempotent.
 *
 * @param <T> the plugin configuration type
 */
public interface SensitiveDiscovery<T> extends Plugin<T> {

    /**
     * Scans the configured source (classpath classes, files, remote service, etc.) and returns all
     * discovered sensitive fields and categories.
     *
     * <p>The result is a point-in-time snapshot. Implementations should not cache results across
     * calls — each invocation performs a fresh scan so that newly added annotations or metadata are
     * picked up.
     *
     * @return the discovery result containing all found fields and categories, never {@code null}
     *     (return {@link DiscoveryResult#empty()} if nothing was found)
     */
    DiscoveryResult discover();
}
