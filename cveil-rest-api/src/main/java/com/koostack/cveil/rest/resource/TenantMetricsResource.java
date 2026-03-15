package com.koostack.cveil.rest.resource;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.koostack.cveil.rest.auth.Permissions;
import com.koostack.cveil.rest.config.CveilOpenApiConfig;
import com.koostack.cveil.rest.context.CveilRequestHolder;
import com.koostack.cveil.rest.dto.CommonDtos.ErrorResponse;
import com.koostack.cveil.rest.dto.MetricsDtos.MetricRow;
import com.koostack.cveil.rest.dto.MetricsDtos.MetricSummary;
import com.koostack.cveil.rest.dto.MetricsDtos.MetricsResponse;
import com.koostack.cveil.rest.dto.MetricsDtos.MetricsSummaryResponse;
import com.koostack.cveil.rest.interceptor.TenantScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tenant-scoped metrics endpoint — self-service usage dashboard.
 *
 * <p>Authenticated via API key ({@code @TenantScoped} interceptor sets the tenant context). Queries
 * are automatically scoped to the authenticated tenant's data — no cross-tenant access is possible.
 */
@Path("/api/v1/metrics")
@TenantScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(ref = CveilOpenApiConfig.TAG_CONSENT)
@SecurityRequirement(name = CveilOpenApiConfig.SECURITY_SCHEME)
public class TenantMetricsResource {

    private static final Logger LOG = LoggerFactory.getLogger(TenantMetricsResource.class);
    private static final ObjectMapper MAPPER =
            new ObjectMapper().registerModule(new JavaTimeModule());
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final int MAX_PAGE_SIZE = 500;

    @Inject
    @Named("platformDataSource")
    jakarta.enterprise.inject.Instance<DataSource> dataSourceInstance;

    @Inject CveilRequestHolder requestHolder;

    @GET
    @Operation(
            operationId = "getTenantMetrics",
            summary = "Query tenant usage metrics",
            description =
                    "Returns paginated telemetry rows for the authenticated tenant. "
                            + "Supports filtering by metric name and time range. "
                            + "Tenant ID is derived from the API key — no cross-tenant access.")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Metrics data",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = MetricsResponse.class))),
        @APIResponse(responseCode = "401", description = "Invalid or missing API key"),
        @APIResponse(responseCode = "503", description = "Metrics not available"),
    })
    public Response getMetrics(
            @Context SecurityContext sc,
            @Parameter(description = "Filter by metric name") @QueryParam("metricName")
                    String metricName,
            @Parameter(description = "Start of time range (ISO-8601)") @QueryParam("from")
                    String from,
            @Parameter(description = "End of time range (ISO-8601)") @QueryParam("to") String to,
            @Parameter(description = "Page number (1-based)") @QueryParam("page") @DefaultValue("1")
                    int page,
            @Parameter(description = "Page size (max 500)")
                    @QueryParam("pageSize")
                    @DefaultValue("100")
                    int pageSize) {
        Permissions.require(requestHolder.authorization(), Permissions.CONSENT_READ);

        String tenantId = currentTenantId();
        if (tenantId == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(ErrorResponse.internal("Unable to resolve tenant"))
                    .build();
        }

        if (!dataSourceInstance.isResolvable()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(ErrorResponse.internal("Metrics not available in embedded mode"))
                    .build();
        }

        int clampedPage = Math.max(1, page);
        int clampedSize = Math.max(1, Math.min(pageSize, MAX_PAGE_SIZE));
        int offset = (clampedPage - 1) * clampedSize;

        Instant fromInstant;
        Instant toInstant;
        try {
            fromInstant = from != null ? Instant.parse(from) : null;
            toInstant = to != null ? Instant.parse(to) : null;
        } catch (java.time.format.DateTimeParseException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(
                            ErrorResponse.validation(
                                    "Invalid date format. Use ISO-8601 (e.g."
                                            + " 2024-01-01T00:00:00Z)"))
                    .build();
        }

        try {
            QueryResult result =
                    executeQuery(tenantId, metricName, fromInstant, toInstant, clampedSize, offset);
            return Response.ok(
                            new MetricsResponse(
                                    result.rows, result.total, clampedPage, clampedSize))
                    .build();
        } catch (Exception e) {
            LOG.error("Failed to query tenant metrics for {}", tenantId, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ErrorResponse.internal("Failed to query metrics"))
                    .build();
        }
    }

    @GET
    @Path("/summary")
    @Operation(
            operationId = "getTenantMetricsSummary",
            summary = "Get aggregated metric summaries for this tenant",
            description =
                    "Returns per-metric totals for the authenticated tenant within the given "
                            + "time range. Useful for billing dashboards and usage overviews.")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Metric summaries",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = MetricsSummaryResponse.class))),
        @APIResponse(responseCode = "401", description = "Invalid or missing API key"),
        @APIResponse(responseCode = "503", description = "Metrics not available"),
    })
    public Response getSummary(
            @Context SecurityContext sc,
            @Parameter(description = "Start of time range (ISO-8601)") @QueryParam("from")
                    String from,
            @Parameter(description = "End of time range (ISO-8601)") @QueryParam("to") String to) {
        Permissions.require(requestHolder.authorization(), Permissions.CONSENT_READ);

        String tenantId = currentTenantId();
        if (tenantId == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(ErrorResponse.internal("Unable to resolve tenant"))
                    .build();
        }

        if (!dataSourceInstance.isResolvable()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(ErrorResponse.internal("Metrics not available in embedded mode"))
                    .build();
        }

        Instant fromInstant;
        Instant toInstant;
        try {
            fromInstant = from != null ? Instant.parse(from) : null;
            toInstant = to != null ? Instant.parse(to) : null;
        } catch (java.time.format.DateTimeParseException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(
                            ErrorResponse.validation(
                                    "Invalid date format. Use ISO-8601 (e.g."
                                            + " 2024-01-01T00:00:00Z)"))
                    .build();
        }

        try {
            List<MetricSummary> summaries = executeSummary(tenantId, fromInstant, toInstant);
            return Response.ok(new MetricsSummaryResponse(summaries, fromInstant, toInstant))
                    .build();
        } catch (Exception e) {
            LOG.error("Failed to query tenant metrics summary for {}", tenantId, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ErrorResponse.internal("Failed to query metrics summary"))
                    .build();
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private String currentTenantId() {
        return requestHolder.identity() != null ? requestHolder.identity().tenantId() : null;
    }

    private record QueryResult(List<MetricRow> rows, long total) {}

    private QueryResult executeQuery(
            String tenantId, String metricName, Instant from, Instant to, int limit, int offset)
            throws Exception {
        StringBuilder where = new StringBuilder(" WHERE tenant_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(tenantId);

        if (metricName != null && !metricName.isBlank()) {
            where.append(" AND metric_name = ?");
            params.add(metricName);
        }
        if (from != null) {
            where.append(" AND time_bucket >= ?");
            params.add(java.sql.Timestamp.from(from));
        }
        if (to != null) {
            where.append(" AND time_bucket <= ?");
            params.add(java.sql.Timestamp.from(to));
        }

        String countSql = "SELECT COUNT(*) FROM platform_metrics.telemetry" + where;
        String dataSql =
                "SELECT time_bucket, tenant_id, metric_name, dimensions, metric_value, published_at"
                        + " FROM platform_metrics.telemetry"
                        + where
                        + " ORDER BY time_bucket DESC LIMIT ? OFFSET ?";

        DataSource ds = dataSourceInstance.get();
        long total;
        List<MetricRow> rows = new ArrayList<>();

        try (Connection conn = ds.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(countSql)) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    total = rs.next() ? rs.getLong(1) : 0;
                }
            }

            try (PreparedStatement ps = conn.prepareStatement(dataSql)) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
                ps.setInt(params.size() + 1, limit);
                ps.setInt(params.size() + 2, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(mapRow(rs));
                    }
                }
            }
        }
        return new QueryResult(rows, total);
    }

    private List<MetricSummary> executeSummary(String tenantId, Instant from, Instant to)
            throws Exception {
        StringBuilder where = new StringBuilder(" WHERE tenant_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(tenantId);

        if (from != null) {
            where.append(" AND time_bucket >= ?");
            params.add(java.sql.Timestamp.from(from));
        }
        if (to != null) {
            where.append(" AND time_bucket <= ?");
            params.add(java.sql.Timestamp.from(to));
        }

        String sql =
                "SELECT metric_name, SUM(metric_value) AS total_value,"
                        + " MIN(time_bucket) AS first_seen, MAX(time_bucket) AS last_seen"
                        + " FROM platform_metrics.telemetry"
                        + where
                        + " GROUP BY metric_name ORDER BY total_value DESC";

        DataSource ds = dataSourceInstance.get();
        List<MetricSummary> summaries = new ArrayList<>();

        try (Connection conn = ds.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    summaries.add(
                            new MetricSummary(
                                    rs.getString("metric_name"),
                                    rs.getDouble("total_value"),
                                    toInstantSafe(rs.getTimestamp("first_seen")),
                                    toInstantSafe(rs.getTimestamp("last_seen"))));
                }
            }
        }
        return summaries;
    }

    private MetricRow mapRow(ResultSet rs) throws Exception {
        Map<String, Object> dims;
        String dimJson = rs.getString("dimensions");
        dims =
                (dimJson != null && !dimJson.isBlank())
                        ? MAPPER.readValue(dimJson, MAP_TYPE)
                        : Map.of();
        return new MetricRow(
                toInstantSafe(rs.getTimestamp("time_bucket")),
                rs.getString("tenant_id"),
                rs.getString("metric_name"),
                dims,
                rs.getDouble("metric_value"),
                toInstantSafe(rs.getTimestamp("published_at")));
    }

    private static Instant toInstantSafe(java.sql.Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }
}
