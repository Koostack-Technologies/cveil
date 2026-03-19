package com.koostack.cveil.rest.resource;

import com.koostack.cveil.rest.auth.Permissions;
import com.koostack.cveil.rest.config.CveilOpenApiConfig;
import com.koostack.cveil.rest.context.CveilRequestHolder;
import com.koostack.cveil.rest.dto.CommonDtos.ErrorResponse;
import com.koostack.cveil.rest.interceptor.TenantScoped;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Compliance reports and audit log queries. Provides consent summary, activity over time, audit
 * trail, governance snapshot, and CSV/JSON export endpoints.
 */
@Path("/api/v1/reports")
@TenantScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(ref = CveilOpenApiConfig.TAG_CONSENT)
@SecurityRequirement(name = CveilOpenApiConfig.SECURITY_SCHEME)
@Blocking
public class ReportResource {

    private static final Logger LOG = LoggerFactory.getLogger(ReportResource.class);
    private static final int MAX_EXPORT_ROWS = 100_000;

    @Inject
    @Named("platformDataSource")
    jakarta.enterprise.inject.Instance<DataSource> dataSourceInstance;

    @Inject CveilRequestHolder requestHolder;

    // -----------------------------------------------------------------------
    // Consent Summary — counts by purpose
    // -----------------------------------------------------------------------

    @GET
    @Path("/consent-summary")
    @Operation(operationId = "consentSummaryReport", summary = "Consent status counts by purpose")
    public Response consentSummary() {
        Permissions.require(requestHolder.authorization(), Permissions.REPORTS_READ);
        String tenantId = currentTenantId();
        if (tenantId == null) return unauthorized();

        // Derive current consent status from latest action per (subject, policy).
        // consent_ledger is append-only with action enum (GRANT/REVOKE/EXPIRE).
        String sql =
                """
                WITH latest_consent AS (
                    SELECT DISTINCT ON (cl.subject_id, cl.policy_uuid)
                           cl.policy_uuid, cl.action
                    FROM cveilconsent.consent_ledger cl
                    WHERE cl.tenant_id = ?
                    ORDER BY cl.subject_id, cl.policy_uuid, cl.created_at DESC
                )
                SELECT pu.purpose_name, pu.description AS purpose_description,
                       COUNT(*) FILTER (WHERE lc.action = 'GRANT')  AS active,
                       COUNT(*) FILTER (WHERE lc.action = 'REVOKE') AS revoked,
                       COUNT(*) FILTER (WHERE lc.action = 'EXPIRE') AS expired,
                       COUNT(lc.policy_uuid) AS total
                FROM cveilconsent.purpose pu
                LEFT JOIN cveilconsent.policy po
                       ON po.purpose_uuid = pu.purpose_uuid AND po.tenant_id = pu.tenant_id
                LEFT JOIN latest_consent lc ON lc.policy_uuid = po.policy_uuid
                WHERE pu.tenant_id = ?
                GROUP BY pu.purpose_name, pu.description
                ORDER BY pu.purpose_name
                """;

        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>();
                    row.put("purposeName", rs.getString("purpose_name"));
                    row.put("purposeDescription", rs.getString("purpose_description"));
                    row.put("active", rs.getInt("active"));
                    row.put("revoked", rs.getInt("revoked"));
                    row.put("expired", rs.getInt("expired"));
                    row.put("total", rs.getInt("total"));
                    rows.add(row);
                }
                return Response.ok(Map.of("tenantId", tenantId, "purposes", rows)).build();
            }
        } catch (Exception e) {
            LOG.error("consentSummary failed for tenant {}", tenantId, e);
            return Response.serverError()
                    .entity(ErrorResponse.internal("Failed to generate consent summary"))
                    .build();
        }
    }

    // -----------------------------------------------------------------------
    // Consent Activity — grants/revokes over time
    // -----------------------------------------------------------------------

    @GET
    @Path("/consent-activity")
    @Operation(
            operationId = "consentActivityReport",
            summary = "Consent grants and revocations over time")
    public Response consentActivity(
            @QueryParam("from") String from,
            @QueryParam("to") String to,
            @QueryParam("interval") @DefaultValue("1d") String interval) {
        Permissions.require(requestHolder.authorization(), Permissions.REPORTS_READ);
        String tenantId = currentTenantId();
        if (tenantId == null) return unauthorized();

        // Validate date range
        if (from != null && to != null && from.compareTo(to) > 0) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ErrorResponse.validation("'from' must not be after 'to'"))
                    .build();
        }
        if (from != null && to != null) {
            try {
                long days =
                        java.time.Duration.between(Instant.parse(from), Instant.parse(to)).toDays();
                if (days > 90) {
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity(ErrorResponse.validation("Date range must not exceed 90 days"))
                            .build();
                }
            } catch (Exception ignored) {
                /* parse errors handled by DB cast */
            }
        }

        String truncExpr =
                switch (interval) {
                    case "1h" -> "date_trunc('hour', cl.created_at)";
                    case "1w" -> "date_trunc('week', cl.created_at)";
                    default -> "date_trunc('day', cl.created_at)";
                };

        // consent_ledger uses action enum (GRANT/REVOKE/EXPIRE), not status column
        String sql =
                "SELECT "
                        + truncExpr
                        + " AS bucket, "
                        + " COUNT(*) FILTER (WHERE cl.action = 'GRANT')  AS grants, "
                        + " COUNT(*) FILTER (WHERE cl.action = 'REVOKE') AS revocations "
                        + "FROM cveilconsent.consent_ledger cl "
                        + "WHERE cl.tenant_id = ? "
                        + (from != null ? "AND cl.created_at >= ?::timestamptz " : "")
                        + (to != null ? "AND cl.created_at <= ?::timestamptz " : "")
                        + "GROUP BY bucket ORDER BY bucket";

        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            ps.setString(idx++, tenantId);
            if (from != null) ps.setString(idx++, from);
            if (to != null) ps.setString(idx++, to);

            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>();
                    row.put("bucket", rs.getTimestamp("bucket").toInstant().toString());
                    row.put("grants", rs.getInt("grants"));
                    row.put("revocations", rs.getInt("revocations"));
                    rows.add(row);
                }
                return Response.ok(Map.of("tenantId", tenantId, "interval", interval, "data", rows))
                        .build();
            }
        } catch (Exception e) {
            LOG.error("consentActivity failed for tenant {}", tenantId, e);
            return Response.serverError()
                    .entity(ErrorResponse.internal("Failed to generate consent activity report"))
                    .build();
        }
    }

    // -----------------------------------------------------------------------
    // Audit Log — paginated event trail
    // -----------------------------------------------------------------------

    @GET
    @Path("/audit-log")
    @Operation(operationId = "auditLogReport", summary = "Paginated audit trail of domain events")
    public Response auditLog(
            @QueryParam("from") String from,
            @QueryParam("to") String to,
            @QueryParam("subject") String subject,
            @QueryParam("limit") @DefaultValue("100") int limit,
            @QueryParam("offset") @DefaultValue("0") int offset) {
        Permissions.require(requestHolder.authorization(), Permissions.REPORTS_READ);
        String tenantId = currentTenantId();
        if (tenantId == null) return unauthorized();

        limit = Math.min(limit, 500);

        // Validate date range (max 90 days, default 30-day window)
        Instant fromInstant;
        Instant toInstant;
        try {
            fromInstant = from != null ? Instant.parse(from) : null;
            toInstant = to != null ? Instant.parse(to) : null;
        } catch (Exception e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(
                            ErrorResponse.validation(
                                    "Invalid date format. Use ISO-8601 (e.g."
                                            + " 2024-01-01T00:00:00Z)"))
                    .build();
        }
        if (fromInstant != null && toInstant != null && fromInstant.isAfter(toInstant)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ErrorResponse.validation("'from' must not be after 'to'"))
                    .build();
        }
        if (fromInstant != null && toInstant != null) {
            if (java.time.Duration.between(fromInstant, toInstant).toDays() > 90) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(ErrorResponse.validation("Date range must not exceed 90 days"))
                        .build();
            }
        }
        // Default 30-day window when no range specified
        if (fromInstant == null && toInstant == null) {
            fromInstant = Instant.now().minus(java.time.Duration.ofDays(30));
            from = fromInstant.toString();
        }

        // Escape SQL ILIKE wildcards in subject filter
        String escapedSubject = null;
        if (subject != null && !subject.isBlank()) {
            escapedSubject = subject.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        }

        StringBuilder sb = new StringBuilder();
        sb.append(
                "SELECT event_id, event_type, aggregate_id, aggregate_type, version, payload,"
                        + " created_at ");
        sb.append("FROM cveilevent.event ");
        sb.append("WHERE tenant_id = ? ");
        if (from != null) sb.append("AND created_at >= ?::timestamptz ");
        if (to != null) sb.append("AND created_at <= ?::timestamptz ");
        if (escapedSubject != null)
            sb.append("AND payload::text ILIKE '%' || ? || '%' ESCAPE '\\' ");
        sb.append("ORDER BY created_at DESC LIMIT ? OFFSET ?");

        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement(sb.toString())) {
            int idx = 1;
            ps.setString(idx++, tenantId);
            if (from != null) ps.setString(idx++, from);
            if (to != null) ps.setString(idx++, to);
            if (escapedSubject != null) ps.setString(idx++, escapedSubject);
            ps.setInt(idx++, limit);
            ps.setInt(idx++, offset);

            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>();
                    row.put("eventId", rs.getLong("event_id"));
                    row.put("eventType", rs.getString("event_type"));
                    row.put("aggregateId", rs.getString("aggregate_id"));
                    row.put("aggregateType", rs.getString("aggregate_type"));
                    row.put("version", rs.getInt("version"));
                    row.put("payload", rs.getString("payload"));
                    row.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                    rows.add(row);
                }
                return Response.ok(
                                Map.of(
                                        "tenantId", tenantId,
                                        "limit", limit,
                                        "offset", offset,
                                        "events", rows))
                        .build();
            }
        } catch (Exception e) {
            LOG.error("auditLog failed for tenant {}", tenantId, e);
            return Response.serverError()
                    .entity(ErrorResponse.internal("Failed to generate audit log"))
                    .build();
        }
    }

    // -----------------------------------------------------------------------
    // Governance Snapshot — current jurisdictions, regulations, purposes
    // -----------------------------------------------------------------------

    @GET
    @Path("/governance-snapshot")
    @Operation(
            operationId = "governanceSnapshotReport",
            summary = "Current governance configuration snapshot")
    public Response governanceSnapshot() {
        Permissions.require(requestHolder.authorization(), Permissions.REPORTS_READ);
        String tenantId = currentTenantId();
        if (tenantId == null) return unauthorized();

        String sql =
                """
                SELECT
                    (SELECT COUNT(*) FROM cveilconsent.jurisdiction WHERE tenant_id = ?) AS jurisdictions,
                    (SELECT COUNT(*) FROM cveilconsent.regulation   WHERE tenant_id = ?) AS regulations,
                    (SELECT COUNT(*) FROM cveilconsent.purpose      WHERE tenant_id = ?) AS purposes,
                    (SELECT COUNT(*) FROM cveilconsent.entity       WHERE tenant_id = ?) AS entities,
                    (SELECT COUNT(*) FROM cveilconsent.subject      WHERE tenant_id = ?) AS subjects
                """;
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 1; i <= 5; i++) ps.setString(i, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                Map<String, Object> snapshot = new HashMap<>();
                snapshot.put("tenantId", tenantId);
                snapshot.put("jurisdictions", rs.getInt("jurisdictions"));
                snapshot.put("regulations", rs.getInt("regulations"));
                snapshot.put("purposes", rs.getInt("purposes"));
                snapshot.put("entities", rs.getInt("entities"));
                snapshot.put("subjects", rs.getInt("subjects"));
                snapshot.put("generatedAt", Instant.now().toString());
                return Response.ok(snapshot).build();
            }
        } catch (Exception e) {
            LOG.error("governanceSnapshot failed for tenant {}", tenantId, e);
            return Response.serverError()
                    .entity(ErrorResponse.internal("Failed to generate governance snapshot"))
                    .build();
        }
    }

    // -----------------------------------------------------------------------
    // CSV Export
    // -----------------------------------------------------------------------

    @GET
    @Path("/export/consent-summary")
    @Produces("text/csv")
    @Operation(operationId = "exportConsentSummary", summary = "Export consent summary as CSV")
    public Response exportConsentSummaryCsv() {
        Permissions.require(requestHolder.authorization(), Permissions.REPORTS_READ);
        String tenantId = currentTenantId();
        if (tenantId == null) return unauthorized();

        String sql =
                """
                WITH latest_consent AS (
                    SELECT DISTINCT ON (cl.subject_id, cl.policy_uuid)
                           cl.policy_uuid, cl.action
                    FROM cveilconsent.consent_ledger cl
                    WHERE cl.tenant_id = ?
                    ORDER BY cl.subject_id, cl.policy_uuid, cl.created_at DESC
                )
                SELECT pu.purpose_name, pu.description AS purpose_description,
                       COUNT(*) FILTER (WHERE lc.action = 'GRANT')  AS active,
                       COUNT(*) FILTER (WHERE lc.action = 'REVOKE') AS revoked,
                       COUNT(*) FILTER (WHERE lc.action = 'EXPIRE') AS expired,
                       COUNT(lc.policy_uuid) AS total
                FROM cveilconsent.purpose pu
                LEFT JOIN cveilconsent.policy po
                       ON po.purpose_uuid = pu.purpose_uuid AND po.tenant_id = pu.tenant_id
                LEFT JOIN latest_consent lc ON lc.policy_uuid = po.policy_uuid
                WHERE pu.tenant_id = ?
                GROUP BY pu.purpose_name, pu.description
                ORDER BY pu.purpose_name
                """;

        // Stream CSV directly to the response OutputStream — constant memory regardless of row
        // count
        StreamingOutput stream =
                output -> {
                    try (BufferedWriter writer =
                                    new BufferedWriter(
                                            new OutputStreamWriter(
                                                    output, StandardCharsets.UTF_8));
                            Connection conn = dataSourceInstance.get().getConnection();
                            PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setString(1, tenantId);
                        ps.setString(2, tenantId);
                        ps.setFetchSize(256);

                        writer.write("Purpose,Description,Active,Revoked,Expired,Total");
                        writer.newLine();

                        try (ResultSet rs = ps.executeQuery()) {
                            int rowCount = 0;
                            while (rs.next() && rowCount < MAX_EXPORT_ROWS) {
                                writer.write(escapeCsv(rs.getString("purpose_name")));
                                writer.write(',');
                                writer.write(escapeCsv(rs.getString("purpose_description")));
                                writer.write(',');
                                writer.write(Integer.toString(rs.getInt("active")));
                                writer.write(',');
                                writer.write(Integer.toString(rs.getInt("revoked")));
                                writer.write(',');
                                writer.write(Integer.toString(rs.getInt("expired")));
                                writer.write(',');
                                writer.write(Integer.toString(rs.getInt("total")));
                                writer.newLine();
                                rowCount++;
                            }
                        }
                        writer.flush();
                    } catch (java.sql.SQLException e) {
                        LOG.error(
                                "exportConsentSummary streaming failed for tenant {}", tenantId, e);
                        throw new java.io.IOException("Database error during CSV export", e);
                    }
                };

        return Response.ok(stream)
                .type("text/csv; charset=UTF-8")
                .header(
                        "Content-Disposition",
                        "attachment; filename=\"consent-summary-" + tenantId + ".csv\"")
                .build();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private String currentTenantId() {
        var identity = requestHolder.identity();
        return identity != null ? identity.tenantId() : null;
    }

    private Response unauthorized() {
        return Response.status(Response.Status.UNAUTHORIZED)
                .entity(ErrorResponse.of("UNAUTHORIZED", "No tenant context"))
                .build();
    }

    private static String escapeCsv(String value) {
        if (value == null) return "";
        // Protect against CSV formula injection (cells starting with =, +, -, @, tab, CR)
        if (!value.isEmpty()) {
            char first = value.charAt(0);
            if (first == '='
                    || first == '+'
                    || first == '-'
                    || first == '@'
                    || first == '\t'
                    || first == '\r') {
                value = "'" + value; // prefix with single-quote to neutralize formula
            }
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
