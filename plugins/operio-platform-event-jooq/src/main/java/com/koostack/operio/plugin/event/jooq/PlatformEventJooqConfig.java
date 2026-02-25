package com.koostack.operio.plugin.event.jooq;

import com.koostack.operio.plugin.jooq.common.JooqPluginConfig;
import com.koostack.operio.plugin.redis.common.RedisPluginConfig;

/**
 * Configuration record for the {@code operio-platform-event-jooq} module.
 *
 * <p>Extends the standard {@link JooqPluginConfig} with an optional
 * {@link RedisPluginConfig} for event publish/subscribe notifications.
 * When Redis is configured, published events are also pushed to a Redis
 * Stream for real-time fanout to listeners.
 *
 * @param connectionString the JDBC URL for the event store database
 * @param dialect          the jOOQ {@link org.jooq.SQLDialect} name
 *                         (e.g. {@code "POSTGRES"}, {@code "H2"})
 * @param username         the database username, or {@code null} to use
 *                         the driver default
 * @param password         the database password, or {@code null} to use
 *                         the driver default
 * @param redis            Redis connection config for event pub/sub,
 *                         or {@code null} to disable Redis notifications
 */
public record PlatformEventJooqConfig(
    String connectionString,
    String dialect,
    String username,
    String password,
    RedisPluginConfig redis
) implements JooqPluginConfig {

    public PlatformEventJooqConfig(String connectionString, String dialect) {
        this(connectionString, dialect, null, null, null);
    }

    public PlatformEventJooqConfig(
        String connectionString,
        String dialect,
        RedisPluginConfig redis
    ) {
        this(connectionString, dialect, null, null, redis);
    }
}
