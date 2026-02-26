package com.koostack.operio.rest.dto;

import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Request and response DTOs for the usage metering endpoints.
 *
 * <p>These DTOs are used by both the self-service usage endpoint
 * ({@code GET /api/v1/usage}) and the super-admin usage endpoints
 * ({@code GET /api/v1/platform/tenants/{id}/usage} and
 * {@code GET /api/v1/platform/usage/export}).
 */
public final class UsageDtos {

    private UsageDtos() {
        // Utility class — no instances
    }

    /**
     * A usage metric with its current count and plan limit.
     *
     * @param count the current usage count for the billing period
     * @param limit the plan-imposed limit for this metric
     */
    @Schema(description = "A usage metric with current count and plan limit")
    public record MetricUsage(
        @Schema(description = "Current usage count", example = "45231")
        long count,
        @Schema(description = "Plan limit for this metric", example = "500000")
        long limit
    ) {}

    /**
     * Full usage response for a single tenant in a single billing period.
     *
     * @param tenantId         the tenant identifier
     * @param period           the billing period in {@code YYYY-MM} format
     * @param evaluations      consent evaluation usage and limit
     * @param subjects         distinct subject usage and limit
     * @param burstLimitPerMin the per-minute burst request limit
     * @param plan             the tenant's current subscription plan
     */
    @Schema(description = "Usage summary for a tenant in a billing period")
    public record UsageResponse(
        @Schema(description = "Tenant identifier", example = "acme_corp")
        String tenantId,
        @Schema(description = "Billing period (YYYY-MM)", example = "2026-02")
        String period,
        @Schema(description = "Consent evaluation usage")
        MetricUsage evaluations,
        @Schema(description = "Distinct subject usage")
        MetricUsage subjects,
        @Schema(description = "Per-minute burst request limit", example = "500")
        int burstLimitPerMin,
        @Schema(description = "Subscription plan", example = "pro")
        String plan
    ) {}

    /**
     * A single row in the usage export report (all tenants for a period).
     *
     * @param tenantId         the tenant identifier
     * @param plan             the tenant's subscription plan
     * @param evaluations      evaluation count for the period
     * @param evalLimit        evaluation limit from the plan
     * @param subjects         distinct subject count for the period
     * @param subjectLimit     subject limit from the plan
     * @param burstLimitPerMin per-minute burst limit
     */
    @Schema(description = "Usage export row for a single tenant")
    public record UsageExportRow(
        @Schema(description = "Tenant identifier", example = "acme_corp")
        String tenantId,
        @Schema(description = "Subscription plan", example = "pro")
        String plan,
        @Schema(description = "Evaluation count", example = "45231")
        long evaluations,
        @Schema(description = "Evaluation limit", example = "500000")
        long evalLimit,
        @Schema(description = "Subject count", example = "8742")
        long subjects,
        @Schema(description = "Subject limit", example = "50000")
        long subjectLimit,
        @Schema(description = "Burst limit per minute", example = "500")
        int burstLimitPerMin
    ) {
        /** CSV header line. */
        public static final String CSV_HEADER =
            "tenantId,plan,evaluations,evalLimit,subjects,subjectLimit,burstLimitPerMin";

        /** Converts this row to a CSV line. */
        public String toCsvLine() {
            return String.join(",",
                tenantId, plan,
                String.valueOf(evaluations), String.valueOf(evalLimit),
                String.valueOf(subjects), String.valueOf(subjectLimit),
                String.valueOf(burstLimitPerMin)
            );
        }
    }

    /**
     * Wrapper for the usage export response (JSON format).
     *
     * @param period the billing period
     * @param rows   the per-tenant usage rows
     */
    @Schema(description = "Usage export for all tenants in a billing period")
    public record UsageExportResponse(
        @Schema(description = "Billing period (YYYY-MM)", example = "2026-02")
        String period,
        @Schema(description = "Per-tenant usage rows")
        List<UsageExportRow> rows
    ) {}
}
