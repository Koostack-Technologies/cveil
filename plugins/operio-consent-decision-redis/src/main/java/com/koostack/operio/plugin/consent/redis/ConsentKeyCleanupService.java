package com.koostack.operio.plugin.consent.redis;

import com.koostack.operio.plugin.redis.common.JedisProvider;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

/**
 * One-time cleanup utility that removes orphaned {@code meta:field2cat:*} and
 * {@code meta:cat2field:*} Redis keys left behind after the Phase 1 Hash
 * schema migration.
 *
 * <p>These bidirectional mapping keys were previously maintained by
 * {@code FieldCategoryMappingRedisRepository} (now deleted). The new
 * Hash-per-subject schema no longer uses or maintains them.
 *
 * <p>Safe to run at any time after Phase 1 deployment. Keys matching the
 * patterns are SCANned and DELeted in batches.
 */
public class ConsentKeyCleanupService {

    private static final Logger LOG = LoggerFactory.getLogger(ConsentKeyCleanupService.class);

    private static final int SCAN_COUNT = 200;
    private static final int DELETE_BATCH_SIZE = 100;

    private final JedisProvider jedisProvider;

    public ConsentKeyCleanupService(JedisProvider jedisProvider) {
        this.jedisProvider = jedisProvider;
    }

    /**
     * Cleans up orphaned metadata keys for all provided tenants.
     *
     * @param tenantIds  list of tenant identifiers
     * @param appName    the appName prefix used in old keys (e.g., "operio")
     * @return total number of keys deleted
     */
    public int cleanupAll(List<String> tenantIds, String appName) {
        int total = 0;
        for (String tenantId : tenantIds) {
            try {
                int count = cleanup(tenantId, appName);
                total += count;
                if (count > 0) {
                    LOG.info("Cleaned up {} orphaned metadata keys for tenant '{}'", count, tenantId);
                }
            } catch (Exception e) {
                LOG.error("Cleanup failed for tenant '{}': {}", tenantId, e.getMessage(), e);
            }
        }
        LOG.info("Cleanup complete. Total orphaned keys deleted: {}", total);
        return total;
    }

    /**
     * Cleans up orphaned {@code meta:field2cat:*} and {@code meta:cat2field:*}
     * keys for a single tenant.
     *
     * <p>Scans for keys matching:
     * <ul>
     *   <li>{@code {appName}:{tenantId}:meta:field2cat:*}</li>
     *   <li>{@code {appName}:{tenantId}:meta:cat2field:*}</li>
     *   <li>{@code {appName}:meta:field2cat:*} (single-tenant legacy)</li>
     *   <li>{@code {appName}:meta:cat2field:*} (single-tenant legacy)</li>
     * </ul>
     *
     * @param tenantId the tenant whose orphaned keys to clean
     * @param appName  the appName prefix
     * @return number of keys deleted
     */
    public int cleanup(String tenantId, String appName) {
        String effectiveAppName = appName != null ? appName : "operio";

        List<String> patterns = List.of(
            effectiveAppName + ":" + tenantId + ":meta:field2cat:*",
            effectiveAppName + ":" + tenantId + ":meta:cat2field:*"
        );

        int deleted = 0;
        for (String pattern : patterns) {
            deleted += scanAndDelete(pattern);
        }
        return deleted;
    }

    /**
     * Cleans up legacy single-tenant metadata keys (no tenantId segment).
     *
     * @param appName the appName prefix
     * @return number of keys deleted
     */
    public int cleanupLegacy(String appName) {
        String effectiveAppName = appName != null ? appName : "operio";

        List<String> patterns = List.of(
            effectiveAppName + ":meta:field2cat:*",
            effectiveAppName + ":meta:cat2field:*"
        );

        int deleted = 0;
        for (String pattern : patterns) {
            deleted += scanAndDelete(pattern);
        }

        if (deleted > 0) {
            LOG.info("Cleaned up {} legacy single-tenant metadata keys", deleted);
        }
        return deleted;
    }

    // -----------------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------------

    private int scanAndDelete(String pattern) {
        JedisPooled jedis = jedisProvider.get();
        List<String> batch = new ArrayList<>(DELETE_BATCH_SIZE);
        int deleted = 0;

        String cursor = "0";
        ScanParams params = new ScanParams().match(pattern).count(SCAN_COUNT);

        do {
            ScanResult<String> scanResult = jedis.scan(cursor, params);
            batch.addAll(scanResult.getResult());
            cursor = scanResult.getCursor();

            if (batch.size() >= DELETE_BATCH_SIZE || "0".equals(cursor)) {
                if (!batch.isEmpty()) {
                    long count = jedis.del(batch.toArray(new String[0]));
                    deleted += (int) count;
                    batch.clear();
                }
            }
        } while (!"0".equals(cursor));

        return deleted;
    }
}
