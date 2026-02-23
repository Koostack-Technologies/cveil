package com.koostack.operio.plugin.platform.jooq;

import com.koostack.operio.plugin.jooq.common.JooqPluginConfig;
import com.koostack.operio.plugin.redis.common.RedisPluginConfig;

/**
 * Configuration record for the {@code operio-platform-jooq} module.
 *
 * <p>Extends the standard {@link JooqPluginConfig} (JDBC URL + dialect) with
 * an optional {@link RedisPluginConfig} needed by {@link TenantManager} to
 * provision per-tenant Redis ACL users during
 * {@link JooqPlatformAdminService#createTenant}.
 *
 * <p>When {@code redis} is {@code null}, tenant provisioning skips the Redis
 * ACL step — useful for embedded / test deployments that don't use Redis.
 *
 * <h2>Example usage</h2>
 * <pre>{@code
 * // Production (Postgres + Redis):
 * PlatformJooqConfig config = new PlatformJooqConfig(
 *     "jdbc:postgresql://localhost:5432/operio_platform",
 *     "POSTGRES",
 *     new MyRedisConfig("localhost", 6379, "secret")
 * );
 *
 * // Embedded / test (H2, no Redis):
 * PlatformJooqConfig config = new PlatformJooqConfig(
 *     "jdbc:h2:mem:operio_platform_test;DB_CLOSE_DELAY=-1",
 *     "H2",
 *     null
 * );
 * }</pre>
 *
 * @param connectionString the JDBC URL for the platform database
 * @param dialect          the jOOQ {@link org.jooq.SQLDialect} name
 *                         (e.g. {@code "POSTGRES"}, {@code "H2"})
 * @param redis            Redis connection config for {@link TenantManager}
 *                         ACL provisioning, or {@code null} to skip Redis
 */
public record PlatformJooqConfig(
    String connectionString,
    String dialect,
    RedisPluginConfig redis
) implements JooqPluginConfig {}
