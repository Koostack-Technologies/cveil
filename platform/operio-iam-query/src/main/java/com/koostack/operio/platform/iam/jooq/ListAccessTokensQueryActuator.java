package com.koostack.operio.platform.iam.jooq;

import com.koostack.operio.core.cqrs.QueryActuator;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.platform.iam.AccessTokenInfo;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Query actuator that lists access tokens from the {@code platform_iam.access_token} table.
 *
 * <p>Supports filtering by {@code created_by} (nullable — if null, lists all for the tenant).
 */
public class ListAccessTokensQueryActuator
        implements QueryActuator<
                ListAccessTokensQueryActuator.ListAccessTokensQuery, List<AccessTokenInfo>> {

    private static final Logger LOG =
            LoggerFactory.getLogger(ListAccessTokensQueryActuator.class);

    private final DataSource platformDataSource;

    public ListAccessTokensQueryActuator(DataSource platformDataSource) {
        this.platformDataSource = platformDataSource;
    }

    /**
     * @param tenantId the tenant to list tokens for
     * @param createdBy the creator's user ID (nullable — if null, list all for tenant)
     */
    public record ListAccessTokensQuery(String tenantId, String createdBy) {}

    @Override
    public List<AccessTokenInfo> execute(ListAccessTokensQuery query) {
        if (platformDataSource == null) return List.of();

        boolean filterByCreator = query.createdBy() != null && !query.createdBy().isBlank();
        String sql =
                "SELECT access_token_id, token_prefix, name, tenant_id, roles, status,"
                        + " expires_at, created_by, last_used_at, created_at"
                        + " FROM platform_iam.access_token WHERE tenant_id = ?"
                        + (filterByCreator ? " AND created_by = ?" : "")
                        + " ORDER BY created_at DESC";

        List<AccessTokenInfo> results = new ArrayList<>();
        try (var conn = platformDataSource.getConnection();
                var ps = conn.prepareStatement(sql)) {
            ps.setString(1, query.tenantId());
            if (filterByCreator) {
                ps.setString(2, query.createdBy());
            }
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(
                            new AccessTokenInfo(
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
                                            : null));
                }
            }
        } catch (Exception e) {
            LOG.error(
                    "Failed to list access tokens: tenantId={}, createdBy={}",
                    query.tenantId(),
                    query.createdBy(),
                    e);
            return List.of();
        }
        return results;
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
