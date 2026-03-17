package com.koostack.cveil.rest.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Request and response DTOs for the metrics / telemetry endpoints.
 *
 * <p>Used by both the platform admin metrics endpoint ({@code /api/v1/platform/metrics}) and the
 * tenant-scoped self-service metrics endpoint ({@code /api/v1/metrics}).
 */
public final class MetricsDtos {

    private MetricsDtos() {}

    /** A single telemetry data point returned from the metrics query. */
    @Schema(description = "A single telemetry data point")
    public record MetricRow(
            @Schema(description = "Time bucket start") Instant timeBucket,
            @Schema(description = "Tenant identifier") String tenantId,
            @Schema(description = "Metric name") String metricName,
            @Schema(description = "Dimension key-value pairs") Map<String, Object> dimensions,
            @Schema(description = "Accumulated or latest metric value") double metricValue,
            @Schema(description = "Last published timestamp") Instant publishedAt) {}

    /** Paginated response wrapper for telemetry rows. */
    @Schema(description = "Paginated metrics query result")
    public record MetricsResponse(
            @Schema(description = "Metric data points") List<MetricRow> data,
            @Schema(description = "Total rows matching the query") long total,
            @Schema(description = "Current page (1-based)") int page,
            @Schema(description = "Page size") int pageSize) {}

    /** Summary row for aggregated metric totals. */
    @Schema(description = "Aggregated metric summary")
    public record MetricSummary(
            @Schema(description = "Metric name") String metricName,
            @Schema(description = "Total accumulated value") double totalValue,
            @Schema(description = "Earliest time bucket") Instant firstSeen,
            @Schema(description = "Latest time bucket") Instant lastSeen) {}

    /** Response for the summary/rollup endpoint. */
    @Schema(description = "Metrics summary response")
    public record MetricsSummaryResponse(
            @Schema(description = "Metric summaries") List<MetricSummary> summaries,
            @Schema(description = "Query time range start") Instant from,
            @Schema(description = "Query time range end") Instant to) {}
}
