package com.koostack.operio.plugin.consent.sync;

import com.koostack.operio.core.spi.PluginConfig;
import com.koostack.operio.plugin.consent.jooq.JooqConsentAdminServiceConfig;
import com.koostack.operio.plugin.consent.redis.RedisConsentDecisionServiceConfig;

/**
 * Composite configuration for {@link ConsentSyncServiceImpl}, carrying the
 * settings needed to initialize both the relational admin layer (jOOQ/Flyway)
 * and the Redis decision/cache layer.
 *
 * <p>Callers construct this record with a
 * {@link JooqConsentAdminServiceConfig} (connection string + dialect for the
 * relational DB) and a {@link RedisConsentDecisionServiceConfig} (host, port,
 * optional password for Redis). The sync service uses these to configure its
 * internal {@code JooqConsentAdminService} and Redis repositories
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
 * var config = new ConsentSyncServiceConfig(
 *     new JooqConsentAdminServiceConfig(
 *         "jdbc:postgresql://localhost/operio", "POSTGRES"
 *     ),
 *     new RedisConsentDecisionServiceConfig("localhost", 6379)
 * );
 *
 * // Disable auto-registration
 * var strictConfig = new ConsentSyncServiceConfig(
 *     new JooqConsentAdminServiceConfig(
 *         "jdbc:postgresql://localhost/operio", "POSTGRES"
 *     ),
 *     new RedisConsentDecisionServiceConfig("localhost", 6379),
 *     false
 * );
 * }</pre>
 *
 * @param jooq                   configuration for the jOOQ consent admin layer
 * @param redis                  configuration for the Redis consent decision layer
 * @param autoRegisterMetadata   whether the sync service should auto-register
 *                               entities, fields, categories, and links before
 *                               every grant/revoke write ({@code true} by default)
 */
public record ConsentSyncServiceConfig(
    JooqConsentAdminServiceConfig jooq,
    RedisConsentDecisionServiceConfig redis,
    boolean autoRegisterMetadata
) implements PluginConfig {
    /**
     * Backward-compatible constructor — creates a config with
     * auto-registration <strong>enabled</strong> (the default).
     *
     * @param jooq  configuration for the jOOQ consent admin layer
     * @param redis configuration for the Redis consent decision layer
     */
    public ConsentSyncServiceConfig(
        JooqConsentAdminServiceConfig jooq,
        RedisConsentDecisionServiceConfig redis
    ) {
        this(jooq, redis, true);
    }
}
