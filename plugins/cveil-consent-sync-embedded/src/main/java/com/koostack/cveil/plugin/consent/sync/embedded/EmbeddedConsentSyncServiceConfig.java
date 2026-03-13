package com.koostack.cveil.plugin.consent.sync.embedded;

import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.spi.PluginConfig;
import com.koostack.cveil.plugin.consent.caffeine.CaffeineConsentDecisionServiceConfig;
import com.koostack.cveil.plugin.consent.jooq.JooqConsentAdminServiceConfig;

/**
 * Composite configuration for {@link EmbeddedConsentSyncServiceImpl}, carrying the settings needed
 * to initialize both the relational admin layer (jOOQ/Flyway) and the Caffeine in-memory
 * decision/cache layer.
 *
 * <p>Callers construct this record with a {@link JooqConsentAdminServiceConfig} (connection string
 * + dialect for the relational DB — typically H2 for embedded use) and a {@link
 * CaffeineConsentDecisionServiceConfig} (max cache size for the Caffeine consent cache). The sync
 * service uses these to configure its internal query and command services independently.
 *
 * <h2>Event Service</h2>
 *
 * <p>When an {@link EventService} is provided, the sync service creates a {@code
 * JooqConsentAdminCommandService} for write operations (metadata registration, grant/revoke). When
 * {@code null}, the sync service operates in cache-only mode — writes go directly to the Caffeine
 * cache without publishing domain events.
 *
 * <h2>Auto-Registration</h2>
 *
 * <p>When {@link #autoRegisterMetadata()} is {@code true} (the default), the sync service
 * automatically upserts any entities, fields, categories, and field↔category links referenced by a
 * consent scope before every grant/revoke DB write.
 *
 * @param jooq configuration for the jOOQ consent admin layer
 * @param caffeine configuration for the Caffeine consent decision layer
 * @param autoRegisterMetadata whether the sync service should auto-register entities, fields,
 *     categories, and links before every grant/revoke write ({@code true} by default)
 * @param eventService the event service for the command service, or {@code null} for cache-only
 *     mode
 */
public record EmbeddedConsentSyncServiceConfig(
        JooqConsentAdminServiceConfig jooq,
        CaffeineConsentDecisionServiceConfig caffeine,
        boolean autoRegisterMetadata,
        EventService<?> eventService)
        implements PluginConfig {
    /**
     * Backward-compatible constructor — creates a config with auto-registration
     * <strong>enabled</strong> and no event service (cache-only mode).
     *
     * @param jooq configuration for the jOOQ consent admin layer
     * @param caffeine configuration for the Caffeine consent decision layer
     */
    public EmbeddedConsentSyncServiceConfig(
            JooqConsentAdminServiceConfig jooq, CaffeineConsentDecisionServiceConfig caffeine) {
        this(jooq, caffeine, true, null);
    }

    /**
     * Backward-compatible constructor — creates a config with the specified auto-registration flag
     * and no event service (cache-only mode).
     *
     * @param jooq configuration for the jOOQ consent admin layer
     * @param caffeine configuration for the Caffeine consent decision layer
     * @param autoRegisterMetadata whether to auto-register metadata
     */
    public EmbeddedConsentSyncServiceConfig(
            JooqConsentAdminServiceConfig jooq,
            CaffeineConsentDecisionServiceConfig caffeine,
            boolean autoRegisterMetadata) {
        this(jooq, caffeine, autoRegisterMetadata, null);
    }
}
