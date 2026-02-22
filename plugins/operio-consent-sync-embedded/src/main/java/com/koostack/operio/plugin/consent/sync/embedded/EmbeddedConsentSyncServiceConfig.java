package com.koostack.operio.plugin.consent.sync.embedded;

import com.koostack.operio.core.spi.PluginConfig;
import com.koostack.operio.plugin.consent.caffeine.CaffeineConsentDecisionServiceConfig;
import com.koostack.operio.plugin.consent.jooq.JooqConsentAdminServiceConfig;

/**
 * Composite configuration for {@link EmbeddedConsentSyncServiceImpl}, carrying
 * the settings needed to initialize both the relational admin layer
 * (jOOQ/Flyway) and the Caffeine in-memory decision/cache layer.
 *
 * <p>Callers construct this record with a
 * {@link JooqConsentAdminServiceConfig} (connection string + dialect for the
 * relational DB — typically H2 for embedded use) and a
 * {@link CaffeineConsentDecisionServiceConfig} (max cache size for the
 * Caffeine consent cache). The sync service uses these to configure its
 * internal {@code JooqConsentAdminService} and Caffeine repositories
 * independently.
 *
 * <h2>Auto-Registration</h2>
 * <p>When {@link #autoRegisterMetadata()} is {@code true} (the default),
 * the sync service automatically upserts any entities, fields, categories,
 * and field↔category links referenced by a consent scope before every
 * grant/revoke DB write. This is a best-effort safety net that eliminates
 * the need to run a discovery pipeline or manually register metadata before
 * writing consent.
 *
 * <p>Set to {@code false} in environments where the consent admin schema
 * must be strictly managed (e.g. metadata is provisioned by a separate
 * administrative process and unexpected auto-creation is undesirable).
 * When disabled, grant/revoke calls will throw
 * {@code IllegalArgumentException} from the admin layer if the referenced
 * metadata has not been previously registered.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Default settings (auto-registration enabled)
 * var config = new EmbeddedConsentSyncServiceConfig(
 *     new JooqConsentAdminServiceConfig(
 *         "jdbc:h2:mem:operio;DB_CLOSE_DELAY=-1", "H2"
 *     ),
 *     new CaffeineConsentDecisionServiceConfig()
 * );
 *
 * // Disable auto-registration
 * var strictConfig = new EmbeddedConsentSyncServiceConfig(
 *     new JooqConsentAdminServiceConfig(
 *         "jdbc:h2:mem:operio;DB_CLOSE_DELAY=-1", "H2"
 *     ),
 *     new CaffeineConsentDecisionServiceConfig(),
 *     false
 * );
 * }</pre>
 *
 * @param jooq                   configuration for the jOOQ consent admin layer
 * @param caffeine               configuration for the Caffeine consent decision layer
 * @param autoRegisterMetadata   whether the sync service should auto-register
 *                               entities, fields, categories, and links before
 *                               every grant/revoke write ({@code true} by default)
 */
public record EmbeddedConsentSyncServiceConfig(
    JooqConsentAdminServiceConfig jooq,
    CaffeineConsentDecisionServiceConfig caffeine,
    boolean autoRegisterMetadata
) implements PluginConfig {
    /**
     * Backward-compatible constructor — creates a config with
     * auto-registration <strong>enabled</strong> (the default).
     *
     * @param jooq     configuration for the jOOQ consent admin layer
     * @param caffeine configuration for the Caffeine consent decision layer
     */
    public EmbeddedConsentSyncServiceConfig(
        JooqConsentAdminServiceConfig jooq,
        CaffeineConsentDecisionServiceConfig caffeine
    ) {
        this(jooq, caffeine, true);
    }
}
