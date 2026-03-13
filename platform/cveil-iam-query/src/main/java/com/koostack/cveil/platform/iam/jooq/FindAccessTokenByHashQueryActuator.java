package com.koostack.cveil.platform.iam.jooq;

import com.koostack.cveil.core.cqrs.QueryActuator;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.platform.iam.AccessTokenInfo;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Query actuator that looks up an access token by its SHA-256 hash.
 *
 * <p>This is the auth filter hot path — keep it lean.
 */
public class FindAccessTokenByHashQueryActuator
        implements QueryActuator<
                FindAccessTokenByHashQueryActuator.FindAccessTokenByHashQuery, AccessTokenInfo> {

    private static final Logger LOG =
            LoggerFactory.getLogger(FindAccessTokenByHashQueryActuator.class);

    private final DataSource platformDataSource;

    public FindAccessTokenByHashQueryActuator(DataSource platformDataSource) {
        this.platformDataSource = platformDataSource;
    }

    /**
     * @param tokenHash the SHA-256 hex digest of the raw token
     */
    public record FindAccessTokenByHashQuery(String tokenHash) {}

    @Override
    public AccessTokenInfo execute(FindAccessTokenByHashQuery query) {
        if (platformDataSource == null) return null;

        String sql =
                "SELECT access_token_id, token_prefix, name, tenant_id, roles, status,"
                        + " expires_at, created_by, last_used_at, created_at"
                        + " FROM platform_iam.access_token WHERE token_hash = ?";
        try (var conn = platformDataSource.getConnection();
                var ps = conn.prepareStatement(sql)) {
            ps.setString(1, query.tokenHash());
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new AccessTokenInfo(
                        rs.getLong("access_token_id"),
                        rs.getString("token_prefix"),
                        rs.getString("name"),
                        rs.getString("tenant_id"),
                        rs.getString("roles"),
                        rs.getString("status"),
                        rs.getTimestamp("expires_at") != null
                                ? rs.getTimestamp("expires_at").toInstant()
                                : null,
                        rs.getString("created_by"),
                        rs.getTimestamp("last_used_at") != null
                                ? rs.getTimestamp("last_used_at").toInstant()
                                : null,
                        rs.getTimestamp("created_at") != null
                                ? rs.getTimestamp("created_at").toInstant()
                                : null);
            }
        } catch (Exception e) {
            LOG.warn("Failed to look up access token by hash: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
