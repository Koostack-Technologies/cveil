package com.koostack.operio.rest.resource;

import com.koostack.operio.core.Models.TenantInfo;
import com.koostack.operio.core.service.PlatformAdminService;
import com.koostack.operio.plugin.platform.jooq.JooqPlatformAdminService;
import com.koostack.operio.plugin.platform.jooq.RateLimitCounterRepository;
import com.koostack.operio.rest.auth.Roles;
import com.koostack.operio.rest.config.OperioOpenApiConfig;
import com.koostack.operio.rest.context.OperioRequestHolder;
import com.koostack.operio.rest.dto.CommonDtos.ErrorResponse;
import com.koostack.operio.rest.dto.UsageDtos.MetricUsage;
import com.koostack.operio.rest.dto.UsageDtos.UsageExportResponse;
import com.koostack.operio.rest.dto.UsageDtos.UsageExportRow;
import com.koostack.operio.rest.dto.UsageDtos.UsageResponse;
import com.koostack.operio.rest.interceptor.TenantScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
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
 * JAX-RS resource for usage metering and billing endpoints.
 *
 * <p>Provides three endpoints:
 * <ol>
 *   <li>{@code GET /api/v1/usage} — self-service usage for the calling
 *       tenant (requires {@code consent:read})</li>
 *   <li>{@code GET /api/v1/platform/tenants/{id}/usage} — admin usage
 *       for a specific tenant (requires {@code super-admin})</li>
 *   <li>{@code GET /api/v1/platform/usage/export} — bulk export of all
 *       tenants' usage for a billing period (requires {@code super-admin})</li>
 * </ol>
 *
 * <p>All usage data is read from the durable {@code rate_limit_counter}
 * Postgres table — the billing source of truth. The counters are
 * populated by the real-time sync in {@code RateLimitService.checkMonthly()}
 * and the periodic {@code CounterReconciliationJob}.
 */
@Path("/api/v1")
@TenantScoped
@Produces(MediaType.APPLICATION_JSON)
@Tag(ref = OperioOpenApiConfig.TAG_PLATFORM)
@SecurityRequirement(name = OperioOpenApiConfig.SECURITY_SCHEME)
public class UsageResource {

    private static final Logger LOG = LoggerFactory.getLogger(
        UsageResource.class
    );

    private static final DateTimeFormatter MONTH_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM");

    @Inject
    @SuppressWarnings("rawtypes")
    PlatformAdminService platformAdminService;

    @Inject
    OperioRequestHolder requestHolder;

    // -------------------------------------------------------------------
    // Self-service usage
    // -------------------------------------------------------------------

    @GET
    @Path("/usage")
    @Operation(
        operationId = "getSelfUsage",
        summary = "Get usage for the calling tenant",
        description = "Returns the current billing period usage for the tenant " +
            "associated with the calling API key. Useful for self-service " +
            "dashboards showing how much of the plan quota has been consumed."
    )
    @APIResponses(
        {
            @APIResponse(
                responseCode = "200",
                description = "Usage summary for the current period",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = UsageResponse.class)
                )
            ),
            @APIResponse(
                responseCode = "403",
                description = "Missing required role (consent:read)",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ErrorResponse.class)
                )
            ),
        }
    )
    public Response getSelfUsage(@Context SecurityContext securityContext) {
        Roles.requireRole(securityContext, Roles.CONSENT_READ);

        String tenantId = requestHolder.identity().tenantId();
        if (tenantId == null || tenantId.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of(
                    "error", "no_tenant",
                    "message", "No tenant associated with this API key"
                ))
                .build();
        }

        LOG.debug("GET /usage (self-service, tenant={})", tenantId);

        String period = currentPeriod();
        UsageResponse usage = buildUsageResponse(tenantId, period);
        if (usage == null) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(Map.of(
                    "error", "tenant_not_found",
                    "message", "Tenant '" + tenantId + "' not found"
                ))
                .build();
        }

        return Response.ok(usage).build();
    }

    // -------------------------------------------------------------------
    // Admin: tenant usage
    // -------------------------------------------------------------------

    @GET
    @Path("/platform/tenants/{id}/usage")
    @Operation(
        operationId = "getTenantUsage",
        summary = "Get usage for a specific tenant",
        description = "Returns the usage for a specific tenant in the given billing " +
            "period (defaults to current month). Requires the super-admin role."
    )
    @APIResponses(
        {
            @APIResponse(
                responseCode = "200",
                description = "Tenant usage summary",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = UsageResponse.class)
                )
            ),
            @APIResponse(
                responseCode = "403",
                description = "Missing required role (super-admin)",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ErrorResponse.class)
                )
            ),
            @APIResponse(
                responseCode = "404",
                description = "Tenant not found",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ErrorResponse.class)
                )
            ),
        }
    )
    public Response getTenantUsage(
        @Context SecurityContext securityContext,
        @Parameter(
            description = "Unique tenant identifier",
            required = true,
            example = "acme_corp"
        ) @PathParam("id") String tenantId,
        @Parameter(
            description = "Billing period in YYYY-MM format (defaults to current month)",
            example = "2026-02"
        ) @QueryParam("period") String period
    ) {
        Roles.requireRole(securityContext, Roles.SUPER_ADMIN);
        requirePlatformAdmin();

        String effectivePeriod = (period != null && !period.isBlank())
            ? period
            : currentPeriod();

        LOG.debug(
            "GET /platform/tenants/{}/usage?period={}",
            tenantId,
            effectivePeriod
        );

        UsageResponse usage = buildUsageResponse(tenantId, effectivePeriod);
        if (usage == null) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(Map.of(
                    "error", "tenant_not_found",
                    "message", "Tenant '" + tenantId + "' not found"
                ))
                .build();
        }

        return Response.ok(usage).build();
    }

    // -------------------------------------------------------------------
    // Admin: usage export
    // -------------------------------------------------------------------

    @GET
    @Path("/platform/usage/export")
    @Produces({ MediaType.APPLICATION_JSON, "text/csv" })
    @Operation(
        operationId = "exportUsage",
        summary = "Export usage for all tenants",
        description = "Returns usage data for all tenants in the specified billing " +
            "period. Supports JSON (default) and CSV (via Accept: text/csv header). " +
            "This is the data that feeds the billing system. " +
            "Requires the super-admin role."
    )
    @APIResponses(
        {
            @APIResponse(
                responseCode = "200",
                description = "Usage export data",
                content = {
                    @Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = @Schema(
                            implementation = UsageExportResponse.class
                        )
                    ),
                    @Content(
                        mediaType = "text/csv",
                        schema = @Schema(
                            type = SchemaType.STRING,
                            description = "CSV with header row"
                        )
                    ),
                }
            ),
            @APIResponse(
                responseCode = "400",
                description = "Missing period parameter",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ErrorResponse.class)
                )
            ),
            @APIResponse(
                responseCode = "403",
                description = "Missing required role (super-admin)",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ErrorResponse.class)
                )
            ),
        }
    )
    public Response exportUsage(
        @Context SecurityContext securityContext,
        @Parameter(
            description = "Billing period in YYYY-MM format (defaults to current month)",
            example = "2026-02"
        ) @QueryParam("period") String period,
        @Parameter(
            description = "Response format: 'csv' for CSV, omit for JSON",
            example = "csv"
        ) @QueryParam("format") String format
    ) {
        Roles.requireRole(securityContext, Roles.SUPER_ADMIN);
        requirePlatformAdmin();

        String effectivePeriod = (period != null && !period.isBlank())
            ? period
            : currentPeriod();

        LOG.info(
            "GET /platform/usage/export?period={}&format={}",
            effectivePeriod,
            format
        );

        JooqPlatformAdminService jooqAdmin = asJooqAdmin();
        RateLimitCounterRepository counterRepo =
            jooqAdmin.rateLimitCounterRepository();

        // Fetch all counter rows for the period
        List<RateLimitCounterRepository.CounterRow> counterRows =
            counterRepo.getAllByPeriod(effectivePeriod);

        // Group counters by tenant
        Map<String, Map<String, Long>> tenantCounters = new HashMap<>();
        for (RateLimitCounterRepository.CounterRow row : counterRows) {
            tenantCounters
                .computeIfAbsent(row.tenantId(), k -> new HashMap<>())
                .put(row.metric(), row.counter());
        }

        // Fetch all tenants for metadata (plan, limits)
        List<TenantInfo> tenants = platformAdminService.listTenants();
        Map<String, TenantInfo> tenantMap = new HashMap<>();
        for (TenantInfo t : tenants) {
            tenantMap.put(t.tenantId(), t);
        }

        // Build export rows — include all tenants (even those with zero usage)
        List<UsageExportRow> rows = new ArrayList<>();
        for (TenantInfo tenant : tenants) {
            Map<String, Long> counters =
                tenantCounters.getOrDefault(tenant.tenantId(), Map.of());
            rows.add(new UsageExportRow(
                tenant.tenantId(),
                tenant.plan(),
                counters.getOrDefault("evaluations", 0L),
                tenant.evalLimitMonthly(),
                counters.getOrDefault("subjects", 0L),
                tenant.subjectLimit(),
                tenant.burstLimitPerMin()
            ));
        }

        // CSV format
        if ("csv".equalsIgnoreCase(format)) {
            StringBuilder csv = new StringBuilder();
            csv.append(UsageExportRow.CSV_HEADER).append('\n');
            for (UsageExportRow row : rows) {
                csv.append(row.toCsvLine()).append('\n');
            }
            return Response.ok(csv.toString())
                .type("text/csv")
                .header(
                    "Content-Disposition",
                    "attachment; filename=\"operio-usage-" +
                        effectivePeriod + ".csv\""
                )
                .build();
        }

        // JSON format (default)
        return Response.ok(
            new UsageExportResponse(effectivePeriod, rows)
        ).build();
    }

    // -------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------

    private void requirePlatformAdmin() {
        if (platformAdminService == null) {
            throw new IllegalStateException(
                "Platform administration is not available in embedded mode. " +
                    "Set operio.mode=production to enable usage metering."
            );
        }
    }

    private JooqPlatformAdminService asJooqAdmin() {
        if (platformAdminService instanceof JooqPlatformAdminService jooqAdmin) {
            return jooqAdmin;
        }
        throw new IllegalStateException(
            "Usage metering requires the jOOQ platform admin service"
        );
    }

    private String currentPeriod() {
        return YearMonth.now(ZoneOffset.UTC).format(MONTH_FORMATTER);
    }

    /**
     * Builds a {@link UsageResponse} for a tenant and period by reading
     * from the durable Postgres counters and the tenant's plan limits.
     *
     * @return the usage response, or {@code null} if the tenant is not found
     */
    private UsageResponse buildUsageResponse(String tenantId, String period) {
        TenantInfo tenant;
        try {
            tenant = platformAdminService.getTenant(tenantId);
        } catch (Exception e) {
            return null;
        }
        if (tenant == null) {
            return null;
        }

        JooqPlatformAdminService jooqAdmin = asJooqAdmin();
        RateLimitCounterRepository counterRepo =
            jooqAdmin.rateLimitCounterRepository();

        Map<String, Long> counters =
            counterRepo.getByTenantAndPeriod(tenantId, period);

        long evalCount = counters.getOrDefault("evaluations", 0L);
        long subjectCount = counters.getOrDefault("subjects", 0L);

        return new UsageResponse(
            tenantId,
            period,
            new MetricUsage(evalCount, tenant.evalLimitMonthly()),
            new MetricUsage(subjectCount, tenant.subjectLimit()),
            tenant.burstLimitPerMin(),
            tenant.plan()
        );
    }
}
