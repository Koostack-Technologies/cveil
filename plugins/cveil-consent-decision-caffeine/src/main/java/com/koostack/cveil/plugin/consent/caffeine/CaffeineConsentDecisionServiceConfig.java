package com.koostack.cveil.plugin.consent.caffeine;

import com.koostack.cveil.core.spi.PluginConfig;

/**
 * Configuration for the Caffeine-backed {@link CaffeineConsentDecisionService}.
 *
 * <p>Controls the maximum number of consent entries held in the in-memory
 * cache. Entries that exceed this limit are evicted according to Caffeine's
 * Window TinyLfu policy. Per-entry TTL is handled automatically based on the
 * {@code expiresAt} timestamp supplied at write time — there is no global
 * TTL setting.
 *
 * <h2>Defaults</h2>
 * <ul>
 *   <li>{@code maxSize} — {@value #DEFAULT_MAX_SIZE} entries</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Default settings (10 000 entries max)
 * var config = new CaffeineConsentDecisionServiceConfig();
 *
 * // Custom max size
 * var config = new CaffeineConsentDecisionServiceConfig(50_000);
 * }</pre>
 *
 * @param maxSize the maximum number of consent entries in the cache;
 *                must be positive
 */
public record CaffeineConsentDecisionServiceConfig(
    long maxSize
) implements PluginConfig {

    /** Default maximum number of consent entries in the cache. */
    public static final long DEFAULT_MAX_SIZE = 10_000L;

    /**
     * Creates a configuration with the given maximum cache size.
     *
     * @param maxSize the maximum number of consent entries; must be positive
     * @throws IllegalArgumentException if {@code maxSize} is not positive
     */
    public CaffeineConsentDecisionServiceConfig {
        if (maxSize <= 0) {
            throw new IllegalArgumentException(
                "maxSize must be positive, got: " + maxSize
            );
        }
    }

    /**
     * Creates a configuration with the default maximum cache size
     * ({@value #DEFAULT_MAX_SIZE}).
     */
    public CaffeineConsentDecisionServiceConfig() {
        this(DEFAULT_MAX_SIZE);
    }
}
