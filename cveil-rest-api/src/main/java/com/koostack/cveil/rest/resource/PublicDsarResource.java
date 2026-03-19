package com.koostack.cveil.rest.resource;

import com.koostack.cveil.rest.dto.CommonDtos.ErrorResponse;
import com.koostack.cveil.rest.dto.CommonDtos.MessageResponse;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Public (unauthenticated) DSAR submission endpoint.
 *
 * <p>Allows data subjects to submit their own data access/deletion requests without authentication.
 * Resolves tenant from the siteId (via banner_config) or tenantSlug parameter.
 */
@Path("/api/v1/public/dsar")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Public DSAR", description = "Self-service DSAR submission (unauthenticated)")
public class PublicDsarResource {

    private static final Logger LOG = LoggerFactory.getLogger(PublicDsarResource.class);
    private static final List<String> VALID_TYPES =
            List.of(
                    "ACCESS",
                    "DELETION",
                    "RECTIFICATION",
                    "PORTABILITY",
                    "RESTRICTION",
                    "OBJECTION");

    @Inject
    @Named("platformDataSource")
    jakarta.enterprise.inject.Instance<DataSource> dataSourceInstance;

    public record PublicDsarSubmission(
            @NotBlank String tenantSlug,
            @NotBlank @Email @Size(max = 512) String requesterEmail,
            @Size(max = 256) String requesterName,
            @NotBlank String requestType,
            @Size(max = 256) String subjectId,
            String notes) {}

    @POST
    @Path("/submit")
    @Operation(operationId = "publicDsarSubmit", summary = "Submit a DSAR request (self-service)")
    public Response submit(@Valid PublicDsarSubmission request) {
        if (!VALID_TYPES.contains(request.requestType().toUpperCase())) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ErrorResponse.validation("Invalid request type. Valid: " + VALID_TYPES))
                    .build();
        }

        try {
            DataSource ds = dataSourceInstance.get();
            String tenantId = resolveTenantFromSlug(ds, request.tenantSlug());
            if (tenantId == null) {
                LOG.debug("Public DSAR: unknown tenant slug '{}'", request.tenantSlug());
                return accepted202();
            }

            UUID id = UUID.randomUUID();
            Instant dueDate = Instant.now().plus(30, ChronoUnit.DAYS);

            try (var conn = ds.getConnection()) {
                // Per-email rate limit: max 5 DSAR submissions per email per 24h
                try (var rateLimitPs =
                        conn.prepareStatement(
                                "SELECT COUNT(*) FROM cveilconsent.dsar_request WHERE"
                                    + " requester_email = ? AND created_at > CURRENT_TIMESTAMP -"
                                    + " INTERVAL '24 hours'")) {
                    rateLimitPs.setString(1, request.requesterEmail());
                    try (var rs = rateLimitPs.executeQuery()) {
                        if (rs.next() && rs.getInt(1) >= 5) {
                            LOG.info(
                                    "Public DSAR: email rate limit exceeded for '{}'",
                                    request.requesterEmail());
                            return accepted202();
                        }
                    }
                }

                // Duplicate detection: same (tenant, email, type) within 24h
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
                            LOG.info(
                                    "Public DSAR: duplicate request for tenant={}, email={}",
                                    tenantId,
                                    request.requesterEmail());
                            return accepted202();
                        }
                    }
                }
                try (var ps =
                        conn.prepareStatement(
                                "INSERT INTO cveilconsent.dsar_request (dsar_request_uuid,"
                                    + " tenant_id, subject_id, requester_email, requester_name,"
                                    + " request_type, status, notes, due_date) VALUES (?, ?, ?, ?,"
                                    + " ?, ?, 'PENDING', ?, ?)")) {
                    ps.setObject(1, id);
                    ps.setString(2, tenantId);
                    ps.setString(3, request.subjectId());
                    ps.setString(4, request.requesterEmail());
                    ps.setString(5, request.requesterName());
                    ps.setString(6, request.requestType().toUpperCase());
                    ps.setString(7, request.notes());
                    ps.setTimestamp(8, Timestamp.from(dueDate));
                    ps.executeUpdate();
                }
                // Log activity
                try (var ps =
                        conn.prepareStatement(
                                "INSERT INTO cveilconsent.dsar_activity_log (dsar_activity_uuid,"
                                        + " tenant_id, request_uuid, action, performed_by, details)"
                                        + " VALUES (?, ?, ?, 'SUBMITTED', ?, 'Self-service"
                                        + " submission')")) {
                    ps.setObject(1, UUID.randomUUID());
                    ps.setString(2, tenantId);
                    ps.setObject(3, id);
                    ps.setString(4, request.requesterEmail());
                    ps.executeUpdate();
                }
            }

            LOG.info(
                    "Public DSAR submitted: id={}, type={}, tenant={}",
                    id,
                    request.requestType(),
                    tenantId);
            // Use 202 Accepted (same as unknown-tenant branch) to prevent enumeration
            return accepted202();
        } catch (Exception e) {
            LOG.error("Failed to submit public DSAR request", e);
            return accepted202();
        }
    }

    private static Response accepted202() {
        return Response.status(Response.Status.ACCEPTED)
                .entity(
                        MessageResponse.of(
                                "Your request has been received."
                                        + " You will receive a response within 30 days if"
                                        + " applicable."))
                .build();
    }

    private String resolveTenantFromSlug(DataSource ds, String slug) throws Exception {
        // Resolve tenant ID from the organization slug in the billing/IAM tables
        try (var conn = ds.getConnection();
                var ps =
                        conn.prepareStatement(
                                "SELECT tenant_id FROM cveilbilling.tenant WHERE slug = ?")) {
            ps.setString(1, slug);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("tenant_id") : null;
            }
        } catch (Exception e) {
            LOG.debug("Tenant lookup by slug failed, trying org name fallback", e);
            // Fallback: try looking up by tenant_id directly
            try (var conn = ds.getConnection();
                    var ps =
                            conn.prepareStatement(
                                    "SELECT tenant_id FROM cveilbilling.tenant WHERE tenant_id"
                                            + " = ?")) {
                ps.setString(1, slug);
                try (var rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString("tenant_id") : null;
                }
            }
        }
    }
}
