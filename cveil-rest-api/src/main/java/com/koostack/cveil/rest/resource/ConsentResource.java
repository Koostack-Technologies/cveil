package com.koostack.cveil.rest.resource;

import com.koostack.cveil.core.cqrs.CommandDispatcher;
import com.koostack.cveil.core.cqrs.QueryDispatcher;
import com.koostack.cveil.core.models.BatchConsentEntry;
import com.koostack.cveil.core.models.BatchConsentResult;
import com.koostack.cveil.core.models.ConsentResponse;
import com.koostack.cveil.core.models.ConsentScope;
import com.koostack.cveil.core.models.PurposeView;
import com.koostack.cveil.core.models.RequestContext;
import com.koostack.cveil.core.models.SubjectConsentView;
import com.koostack.cveil.core.models.SubjectView;
import com.koostack.cveil.core.models.TenantContext;
import com.koostack.cveil.plugin.consent.jooq.CountSubjectCacheKeysQueryActuator;
import com.koostack.cveil.plugin.consent.jooq.DeleteSubjectAuditCommandActuator;
import com.koostack.cveil.plugin.consent.jooq.DeleteSubjectCacheKeysQueryActuator;
import com.koostack.cveil.plugin.consent.jooq.EvaluateConsentQueryActuator;
import com.koostack.cveil.plugin.consent.jooq.FindPolicyQueryActuator;
import com.koostack.cveil.plugin.consent.jooq.GetDsarAuditLogQueryActuator;
import com.koostack.cveil.plugin.consent.jooq.GetSubjectConsentQueryActuator;
import com.koostack.cveil.plugin.consent.jooq.GetSubjectQueryActuator;
import com.koostack.cveil.plugin.consent.jooq.GrantConsentCommandActuator;
import com.koostack.cveil.plugin.consent.jooq.ListSubjectsQueryActuator;
import com.koostack.cveil.plugin.consent.jooq.RegisterSubjectCommandActuator;
import com.koostack.cveil.plugin.consent.jooq.RemoveSubjectCommandActuator;
import com.koostack.cveil.plugin.consent.jooq.RevokeAllConsentCommandActuator;
import com.koostack.cveil.plugin.consent.jooq.RevokeConsentCommandActuator;
import com.koostack.cveil.plugin.consent.jooq.ShredSubjectCommandActuator;
import com.koostack.cveil.plugin.consent.jooq.UpdateSubjectCommandActuator;
import com.koostack.cveil.rest.auth.Permissions;
import com.koostack.cveil.rest.config.CveilConfig;
import com.koostack.cveil.rest.config.CveilOpenApiConfig;
import com.koostack.cveil.rest.context.CveilRequestHolder;
import com.koostack.cveil.rest.dto.AdminDtos.RegisterSubjectRequest;
import com.koostack.cveil.rest.dto.AdminDtos.UpdateSubjectRequest;
import com.koostack.cveil.rest.dto.CommonDtos.ErrorResponse;
import com.koostack.cveil.rest.dto.CommonDtos.MessageResponse;
import com.koostack.cveil.rest.dto.ConsentDtos;
import com.koostack.cveil.rest.dto.ConsentDtos.BatchConsentResponse;
import com.koostack.cveil.rest.dto.ConsentDtos.BatchGrantRequest;
import com.koostack.cveil.rest.dto.ConsentDtos.BatchRevokeRequest;
import com.koostack.cveil.rest.dto.ConsentDtos.DsarDeletionReceipt;
import com.koostack.cveil.rest.dto.ConsentDtos.EvaluateRequest;
import com.koostack.cveil.rest.dto.ConsentDtos.EvaluateResponse;
import com.koostack.cveil.rest.dto.ConsentDtos.GrantRequest;
import com.koostack.cveil.rest.dto.ConsentDtos.RevokeRequest;
import com.koostack.cveil.rest.dto.ConsentDtos.SubjectDataExport;
import com.koostack.cveil.rest.dto.ConsentScopeDto;
import com.koostack.cveil.rest.interceptor.TenantScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
 * JAX-RS resource for consent operations: grant, revoke, evaluate, batch, subject summary, and
 * audit history.
 *
 * <p>All endpoints require authentication (enforced by ApiKeyAuthFilter) and operate within a
 * tenant scope (provided by the TenantScoped CDI interceptor binding).
 *
 * <h2>RBAC Enforcement</h2>
 *
 * <ul>
 *   <li>Read-only endpoints (evaluate, subject summary, subject history) require the {@link
 *       Roles#CONSENT_READ consent:read} role.
 *   <li>Mutating endpoints (grant, revoke, grant-batch, revoke-batch) require the {@link
 *       Roles#CONSENT_WRITE consent:write} role.
 *   <li>The {@link Roles#SUPER_ADMIN super-admin} role bypasses all checks.
 * </ul>
 *
 * @see Roles
 */
@Path("/api/v1/subjects")
@TenantScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(ref = CveilOpenApiConfig.TAG_CONSENT)
@SecurityRequirement(name = CveilOpenApiConfig.SECURITY_SCHEME)
public class ConsentResource {

    private static final Logger LOG = LoggerFactory.getLogger(ConsentResource.class);

    @Inject
    @Named("consentDispatcher")
    CommandDispatcher consentDispatcher;

    @Inject
    @Named("consentQueryDispatcher")
    QueryDispatcher consentQueryDispatcher;

    @Inject CveilConfig cveilConfig;

    @Inject CveilRequestHolder requestHolder;

    @Inject com.koostack.cveil.rest.util.MetricsHelper metrics;

    // -------------------------------------------------------------------
    // Mutating endpoints -- require consent:write
    // -------------------------------------------------------------------

    @POST
    @Path("/consent/grant")
    @Operation(
            operationId = "grantConsent",
            summary = "Grant consent for a single scope",
            description =
                    "Records a consent grant for the specified subject and scope. "
                            + "Requires the consent:write role. If the consent already exists, "
                            + "this is an idempotent update (e.g. refreshing expiration).")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Consent granted successfully",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = MessageResponse.class))),
        @APIResponse(
                responseCode = "400",
                description = "Validation error (missing or invalid fields)",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = ErrorResponse.class))),
        @APIResponse(
                responseCode = "403",
                description = "Missing required role (consent:write)",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = ErrorResponse.class))),
        @APIResponse(
                responseCode = "429",
                description = "Rate limit exceeded",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = ErrorResponse.class))),
    })
    public Response grant(@Context SecurityContext securityContext, @Valid GrantRequest request) {
        Permissions.require(requestHolder.authorization(), Permissions.CONSENT_GRANT);

        LOG.debug(
                "POST /subjects/consent/grant - subject={}, scope={}",
                request.subject(),
                request.scope());

        ConsentScope scope = request.scope().toModel();
        var policy = resolvePolicy(scope);
        consentDispatcher.dispatch(
                new GrantConsentCommandActuator.GrantConsentCommand(
                        request.subject(),
                        policy.policyId(),
                        scope.purposeName(),
                        policy.policyType(),
                        policy.field() != null ? policy.field().fieldName() : null,
                        policy.field() != null ? policy.field().entityName() : null,
                        policy.category() != null ? policy.category().categoryName() : null,
                        RequestContext.actor(),
                        RequestContext.ipAddress(),
                        RequestContext.userAgent(),
                        request.expiresAt()));

        metrics.counter(
                "consent_grants", currentTenantId(), Map.of("purpose", scope.purposeName()));
        return Response.ok(MessageResponse.of("Consent granted")).build();
    }

    @POST
    @Path("/consent/revoke")
    @Operation(
            operationId = "revokeConsent",
            summary = "Revoke consent for a single scope",
            description =
                    "Records a consent revocation for the specified subject and scope. "
                            + "Requires the consent:write role.")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Consent revoked successfully",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = MessageResponse.class))),
        @APIResponse(
                responseCode = "400",
                description = "Validation error",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = ErrorResponse.class))),
        @APIResponse(
                responseCode = "403",
                description = "Missing required role (consent:write)",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = ErrorResponse.class))),
        @APIResponse(
                responseCode = "429",
                description = "Rate limit exceeded",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = ErrorResponse.class))),
    })
    public Response revoke(@Context SecurityContext securityContext, @Valid RevokeRequest request) {
        Permissions.require(requestHolder.authorization(), Permissions.CONSENT_REVOKE);

        LOG.debug(
                "POST /subjects/consent/revoke - subject={}, scope={}",
                request.subject(),
                request.scope());

        ConsentScope scope = request.scope().toModel();
        var policy = resolvePolicy(scope);
        consentDispatcher.dispatch(
                new RevokeConsentCommandActuator.RevokeConsentCommand(
                        request.subject(),
                        policy.policyId(),
                        scope.purposeName(),
                        policy.policyType(),
                        policy.field() != null ? policy.field().fieldName() : null,
                        policy.field() != null ? policy.field().entityName() : null,
                        policy.category() != null ? policy.category().categoryName() : null,
                        RequestContext.actor(),
                        RequestContext.ipAddress(),
                        RequestContext.userAgent()));

        metrics.counter(
                "consent_revocations", currentTenantId(), Map.of("purpose", scope.purposeName()));
        return Response.ok(MessageResponse.of("Consent revoked")).build();
    }

    @POST
    @Path("/consent/grant-all")
    @Operation(
            operationId = "grantAllConsent",
            summary = "Grant consent for multiple scopes in a single request",
            description =
                    "Grants consent for the given subject across multiple scopes. "
                            + "Each grant is independently atomic -- partial success is possible. "
                            + "Requires the consent:write role.")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Batch result with succeeded/failed counts",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = BatchConsentResponse.class))),
        @APIResponse(
                responseCode = "400",
                description = "Validation error",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = ErrorResponse.class))),
        @APIResponse(
                responseCode = "403",
                description = "Missing required role (consent:write)",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = ErrorResponse.class))),
        @APIResponse(
                responseCode = "429",
                description = "Rate limit exceeded",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = ErrorResponse.class))),
    })
    public Response grantAll(
            @Context SecurityContext securityContext, @Valid BatchGrantRequest request) {
        Permissions.require(requestHolder.authorization(), Permissions.CONSENT_GRANT);

        LOG.debug(
                "POST /subjects/consent/grant-all - subject={}, count={}",
                request.subject(),
                request.grants() != null ? request.grants().size() : 0);

        List<BatchConsentEntry> entries =
                request.grants().stream().map(dto -> dto.toModel()).toList();

        int succeeded = 0;
        List<BatchConsentResult.BatchItemError> errors = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            BatchConsentEntry entry = entries.get(i);
            if (entry == null || entry.scope() == null) {
                errors.add(
                        new BatchConsentResult.BatchItemError(
                                i,
                                null,
                                entry == null
                                        ? "Null entry in batch"
                                        : "Null scope in batch entry"));
                continue;
            }
            try {
                var policy = resolvePolicy(entry.scope());
                consentDispatcher.dispatch(
                        new GrantConsentCommandActuator.GrantConsentCommand(
                                request.subject(),
                                policy.policyId(),
                                entry.scope().purposeName(),
                                policy.policyType(),
                                policy.field() != null ? policy.field().fieldName() : null,
                                policy.field() != null ? policy.field().entityName() : null,
                                policy.category() != null ? policy.category().categoryName() : null,
                                RequestContext.actor(),
                                RequestContext.ipAddress(),
                                RequestContext.userAgent(),
                                entry.expiresAt()));
                succeeded++;
            } catch (Exception e) {
                errors.add(new BatchConsentResult.BatchItemError(i, entry.scope(), e.getMessage()));
            }
        }
        BatchConsentResult result =
                new BatchConsentResult(succeeded, errors.size(), List.copyOf(errors));

        metrics.counter("consent_grant_all", currentTenantId());
        return Response.ok(BatchConsentResponse.fromModel(result)).build();
    }

    @POST
    @Path("/consent/revoke-all")
    @Operation(
            operationId = "revokeAllConsent",
            summary = "Revoke consent for multiple scopes in a single request",
            description =
                    "Revokes consent for the given subject across multiple scopes. Each revocation"
                        + " is independently atomic -- partial success is possible. Requires the"
                        + " consent:write role.")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Batch result with succeeded/failed counts",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = BatchConsentResponse.class))),
        @APIResponse(
                responseCode = "400",
                description = "Validation error",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = ErrorResponse.class))),
        @APIResponse(
                responseCode = "403",
                description = "Missing required role (consent:write)",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = ErrorResponse.class))),
        @APIResponse(
                responseCode = "429",
                description = "Rate limit exceeded",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = ErrorResponse.class))),
    })
    public Response revokeAll(
            @Context SecurityContext securityContext, @Valid BatchRevokeRequest request) {
        Permissions.require(requestHolder.authorization(), Permissions.CONSENT_REVOKE);

        LOG.debug(
                "POST /subjects/consent/revoke-all - subject={}, count={}",
                request.subject(),
                request.revokes() != null ? request.revokes().size() : 0);

        List<BatchConsentEntry> entries =
                request.revokes().stream().map(dto -> dto.toModel()).toList();

        int succeeded = 0;
        List<BatchConsentResult.BatchItemError> errors = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            BatchConsentEntry entry = entries.get(i);
            if (entry == null || entry.scope() == null) {
                errors.add(
                        new BatchConsentResult.BatchItemError(
                                i,
                                null,
                                entry == null
                                        ? "Null entry in batch"
                                        : "Null scope in batch entry"));
                continue;
            }
            try {
                var policy = resolvePolicy(entry.scope());
                consentDispatcher.dispatch(
                        new RevokeConsentCommandActuator.RevokeConsentCommand(
                                request.subject(),
                                policy.policyId(),
                                entry.scope().purposeName(),
                                policy.policyType(),
                                policy.field() != null ? policy.field().fieldName() : null,
                                policy.field() != null ? policy.field().entityName() : null,
                                policy.category() != null ? policy.category().categoryName() : null,
                                RequestContext.actor(),
                                RequestContext.ipAddress(),
                                RequestContext.userAgent()));
                succeeded++;
            } catch (Exception e) {
                errors.add(new BatchConsentResult.BatchItemError(i, entry.scope(), e.getMessage()));
            }
        }
        BatchConsentResult result =
                new BatchConsentResult(succeeded, errors.size(), List.copyOf(errors));

        metrics.counter("consent_revoke_all", currentTenantId());
        return Response.ok(BatchConsentResponse.fromModel(result)).build();
    }

    // -------------------------------------------------------------------
    // Read-only endpoints -- require consent:read
    // -------------------------------------------------------------------

    @POST
    @Path("/{subjectId}/evaluate")
    @Operation(
            operationId = "evaluateConsent",
            summary = "Evaluate consent for a subject against a set of scopes",
            description =
                    "Returns which scopes are granted, denied, with reasons and "
                            + "structured decision details. Requires the consent:read role.")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Evaluation result with granted/denied sets and details",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = EvaluateResponse.class))),
        @APIResponse(
                responseCode = "400",
                description = "Validation error",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = ErrorResponse.class))),
        @APIResponse(
                responseCode = "403",
                description = "Missing required role (consent:read)",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = ErrorResponse.class))),
        @APIResponse(
                responseCode = "429",
                description = "Rate limit exceeded",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = ErrorResponse.class))),
    })
    public Response evaluate(
            @Context SecurityContext securityContext,
            @PathParam("subjectId") String subjectId,
            @Valid EvaluateRequest request) {
        Permissions.require(requestHolder.authorization(), Permissions.CONSENT_EVALUATE);

        LOG.debug(
                "POST /subjects/{}/evaluate - scopeCount={}",
                subjectId,
                request.scopes() != null ? request.scopes().size() : 0);

        Set<ConsentScope> coreScopes =
                request.scopes().stream().map(ConsentScopeDto::toModel).collect(Collectors.toSet());

        ConsentResponse response =
                consentQueryDispatcher.dispatch(
                        new EvaluateConsentQueryActuator.EvaluateConsentQuery(
                                subjectId, coreScopes));

        metrics.counter("consent_evaluations", currentTenantId());
        return Response.ok(EvaluateResponse.fromModel(response)).build();
    }

    @GET
    @Path("/{id}/summary")
    @Operation(
            operationId = "getSubjectSummary",
            summary = "List all consent policies for a subject",
            description =
                    "Returns every active consent policy for the given data subject. "
                            + "Requires the consent:read role.")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "List of consent policy summaries",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema =
                                        @Schema(
                                                type = SchemaType.ARRAY,
                                                implementation = SubjectConsentView.class))),
        @APIResponse(
                responseCode = "403",
                description = "Missing required role (consent:read)",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = ErrorResponse.class))),
        @APIResponse(
                responseCode = "429",
                description = "Rate limit exceeded",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = ErrorResponse.class))),
    })
    public Response subjectSummary(
            @Context SecurityContext securityContext,
            @Parameter(
                            description = "The data subject identifier (e.g. user ID)",
                            required = true,
                            example = "user-12345")
                    @PathParam("id")
                    String subjectId) {
        Permissions.require(requestHolder.authorization(), Permissions.CONSENT_READ);

        LOG.debug("GET /subjects/{}/summary", subjectId);

        SubjectConsentView consent =
                consentQueryDispatcher.dispatch(
                        new GetSubjectConsentQueryActuator.GetSubjectConsentQuery(subjectId));

        return Response.ok(consent).build();
    }

    @GET
    @Path("/{id}/history")
    @Operation(
            operationId = "getSubjectHistory",
            summary = "Get current consent state for a subject",
            description =
                    "Returns the current consent ledger state for the given data subject. The event"
                            + " stream serves as the historical record. Requires the consent:read"
                            + " role.")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Subject consent state",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = SubjectConsentView.class))),
        @APIResponse(
                responseCode = "403",
                description = "Missing required role (consent:read)",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = ErrorResponse.class))),
        @APIResponse(
                responseCode = "429",
                description = "Rate limit exceeded",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = ErrorResponse.class))),
    })
    public Response subjectHistory(
            @Context SecurityContext securityContext,
            @Parameter(
                            description = "The data subject identifier (e.g. user ID)",
                            required = true,
                            example = "user-12345")
                    @PathParam("id")
                    String subjectId) {
        Permissions.require(requestHolder.authorization(), Permissions.CONSENT_READ);

        LOG.debug("GET /subjects/{}/history", subjectId);

        // In the new schema, the event stream is the historical record.
        // The consent ledger shows current state per subject.
        SubjectConsentView consent =
                consentQueryDispatcher.dispatch(
                        new GetSubjectConsentQueryActuator.GetSubjectConsentQuery(subjectId));
        return Response.ok(consent).build();
    }

    // -------------------------------------------------------------------
    // DSAR (Data Subject Access Request) endpoints
    // -------------------------------------------------------------------

    @GET
    @Path("/{id}/export")
    @Operation(
            operationId = "exportSubjectData",
            summary = "Export all data for a subject (GDPR Article 20)",
            description =
                    "Returns a complete export of all consent-related data held for a subject,"
                            + " including active policies, audit trail entries, and cached consent"
                            + " keys. Requires the consent:write role.")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Data export successful",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = SubjectDataExport.class))),
        @APIResponse(
                responseCode = "403",
                description =
                        "Missing required role (consent:write) or missing tenant context "
                                + "when multi-tenant mode is enabled",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = ErrorResponse.class))),
        @APIResponse(
                responseCode = "500",
                description = "Export failed",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = ErrorResponse.class))),
    })
    public Response exportSubjectData(
            @Context SecurityContext securityContext,
            @Parameter(
                            description = "The data subject identifier",
                            required = true,
                            example = "user_123")
                    @PathParam("id")
                    String subjectId) {
        Permissions.require(requestHolder.authorization(), Permissions.CONSENT_EXPORT);

        if (subjectId == null || subjectId.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ErrorResponse.validation("subjectId must not be blank"))
                    .build();
        }

        // Multi-tenant guard: if enabled, require an active TenantContext
        if (cveilConfig.isMultiTenantEnabled()) {
            if (!TenantContext.isPresent()) {
                LOG.warn("DSAR export denied: TenantContext is required but not set");
                return Response.status(Response.Status.FORBIDDEN)
                        .entity(
                                ErrorResponse.of(
                                        "TENANT_CONTEXT_REQUIRED",
                                        "This operation requires an active tenant context"))
                        .build();
            }
            LOG.debug("DSAR export scoped to tenant: {}", TenantContext.get());
        }

        LOG.debug("GET /subjects/{}/export", subjectId);

        try {
            SubjectConsentView consent =
                    consentQueryDispatcher.dispatch(
                            new GetSubjectConsentQueryActuator.GetSubjectConsentQuery(subjectId));

            long cachedKeyCount =
                    consentQueryDispatcher.dispatch(
                            new CountSubjectCacheKeysQueryActuator.CountSubjectCacheKeysQuery(
                                    subjectId));

            SubjectDataExport export =
                    new SubjectDataExport(subjectId, consent, cachedKeyCount, Instant.now());

            LOG.info(
                    "Exported data for subject: {} ({} ledger entries)",
                    subjectId,
                    consent.entries().size());

            metrics.counter("dsar_exports", currentTenantId());
            return Response.ok(export).build();
        } catch (Exception e) {
            LOG.error("Failed to export data for subject: {}", subjectId, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ErrorResponse.internal("Failed to export subject data"))
                    .build();
        }
    }

    @GET
    @Path("/{id}/audit")
    @Operation(
            operationId = "getSubjectDsarAuditLog",
            summary = "Get DSAR audit log for a subject",
            description =
                    "Returns the DSAR audit log entries for a subject, ordered by most recent"
                            + " first. Each entry records a past erasure request with metadata"
                            + " (policies deleted, cached keys deleted, status, actor, etc.).")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Audit log returned",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema =
                                        @Schema(
                                                implementation =
                                                        ConsentDtos.DsarAuditLogResponse.class))),
        @APIResponse(
                responseCode = "500",
                description = "Failed to retrieve audit log",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = ErrorResponse.class))),
    })
    public Response getSubjectDsarAuditLog(
            @Context SecurityContext securityContext,
            @Parameter(description = "The data subject identifier", required = true)
                    @PathParam("id")
                    String subjectId) {
        Permissions.require(requestHolder.authorization(), Permissions.CONSENT_READ);

        try {
            @SuppressWarnings("unchecked")
            List<com.koostack.cveil.plugin.consent.jooq.generated.tables.pojos.DsarAuditLog>
                    entries =
                            consentQueryDispatcher.dispatch(
                                    new GetDsarAuditLogQueryActuator.GetDsarAuditLogQuery(
                                            subjectId));

            List<ConsentDtos.DsarAuditEntry> dtos =
                    entries.stream()
                            .map(
                                    e ->
                                            new ConsentDtos.DsarAuditEntry(
                                                    e.getDeletionRequestId(),
                                                    e.getPoliciesDeleted(),
                                                    e.getCachedKeysDeleted(),
                                                    e.getAuditEntriesDeleted(),
                                                    e.getStatus(),
                                                    e.getActor(),
                                                    e.getIpAddress(),
                                                    e.getUserAgent(),
                                                    e.getCreatedAt() != null
                                                            ? e.getCreatedAt()
                                                                    .atZone(
                                                                            java.time.ZoneOffset
                                                                                    .UTC)
                                                                    .toInstant()
                                                            : null))
                            .toList();

            return Response.ok(new ConsentDtos.DsarAuditLogResponse(subjectId, dtos)).build();
        } catch (Exception e) {
            LOG.error("Failed to retrieve DSAR audit log for subject: {}", subjectId, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ErrorResponse.internal("Failed to retrieve DSAR audit log"))
                    .build();
        }
    }

    @DELETE
    @Path("/{id}/audit")
    @Operation(
            operationId = "deleteSubjectData",
            summary = "Erase all data for a subject (GDPR Article 17)",
            description =
                    "Atomically removes all consent policies, cached keys, and audit entries for a"
                        + " subject. Logs the erasure in a separate DSAR audit table. Requires the"
                        + " consent:write role and a confirmation token (?confirm=DELETE_{id})."
                        + " This operation is best-effort — if one store fails, others proceed.")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Deletion completed (check status for SUCCESS/PARTIAL_FAILURE)",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = DsarDeletionReceipt.class))),
        @APIResponse(
                responseCode = "400",
                description = "Missing or invalid confirmation token",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = ErrorResponse.class))),
        @APIResponse(
                responseCode = "403",
                description =
                        "Missing required role (consent:write) or missing tenant context "
                                + "when multi-tenant mode is enabled",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = ErrorResponse.class))),
        @APIResponse(
                responseCode = "500",
                description = "Deletion failed unexpectedly",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = ErrorResponse.class))),
    })
    public Response deleteSubjectData(
            @Context SecurityContext securityContext,
            @Parameter(
                            description = "The data subject identifier",
                            required = true,
                            example = "user_123")
                    @PathParam("id")
                    String subjectId,
            @Parameter(
                            description =
                                    "Confirmation token in format 'DELETE_{id}' to prevent"
                                            + " accidental deletion",
                            required = true,
                            example = "DELETE_user_123")
                    @QueryParam("confirm")
                    String confirmToken) {
        Permissions.require(requestHolder.authorization(), Permissions.CONSENT_DSAR);

        // Validate confirmation token
        String expectedToken = "DELETE_" + subjectId;
        if (confirmToken == null || !confirmToken.equals(expectedToken)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(
                            ErrorResponse.validation(
                                    "Confirmation token invalid. Use ?confirm=" + expectedToken))
                    .build();
        }

        // Multi-tenant guard: if enabled, require an active TenantContext
        if (cveilConfig.isMultiTenantEnabled()) {
            if (!TenantContext.isPresent()) {
                LOG.warn("DSAR delete denied: TenantContext is required but not set");
                return Response.status(Response.Status.FORBIDDEN)
                        .entity(
                                ErrorResponse.of(
                                        "TENANT_CONTEXT_REQUIRED",
                                        "This operation requires an active tenant context"))
                        .build();
            }
            LOG.debug("DSAR delete scoped to tenant: {}", TenantContext.get());
        }

        LOG.debug("DELETE /subjects/{} (with valid confirmation)", subjectId);

        String requestId = UUID.randomUUID().toString();

        try {
            // Multi-dispatch saga: sequential commands across aggregates.
            // Follows the same pattern as PlatformResource.createTenant().
            // Each step is independently atomic (event-sourced). If a later
            // step fails, earlier events remain published — partial state is
            // logged for manual remediation.
            String status = "PENDING_SHRED";

            // Step 1: Revoke all consent for the subject
            long ledgerEntriesRevoked = 0;
            try {
                SubjectConsentView consent =
                        consentQueryDispatcher.dispatch(
                                new GetSubjectConsentQueryActuator.GetSubjectConsentQuery(
                                        subjectId));
                if (consent != null && consent.entries() != null) {
                    ledgerEntriesRevoked = consent.entries().size();
                }
                consentDispatcher.dispatch(
                        new RevokeAllConsentCommandActuator.RevokeAllConsentCommand(
                                subjectId,
                                RequestContext.actor(),
                                RequestContext.ipAddress(),
                                RequestContext.userAgent()));
            } catch (Exception e) {
                LOG.error("eraseSubject: failed to revoke consent for {}", subjectId, e);
                status = "PARTIAL_FAILURE";
            }

            // Step 2: Delete cached consent-decision keys
            long cachedKeysDeleted = 0;
            try {
                cachedKeysDeleted =
                        consentQueryDispatcher.dispatch(
                                new DeleteSubjectCacheKeysQueryActuator.DeleteSubjectCacheKeysQuery(
                                        subjectId));
            } catch (Exception e) {
                LOG.error("eraseSubject: failed to delete cached keys for {}", subjectId, e);
                status = "PARTIAL_FAILURE";
            }

            // Step 3: Remove subject entity
            try {
                consentDispatcher.dispatch(
                        new RemoveSubjectCommandActuator.RemoveSubjectCommand(subjectId));
            } catch (Exception e) {
                LOG.error("eraseSubject: failed to remove subject {}", subjectId, e);
                status = "PARTIAL_FAILURE";
            }

            // Step 4: Log DSAR audit event
            try {
                consentDispatcher.dispatch(
                        new DeleteSubjectAuditCommandActuator.DeleteSubjectAuditCommand(
                                subjectId,
                                requestId,
                                RequestContext.actor(),
                                RequestContext.ipAddress(),
                                RequestContext.userAgent()));
            } catch (Exception e) {
                LOG.error("eraseSubject: failed to log DSAR audit for {}", subjectId, e);
                status = "PARTIAL_FAILURE";
            }

            DsarDeletionReceipt dto =
                    new DsarDeletionReceipt(
                            subjectId,
                            requestId,
                            ledgerEntriesRevoked,
                            cachedKeysDeleted,
                            status,
                            Instant.now());

            metrics.counter("dsar_deletions", currentTenantId());
            return Response.ok(dto).build();
        } catch (Exception e) {
            LOG.error("Unexpected error during DSAR erasure for subject: {}", subjectId, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ErrorResponse.internal("Unexpected error during data erasure"))
                    .build();
        }
    }

    // -------------------------------------------------------------------
    // Subject entity CRUD -- require admin:read / admin:write
    // -------------------------------------------------------------------

    @GET
    @Operation(
            operationId = "listSubjects",
            summary = "List all registered subjects",
            description = "Returns all subjects registered in the current tenant.")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Subjects returned",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema =
                                        @Schema(
                                                type = SchemaType.ARRAY,
                                                implementation = SubjectView.class))),
    })
    public Response listSubjects(@Context SecurityContext sc) {
        Permissions.require(requestHolder.authorization(), Permissions.CONSENT_READ);
        List<SubjectView> subjects =
                consentQueryDispatcher.dispatch(new ListSubjectsQueryActuator.ListSubjectsQuery());
        return Response.ok(subjects).build();
    }

    @GET
    @Path("/{name}")
    @Operation(
            operationId = "getSubject",
            summary = "Get a single registered subject",
            description = "Returns a single subject by name, or 404 if not found.")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Subject found",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = SubjectView.class))),
        @APIResponse(
                responseCode = "404",
                description = "Subject not found",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = ErrorResponse.class))),
    })
    public Response getSubject(
            @Context SecurityContext sc,
            @Parameter(description = "Subject name", required = true) @PathParam("name")
                    String name) {
        Permissions.require(requestHolder.authorization(), Permissions.CONSENT_READ);
        return ((Optional<SubjectView>)
                        consentQueryDispatcher.dispatch(
                                new GetSubjectQueryActuator.GetSubjectQuery(name)))
                .map(s -> Response.ok(s).build())
                .orElse(
                        Response.status(Response.Status.NOT_FOUND)
                                .entity(ErrorResponse.of("NOT_FOUND", "Subject not found: " + name))
                                .build());
    }

    @POST
    @Operation(
            operationId = "registerSubject",
            summary = "Register a new subject",
            description = "Creates a new data subject record in the current tenant.")
    @APIResponses({
        @APIResponse(
                responseCode = "201",
                description = "Subject registered",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = MessageResponse.class))),
        @APIResponse(
                responseCode = "400",
                description = "Validation error",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = ErrorResponse.class))),
    })
    public Response registerSubject(
            @Context SecurityContext sc, @Valid RegisterSubjectRequest req) {
        Permissions.require(requestHolder.authorization(), Permissions.CONSENT_GRANT);
        consentDispatcher.dispatch(
                new RegisterSubjectCommandActuator.RegisterSubjectCommand(
                        req.subjectName(), req.description()));
        metrics.counter("subject_created", currentTenantId());
        return Response.status(Response.Status.CREATED)
                .entity(MessageResponse.of("Subject registered: " + req.subjectName()))
                .build();
    }

    @PUT
    @Path("/{name}")
    @Operation(
            operationId = "updateSubject",
            summary = "Update a subject's description",
            description = "Updates the description of an existing subject.")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Subject updated",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = MessageResponse.class))),
    })
    public Response updateSubject(
            @Context SecurityContext sc,
            @Parameter(description = "Subject name", required = true) @PathParam("name")
                    String name,
            @Valid UpdateSubjectRequest req) {
        Permissions.require(requestHolder.authorization(), Permissions.CONSENT_GRANT);
        consentDispatcher.dispatch(
                new UpdateSubjectCommandActuator.UpdateSubjectCommand(name, req.description()));
        metrics.counter("subject_updated", currentTenantId());
        return Response.ok(MessageResponse.of("Subject updated: " + name)).build();
    }

    @DELETE
    @Path("/{name}")
    @Operation(
            operationId = "removeSubject",
            summary = "Remove a registered subject",
            description =
                    "Removes a subject from the current tenant. Returns 409 if the subject still"
                            + " has active consent entries.")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Subject removed",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = MessageResponse.class))),
        @APIResponse(
                responseCode = "409",
                description = "Subject still referenced by consent entries",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = ErrorResponse.class))),
    })
    public Response removeSubject(
            @Context SecurityContext sc,
            @Parameter(description = "Subject name", required = true) @PathParam("name")
                    String name) {
        Permissions.require(requestHolder.authorization(), Permissions.CONSENT_GRANT);
        consentDispatcher.dispatch(new RemoveSubjectCommandActuator.RemoveSubjectCommand(name));
        metrics.counter("subject_deleted", currentTenantId());
        return Response.ok(MessageResponse.of("Subject removed: " + name)).build();
    }

    @DELETE
    @Path("/{name}/dsar-key")
    @Operation(
            operationId = "shredSubject",
            summary = "Shred a subject's DSAR encryption key (GDPR Article 17)",
            description =
                    "Deletes the per-subject encryption key, rendering all shreddable event"
                        + " payloads for this subject permanently unreadable. This is a"
                        + " destructive, irreversible operation. Returns 409 Conflict if events are"
                        + " still pending outbox processing — retry after the outbox has flushed.")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "DSAR key deleted — events permanently shredded",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = MessageResponse.class))),
        @APIResponse(
                responseCode = "409",
                description = "Events still pending outbox processing",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = ErrorResponse.class))),
    })
    public Response shredSubject(
            @Context SecurityContext sc,
            @Parameter(description = "Subject name", required = true) @PathParam("name")
                    String name) {
        Permissions.require(requestHolder.authorization(), Permissions.CONSENT_DSAR);

        ShredSubjectCommandActuator.ShredSubjectResult result =
                consentDispatcher.dispatch(
                        new ShredSubjectCommandActuator.ShredSubjectCommand(name));
        if (!result.shredded()) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(
                            ErrorResponse.of(
                                    "EVENTS_PENDING",
                                    "Events for subject '"
                                            + name
                                            + "' are still pending outbox processing. "
                                            + "Retry after the outbox has flushed."))
                    .build();
        }

        metrics.counter("dsar_shred", currentTenantId());
        return Response.ok(MessageResponse.of("DSAR key shredded for subject: " + name)).build();
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    private PurposeView.PolicyView resolvePolicy(ConsentScope scope) {
        String policyType = isWildcardOrBlank(scope.fieldName()) ? "CATEGORY" : "FIELD";
        PurposeView.PolicyView policy =
                consentQueryDispatcher.dispatch(
                        new FindPolicyQueryActuator.FindPolicyQuery(
                                scope.purposeName(),
                                policyType,
                                isWildcardOrBlank(scope.fieldName()) ? null : scope.fieldName(),
                                isWildcardOrBlank(scope.entityName()) ? null : scope.entityName(),
                                isWildcardOrBlank(scope.category()) ? null : scope.category()));
        if (policy == null) {
            throw new IllegalArgumentException(
                    "No matching policy found for scope: "
                            + scope
                            + " — policies must be created before consent can be granted or"
                            + " revoked");
        }
        return policy;
    }

    private static boolean isWildcardOrBlank(String value) {
        return value == null || value.isBlank() || ConsentScope.WILDCARD.equals(value);
    }

    private String currentTenantId() {
        var identity = requestHolder.identity();
        return identity != null ? identity.tenantId() : null;
    }
}
