package com.koostack.cveil.rest.resource;

import com.koostack.cveil.rest.auth.Permissions;
import com.koostack.cveil.rest.config.CveilOpenApiConfig;
import com.koostack.cveil.rest.context.CveilRequestHolder;
import com.koostack.cveil.rest.dto.CommonDtos.ErrorResponse;
import com.koostack.cveil.rest.dto.CommonDtos.MessageResponse;
import com.koostack.cveil.rest.interceptor.TenantScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DSAR (Data Subject Access Request) workflow management endpoints.
 *
 * <p>Provides CRUD + workflow operations for managing GDPR/CCPA data subject requests. Supports
 * request intake, status tracking, admin notes, and auto-fulfillment via existing export/deletion
 * capabilities.
 */
@Path("/api/v1/dsar")
@TenantScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(ref = CveilOpenApiConfig.TAG_CONSENT)
@SecurityRequirement(name = CveilOpenApiConfig.SECURITY_SCHEME)
public class DsarResource {

    private static final Logger LOG = LoggerFactory.getLogger(DsarResource.class);

    private static final List<String> VALID_TYPES =
            List.of(
                    "ACCESS",
                    "DELETION",
                    "RECTIFICATION",
                    "PORTABILITY",
                    "RESTRICTION",
                    "OBJECTION");
    private static final List<String> VALID_STATUSES =
            List.of(
                    "PENDING",
                    "IN_REVIEW",
                    "APPROVED",
                    "IN_PROGRESS",
                    "COMPLETED",
                    "REJECTED",
                    "CANCELLED");

    /** Valid status transitions. Terminal states (COMPLETED, REJECTED, CANCELLED) have no exits. */
    private static final Map<String, java.util.Set<String>> ALLOWED_TRANSITIONS =
            Map.of(
                    "PENDING", java.util.Set.of("IN_REVIEW", "CANCELLED"),
                    "IN_REVIEW", java.util.Set.of("APPROVED", "REJECTED", "CANCELLED"),
                    "APPROVED", java.util.Set.of("IN_PROGRESS", "CANCELLED"),
                    "IN_PROGRESS", java.util.Set.of("COMPLETED", "CANCELLED"),
                    "COMPLETED", java.util.Set.of(),
                    "REJECTED", java.util.Set.of(),
                    "CANCELLED", java.util.Set.of());

    @Inject
    @Named("platformDataSource")
    jakarta.enterprise.inject.Instance<DataSource> dataSourceInstance;

    @Inject CveilRequestHolder requestHolder;

    // -- DTOs ---------------------------------------------------------------

    public record CreateDsarRequest(
            @Size(max = 256) String subjectId,
            @NotBlank @Email @Size(max = 512) String requesterEmail,
            @Size(max = 256) String requesterName,
            @NotBlank String requestType,
            String legalBasis,
            String notes) {}

    public record UpdateDsarStatusRequest(@NotBlank String status, String adminNotes) {}

    public record DsarRequestView(
            UUID id,
            String tenantId,
            String subjectId,
            String requesterEmail,
            String requesterName,
            String requestType,
            String status,
            String legalBasis,
            String notes,
            String adminNotes,
            Instant fulfilledAt,
            Instant dueDate,
            Instant createdAt,
            Instant updatedAt) {}

    public record DsarActivityView(
            UUID id,
            UUID requestId,
            String action,
            String performedBy,
            String details,
            Instant createdAt) {}

    public record DsarListResponse(List<DsarRequestView> requests, long total) {}

    // -- Endpoints ----------------------------------------------------------

    @GET
    @Path("/requests")
    @Operation(operationId = "listDsarRequests", summary = "List DSAR requests")
    public Response listRequests(
            @QueryParam("status") String statusFilter,
            @QueryParam("type") String typeFilter,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("pageSize") @DefaultValue("25") int pageSize) {
        Permissions.require(requestHolder.authorization(), Permissions.CONSENT_DSAR);
        String tenantId = currentTenantId();

        try {
            int clampedPage = Math.max(1, page);
            int clampedSize = Math.max(1, Math.min(pageSize, 100));
            int offset = (clampedPage - 1) * clampedSize;

            DataSource ds = dataSourceInstance.get();
            List<String> conditions = new ArrayList<>();
            List<Object> params = new ArrayList<>();
            conditions.add("tenant_id = ?");
            params.add(tenantId);

            if (statusFilter != null && !statusFilter.isBlank()) {
                conditions.add("status = ?");
                params.add(statusFilter.toUpperCase());
            }
            if (typeFilter != null && !typeFilter.isBlank()) {
                conditions.add("request_type = ?");
                params.add(typeFilter.toUpperCase());
            }

            String where = String.join(" AND ", conditions);

            long total;
            List<DsarRequestView> requests = new ArrayList<>();

            try (var conn = ds.getConnection()) {
                try (var ps =
                        conn.prepareStatement(
                                "SELECT COUNT(*) FROM cveilconsent.dsar_request WHERE " + where)) {
                    for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
                    try (var rs = ps.executeQuery()) {
                        total = rs.next() ? rs.getLong(1) : 0;
                    }
                }
                try (var ps =
                        conn.prepareStatement(
                                "SELECT dsar_request_uuid, tenant_id, subject_id, requester_email,"
                                    + " requester_name, request_type, status, legal_basis, notes,"
                                    + " admin_notes, fulfilled_at, due_date, created_at, updated_at"
                                    + " FROM cveilconsent.dsar_request WHERE "
                                        + where
                                        + " ORDER BY created_at DESC LIMIT ? OFFSET ?")) {
                    for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
                    ps.setInt(params.size() + 1, clampedSize);
                    ps.setInt(params.size() + 2, offset);
                    try (var rs = ps.executeQuery()) {
                        while (rs.next()) {
                            requests.add(mapRequest(rs));
                        }
                    }
                }
            }
            return Response.ok(new DsarListResponse(requests, total)).build();
        } catch (Exception e) {
            LOG.error("Failed to list DSAR requests for tenant={}", tenantId, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ErrorResponse.internal("Failed to list DSAR requests"))
                    .build();
        }
    }

    @GET
    @Path("/requests/{id}")
    @Operation(operationId = "getDsarRequest", summary = "Get a DSAR request by ID")
    public Response getRequest(@PathParam("id") UUID requestId) {
        Permissions.require(requestHolder.authorization(), Permissions.CONSENT_DSAR);
        String tenantId = currentTenantId();

        try {
            DataSource ds = dataSourceInstance.get();
            try (var conn = ds.getConnection();
                    var ps =
                            conn.prepareStatement(
                                    "SELECT dsar_request_uuid, tenant_id, subject_id,"
                                        + " requester_email, requester_name, request_type, status,"
                                        + " legal_basis, notes, admin_notes, fulfilled_at,"
                                        + " due_date, created_at, updated_at FROM"
                                        + " cveilconsent.dsar_request WHERE dsar_request_uuid = ?"
                                        + " AND tenant_id = ?")) {
                ps.setObject(1, requestId);
                ps.setString(2, tenantId);
                try (var rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Response.status(Response.Status.NOT_FOUND)
                                .entity(ErrorResponse.of("NOT_FOUND", "DSAR request not found"))
                                .build();
                    }
                    return Response.ok(mapRequest(rs)).build();
                }
            }
        } catch (Exception e) {
            LOG.error("Failed to get DSAR request {}", requestId, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ErrorResponse.internal("Failed to get DSAR request"))
                    .build();
        }
    }

    @POST
    @Path("/requests")
    @Operation(
            operationId = "createDsarRequest",
            summary = "Create a DSAR request (admin-initiated)")
    public Response createRequest(@Valid CreateDsarRequest request) {
        Permissions.require(requestHolder.authorization(), Permissions.CONSENT_DSAR);
        String tenantId = currentTenantId();

        if (!VALID_TYPES.contains(request.requestType().toUpperCase())) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ErrorResponse.validation("Invalid request type. Valid: " + VALID_TYPES))
                    .build();
        }

        try {
            UUID id = UUID.randomUUID();
            Instant dueDate = Instant.now().plus(30, ChronoUnit.DAYS);
            DataSource ds = dataSourceInstance.get();

            try (var conn = ds.getConnection()) {
                // Duplicate detection: reject if same (tenant, email, type) within 24h
                try (var dupCheck =
                        conn.prepareStatement(
                                "SELECT 1 FROM cveilconsent.dsar_request WHERE tenant_id = ? AND"
                                    + " requester_email = ? AND request_type = ? AND created_at >"
                                    + " CURRENT_TIMESTAMP - INTERVAL '24 hours' AND status NOT IN"
                                    + " ('REJECTED', 'CANCELLED') LIMIT 1")) {
                    dupCheck.setString(1, tenantId);
                    dupCheck.setString(2, request.requesterEmail());
                    dupCheck.setString(3, request.requestType().toUpperCase());
                    try (var rs = dupCheck.executeQuery()) {
                        if (rs.next()) {
                            return Response.status(Response.Status.CONFLICT)
                                    .entity(
                                            ErrorResponse.of(
                                                    "DUPLICATE_REQUEST",
                                                    "A similar DSAR request was already submitted"
                                                            + " within the last 24 hours"))
                                    .build();
                        }
                    }
                }

                try (var ps =
                        conn.prepareStatement(
                                "INSERT INTO cveilconsent.dsar_request (dsar_request_uuid,"
                                    + " tenant_id, subject_id, requester_email, requester_name,"
                                    + " request_type, status, legal_basis, notes, due_date) VALUES"
                                    + " (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?)")) {
                    ps.setObject(1, id);
                    ps.setString(2, tenantId);
                    ps.setString(3, request.subjectId());
                    ps.setString(4, request.requesterEmail());
                    ps.setString(5, request.requesterName());
                    ps.setString(6, request.requestType().toUpperCase());
                    ps.setString(7, request.legalBasis());
                    ps.setString(8, request.notes());
                    ps.setTimestamp(9, Timestamp.from(dueDate));
                    ps.executeUpdate();
                }
                logActivity(
                        conn,
                        id,
                        "CREATED",
                        currentActor(),
                        "Request created: " + request.requestType());
            }

            LOG.info(
                    "DSAR request created: id={}, type={}, tenant={}",
                    id,
                    request.requestType(),
                    tenantId);
            return Response.status(Response.Status.CREATED)
                    .entity(MessageResponse.of("DSAR request created: " + id))
                    .build();
        } catch (Exception e) {
            LOG.error("Failed to create DSAR request for tenant={}", tenantId, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ErrorResponse.internal("Failed to create DSAR request"))
                    .build();
        }
    }

    @PUT
    @Path("/requests/{id}/status")
    @Operation(operationId = "updateDsarStatus", summary = "Update DSAR request status")
    public Response updateStatus(
            @PathParam("id") UUID requestId, @Valid UpdateDsarStatusRequest request) {
        Permissions.require(requestHolder.authorization(), Permissions.CONSENT_DSAR);
        String tenantId = currentTenantId();

        String status = request.status().toUpperCase();
        if (!VALID_STATUSES.contains(status)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ErrorResponse.validation("Invalid status. Valid: " + VALID_STATUSES))
                    .build();
        }

        try {
            DataSource ds = dataSourceInstance.get();
            try (var conn = ds.getConnection()) {
                // Fetch current status to validate the transition
                String currentStatus = null;
                try (var fetchPs =
                        conn.prepareStatement(
                                "SELECT status FROM cveilconsent.dsar_request"
                                        + " WHERE dsar_request_uuid = ? AND tenant_id = ?")) {
                    fetchPs.setObject(1, requestId);
                    fetchPs.setString(2, tenantId);
                    try (var rs = fetchPs.executeQuery()) {
                        if (rs.next()) {
                            currentStatus = rs.getString("status");
                        }
                    }
                }
                if (currentStatus == null) {
                    return Response.status(Response.Status.NOT_FOUND)
                            .entity(ErrorResponse.of("NOT_FOUND", "DSAR request not found"))
                            .build();
                }

                java.util.Set<String> allowed =
                        ALLOWED_TRANSITIONS.getOrDefault(currentStatus, java.util.Set.of());
                if (!allowed.contains(status)) {
                    return Response.status(Response.Status.CONFLICT)
                            .entity(
                                    ErrorResponse.validation(
                                            "Cannot transition from "
                                                    + currentStatus
                                                    + " to "
                                                    + status
                                                    + ". Allowed: "
                                                    + allowed))
                            .build();
                }

                String sql =
                        "COMPLETED".equals(status)
                                ? "UPDATE cveilconsent.dsar_request SET status = ?, admin_notes ="
                                        + " COALESCE(?, admin_notes), fulfilled_at ="
                                        + " CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE"
                                        + " dsar_request_uuid = ? AND tenant_id = ?"
                                : "UPDATE cveilconsent.dsar_request SET status = ?, admin_notes ="
                                      + " COALESCE(?, admin_notes), updated_at = CURRENT_TIMESTAMP"
                                      + " WHERE dsar_request_uuid = ? AND tenant_id = ?";

                try (var ps = conn.prepareStatement(sql)) {
                    ps.setString(1, status);
                    ps.setString(2, request.adminNotes());
                    ps.setObject(3, requestId);
                    ps.setString(4, tenantId);
                    int updated = ps.executeUpdate();
                    if (updated == 0) {
                        return Response.status(Response.Status.NOT_FOUND)
                                .entity(ErrorResponse.of("NOT_FOUND", "DSAR request not found"))
                                .build();
                    }
                }
                logActivity(
                        conn,
                        requestId,
                        "STATUS_CHANGED",
                        currentActor(),
                        "Status changed to " + status);
            }
            return Response.ok(MessageResponse.of("Status updated to " + status)).build();
        } catch (Exception e) {
            LOG.error("Failed to update DSAR request {} status", requestId, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ErrorResponse.internal("Failed to update DSAR request status"))
                    .build();
        }
    }

    @GET
    @Path("/requests/{id}/activity")
    @Operation(operationId = "getDsarActivity", summary = "Get activity log for a DSAR request")
    public Response getActivity(@PathParam("id") UUID requestId) {
        Permissions.require(requestHolder.authorization(), Permissions.CONSENT_DSAR);
        String tenantId = currentTenantId();

        try {
            DataSource ds = dataSourceInstance.get();
            List<DsarActivityView> activities = new ArrayList<>();

            try (var conn = ds.getConnection();
                    var ps =
                            conn.prepareStatement(
                                    "SELECT a.dsar_activity_uuid, a.request_uuid, a.action,"
                                        + " a.performed_by, a.details, a.created_at FROM"
                                        + " cveilconsent.dsar_activity_log a JOIN"
                                        + " cveilconsent.dsar_request r ON r.dsar_request_uuid ="
                                        + " a.request_uuid WHERE a.request_uuid = ? AND r.tenant_id"
                                        + " = ? ORDER BY a.created_at DESC")) {
                ps.setObject(1, requestId);
                ps.setString(2, tenantId);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        activities.add(
                                new DsarActivityView(
                                        UUID.fromString(rs.getString("dsar_activity_uuid")),
                                        UUID.fromString(rs.getString("request_uuid")),
                                        rs.getString("action"),
                                        rs.getString("performed_by"),
                                        rs.getString("details"),
                                        rs.getTimestamp("created_at").toInstant()));
                    }
                }
            }
            return Response.ok(activities).build();
        } catch (Exception e) {
            LOG.error("Failed to get DSAR activity for request {}", requestId, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ErrorResponse.internal("Failed to get activity log"))
                    .build();
        }
    }

    @GET
    @Path("/transitions")
    @Operation(
            operationId = "getDsarTransitions",
            summary = "Get allowed status transitions per status")
    public Response getTransitions() {
        Permissions.require(requestHolder.authorization(), Permissions.CONSENT_DSAR);
        return Response.ok(ALLOWED_TRANSITIONS).build();
    }

    @GET
    @Path("/summary")
    @Operation(operationId = "getDsarSummary", summary = "Get DSAR request summary statistics")
    public Response getSummary() {
        Permissions.require(requestHolder.authorization(), Permissions.CONSENT_DSAR);
        String tenantId = currentTenantId();

        try {
            DataSource ds = dataSourceInstance.get();
            try (var conn = ds.getConnection();
                    var ps =
                            conn.prepareStatement(
                                    "SELECT status, COUNT(*) as cnt"
                                            + " FROM cveilconsent.dsar_request"
                                            + " WHERE tenant_id = ?"
                                            + " GROUP BY status")) {
                ps.setString(1, tenantId);
                try (var rs = ps.executeQuery()) {
                    List<Object> statusCounts = new ArrayList<>();
                    while (rs.next()) {
                        statusCounts.add(
                                java.util.Map.of(
                                        "status", rs.getString("status"),
                                        "count", rs.getLong("cnt")));
                    }
                    return Response.ok(java.util.Map.of("statuses", statusCounts)).build();
                }
            }
        } catch (Exception e) {
            LOG.error("Failed to get DSAR summary for tenant={}", tenantId, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ErrorResponse.internal("Failed to get DSAR summary"))
                    .build();
        }
    }

    // -- Helpers ------------------------------------------------------------

    private void logActivity(
            java.sql.Connection conn,
            UUID requestId,
            String action,
            String performedBy,
            String details)
            throws Exception {
        try (var ps =
                conn.prepareStatement(
                        "INSERT INTO cveilconsent.dsar_activity_log (dsar_activity_uuid,"
                                + " tenant_id, request_uuid, action, performed_by, details)"
                                + " VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setString(2, currentTenantId());
            ps.setObject(3, requestId);
            ps.setString(4, action);
            ps.setString(5, performedBy);
            ps.setString(6, details);
            ps.executeUpdate();
        }
    }

    private String currentTenantId() {
        return requestHolder.identity() != null ? requestHolder.identity().tenantId() : null;
    }

    private String currentActor() {
        try {
            return requestHolder.identity() != null ? requestHolder.identity().subject() : "system";
        } catch (Exception e) {
            return "system";
        }
    }

    private DsarRequestView mapRequest(java.sql.ResultSet rs) throws Exception {
        return new DsarRequestView(
                UUID.fromString(rs.getString("dsar_request_uuid")),
                rs.getString("tenant_id"),
                rs.getString("subject_id"),
                rs.getString("requester_email"),
                rs.getString("requester_name"),
                rs.getString("request_type"),
                rs.getString("status"),
                rs.getString("legal_basis"),
                rs.getString("notes"),
                rs.getString("admin_notes"),
                toInstant(rs.getTimestamp("fulfilled_at")),
                toInstant(rs.getTimestamp("due_date")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }
}
