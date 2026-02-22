package com.koostack.operio.core.service;

import com.koostack.operio.core.Models.DiscoveryResult;
import com.koostack.operio.core.spi.Plugin;

/**
 * SPI for plugins that scan a codebase (classpath, source files, external
 * metadata, etc.) to discover sensitive fields and their categories.
 *
 * <p>Implementations define <em>how</em> to look — the annotation-based
 * implementation introspects {@code @Sensitive} annotations via reflection,
 * but other implementations could parse OpenAPI specs, scan database schemas,
 * read external configuration files, or query a remote metadata service.
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>The plugin is resolved and configured via the standard
 *       {@link com.koostack.operio.core.registry.PluginRegistry} mechanism.</li>
 *   <li>The caller invokes {@link #discover()} to perform the scan.</li>
 *   <li>The returned {@link DiscoveryResult} is fed into
 *       {@link ConsentAdminService} registration methods (e.g.
 *       {@link ConsentAdminService#registerCategory},
 *       {@link ConsentAdminService#registerField},
 *       {@link ConsentAdminService#linkFieldToCategory}) to populate the
 *       consent admin schema.</li>
 * </ol>
 *
 * <p>{@link com.koostack.operio.core.Operio#syncDiscoveredFields()} orchestrates
 * steps 2 and 3 automatically when both a {@code SensitiveDiscovery} and a
 * {@code ConsentAdminService} are wired into the Operio instance.
 *
 * <h2>Idempotency</h2>
 * <p>{@link #discover()} is expected to be safe to call multiple times — it
 * performs a read-only scan and returns a snapshot. The downstream registration
 * methods use upsert semantics, so re-running the full pipeline is idempotent.
 *
 * @param <T> the plugin configuration type
 */
public interface SensitiveDiscovery<T> extends Plugin<T> {

    /**
     * Scans the configured source (classpath classes, files, remote service,
     * etc.) and returns all discovered sensitive fields and categories.
     *
     * <p>The result is a point-in-time snapshot. Implementations should not
     * cache results across calls — each invocation performs a fresh scan so
     * that newly added annotations or metadata are picked up.
     *
     * @return the discovery result containing all found fields and categories,
     *         never {@code null} (return {@link DiscoveryResult#empty()} if
     *         nothing was found)
     */
    DiscoveryResult discover();
}
