package com.koostack.operio.core.spi;

import com.koostack.operio.core.Operio;

/**
 * A pre-composed set of plugins that can be applied to an {@link Operio.Builder}
 * in a single call.
 *
 * <p>Bundles encapsulate plugin selection, configuration, and compatibility
 * constraints. They are the recommended unit of distribution for coherent
 * plugin stacks (e.g. "core consent", "embedded consent", "cloud consent").
 *
 * <h2>Composition</h2>
 * <p>Multiple bundles can be applied to the same builder. Each bundle sets
 * its relevant plugins; if two bundles set the same plugin slot, last-write
 * wins — consistent with calling individual {@code withXxx} methods.
 *
 * <h2>Override</h2>
 * <p>Individual {@code withXxx} methods on the builder can override any
 * plugin set by a bundle. Call the bundle first, then override specific
 * slots as needed.
 *
 * <p>Example:
 * <pre>{@code
 * Operio.builder()
 *     .withBundle(new CoreConsentBundle(jooqConfig, redisConfig))
 *     .withAuditProvider("custom", customAuditConfig)  // override
 *     .build();
 * }</pre>
 */
public interface PluginBundle {
    /**
     * Applies this bundle's plugins to the given builder.
     *
     * <p>Implementations wire one or more plugins via the builder's
     * {@code withXxx} methods, potentially sharing resources (connection
     * pools, HTTP clients, credentials) across them via shared config
     * objects.
     *
     * @param builder the Operio builder to configure
     */
    void apply(Operio.Builder builder);

    /**
     * Human-readable name for logging and diagnostics.
     *
     * @return the bundle name (e.g. "core", "embedded", "cloud")
     */
    String name();
}
