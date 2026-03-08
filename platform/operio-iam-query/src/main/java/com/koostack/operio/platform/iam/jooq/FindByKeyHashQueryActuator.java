package com.koostack.operio.platform.iam.jooq;

import com.koostack.operio.core.cqrs.QueryActuator;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.platform.iam.IamQueryService.ApiKeyInfo;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Query actuator that looks up a legacy API key by its SHA-256 hash.
 *
 * @deprecated Use service account queries for new code.
 */
@Deprecated(forRemoval = true)
public class FindByKeyHashQueryActuator
        implements QueryActuator<FindByKeyHashQueryActuator.FindByKeyHashQuery, ApiKeyInfo> {

    private static final Logger LOG = LoggerFactory.getLogger(FindByKeyHashQueryActuator.class);

    private final DataSource platformDataSource;

    public FindByKeyHashQueryActuator(DataSource platformDataSource) {
        this.platformDataSource = platformDataSource;
    }

    /**
     * Query input for finding an API key by hash.
     *
     * @param keyHash the SHA-256 hash of the raw API key
     */
    public record FindByKeyHashQuery(String keyHash) {}

    @Override
    public ApiKeyInfo execute(FindByKeyHashQuery query) {
        if (platformDataSource == null) return null;
        String sql =
                "SELECT key_prefix, name, tenant_id, subject, roles, status, created_at,"
                        + " expires_at FROM platform_iam.api_key WHERE key_hash = ?";
        try (var conn = platformDataSource.getConnection();
                var ps = conn.prepareStatement(sql)) {
            ps.setString(1, query.keyHash());
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new ApiKeyInfo(
                        rs.getString("key_prefix"),
                        rs.getString("key_prefix"),
                        rs.getString("name"),
                        rs.getString("tenant_id"),
                        null,
                        rs.getString("subject"),
                        rs.getString("roles"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at") != null
                                ? rs.getTimestamp("created_at").toInstant()
                                : null,
                        rs.getTimestamp("expires_at") != null
                                ? rs.getTimestamp("expires_at").toInstant()
                                : null);
            }
        } catch (Exception e) {
            LOG.warn("Failed to look up API key by hash: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}

