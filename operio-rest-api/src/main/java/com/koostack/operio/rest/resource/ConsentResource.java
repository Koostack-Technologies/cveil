package com.koostack.operio.rest.resource;

import com.koostack.operio.core.Models.BatchConsentEntry;
import com.koostack.operio.core.Models.BatchConsentResult;
import com.koostack.operio.core.Models.ConsentAuditEntry;
import com.koostack.operio.core.Models.ConsentResponse;
import com.koostack.operio.core.Models.ConsentScope;
import com.koostack.operio.core.Models.PolicySummaryEntry;
import com.koostack.operio.core.Operio;
import com.koostack.operio.core.RequestContext;
import com.koostack.operio.core.TenantContext;
import com.koostack.operio.core.service.ConsentAdminService;
import com.koostack.operio.core.service.ConsentDecisionService;
import com.koostack.operio.rest.auth.Roles;
import com.koostack.operio.rest.config.OperioConfig;
import com.koostack.operio.rest.config.OperioOpenApiConfig;
import com.koostack.operio.rest.dto.CommonDtos.ErrorResponse;
import com.koostack.operio.rest.dto.CommonDtos.MessageResponse;
import com.koostack.operio.rest.dto.ConsentDtos.BatchConsentResponse;
import com.koostack.operio.rest.dto.ConsentDtos.BatchGrantRequest;
import com.koostack.operio.rest.dto.ConsentDtos.BatchRevokeRequest;
import com.koostack.operio.rest.dto.ConsentDtos.ConsentAuditEntryDto;
import com.koostack.operio.rest.dto.ConsentDtos.DsarDeletionReceipt;
import com.koostack.operio.rest.dto.ConsentDtos.EvaluateRequest;
import com.koostack.operio.rest.dto.ConsentDtos.EvaluateResponse;
import com.koostack.operio.rest.dto.ConsentDtos.GrantRequest;
import com.koostack.operio.rest.dto.ConsentDtos.PolicySummaryDto;
import com.koostack.operio.rest.dto.ConsentDtos.RevokeRequest;
import com.koostack.operio.rest.dto.ConsentDtos.SubjectDataExport;
import com.koostack.operio.rest.dto.ConsentScopeDto;
import com.koostack.operio.rest.interceptor.TenantScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.time.Instant;
import java.util.List;
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
 * JAX-RS resource for consent operations: grant, revoke, evaluate,
 * batch, subject summary, and audit history.
 *
 * <p>All endpoints require authentication (enforced by ApiKeyAuthFilter)
 * and operate within a tenant scope (provided by the TenantScoped CDI
 * interceptor binding).
 *
 * <h2>RBAC Enforcement</h2>
 * <ul>
 *   <li>Read-only endpoints (evaluate, subject summary, subject history)
 *       require the {@link Roles#CONSENT_READ consent:read} role.</li>
 *   <li>Mutating endpoints (grant, revoke, grant-batch, revoke-batch)
 *       require the {@link Roles#CONSENT_WRITE consent:write} role.</li>
 *   <li>The {@link Roles#SUPER_ADMIN super-admin} role bypasses all
 *       checks.</li>
 * </ul>
 *
 * @see Roles
 */
@Path("/api/v1/consent")
@TenantScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(ref = OperioOpenApiConfig.TAG_CONSENT)
@SecurityRequirement(name = OperioOpenApiConfig.SECURITY_SCHEME)
public class ConsentResource {

    private static final Logger LOG = LoggerFactory.getLogger(
        ConsentResource.class
    );

    @Inject
    Operio operio;

    @Inject
    OperioConfig operioConfig;

    // -------------------------------------------------------------------
    // Mutating endpoints -- require consent:write
    // -------------------------------------------------------------------

    @POST
    @Path("/grant")
    @Operation(
        operationId = "grantConsent",
        summary = "Grant consent for a single scope",
        description = "Records a consent grant for the specified subject and scope. " +
            "Requires the consent:write role. If the consent already exists, " +
            "this is an idempotent update (e.g. refreshing expiration)."
    )
    @APIResponses(
        {
            @APIResponse(
                responseCode = "200",
                description = "Consent granted successfully",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = MessageResponse.class)
                )
            ),
            @APIResponse(
                responseCode = "400",
                description = "Validation error (missing or invalid fields)",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ErrorResponse.class)
                )
            ),
            @APIResponse(
                responseCode = "403",
                description = "Missing required role (consent:write)",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ErrorResponse.class)
                )
            ),
            @APIResponse(
                responseCode = "429",
                description = "Rate limit exceeded",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ErrorResponse.class)
                )
            ),
        }
    )
    public Response grant(
        @Context SecurityContext securityContext,
        @Valid GrantRequest request
    ) {
        Roles.requireRole(securityContext, Roles.CONSENT_WRITE);

        LOG.debug(
            "POST /consent/grant - subject={}, scope={}",
            request.subject(),
            request.scope()
        );

        operio.grantConsent(
            request.subject(),
            request.scope().toModel(),
            request.expiresAt()
        );

        return Response.ok(MessageResponse.of("Consent granted")).build();
    }

    @POST
    @Path("/revoke")
    @Operation(
        operationId = "revokeConsent",
        summary = "Revoke consent for a single scope",
        description = "Records a consent revocation for the specified subject and scope. " +
            "Requires the consent:write role."
    )
    @APIResponses(
        {
            @APIResponse(
                responseCode = "200",
                description = "Consent revoked successfully",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = MessageResponse.class)
                )
            ),
            @APIResponse(
                responseCode = "400",
                description = "Validation error",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ErrorResponse.class)
                )
            ),
            @APIResponse(
                responseCode = "403",
                description = "Missing required role (consent:write)",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ErrorResponse.class)
                )
            ),
            @APIResponse(
                responseCode = "429",
                description = "Rate limit exceeded",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ErrorResponse.class)
                )
            ),
        }
    )
    public Response revoke(
        @Context SecurityContext securityContext,
        @Valid RevokeRequest request
    ) {
        Roles.requireRole(securityContext, Roles.CONSENT_WRITE);

        LOG.debug(
            "POST /consent/revoke - subject={}, scope={}",
            request.subject(),
            request.scope()
        );

        operio.revokeConsent(
            request.subject(),
            request.scope().toModel(),
            request.expiresAt()
        );

        return Response.ok(MessageResponse.of("Consent revoked")).build();
    }

    @POST
    @Path("/grant-batch")
    @Operation(
        operationId = "grantConsentBatch",
        summary = "Grant consent for multiple scopes in a single request",
        description = "Grants consent for the given subject across multiple scopes. " +
            "Each grant is independently atomic -- partial success is possible. " +
            "Requires the consent:write role."
    )
    @APIResponses(
        {
            @APIResponse(
                responseCode = "200",
                description = "Batch result with succeeded/failed counts",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(
                        implementation = BatchConsentResponse.class
                    )
                )
            ),
            @APIResponse(
                responseCode = "400",
                description = "Validation error",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ErrorResponse.class)
                )
            ),
            @APIResponse(
                responseCode = "403",
                description = "Missing required role (consent:write)",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ErrorResponse.class)
                )
            ),
            @APIResponse(
                responseCode = "429",
                description = "Rate limit exceeded",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ErrorResponse.class)
                )
            ),
        }
    )
    public Response grantBatch(
        @Context SecurityContext securityContext,
        @Valid BatchGrantRequest request
    ) {
        Roles.requireRole(securityContext, Roles.CONSENT_WRITE);

        LOG.debug(
            "POST /consent/grant-batch - subject={}, count={}",
            request.subject(),
            request.grants() != null ? request.grants().size() : 0
        );

        List<BatchConsentEntry> entries = request
            .grants()
            .stream()
            .map(dto -> dto.toModel())
            .toList();

        BatchConsentResult result = operio.grantConsentBatch(
            request.subject(),
            entries
        );

        return Response.ok(BatchConsentResponse.fromModel(result)).build();
    }

    @POST
    @Path("/revoke-batch")
    @Operation(
        operationId = "revokeConsentBatch",
        summary = "Revoke consent for multiple scopes in a single request",
        description = "Revokes consent for the given subject across multiple scopes. " +
            "Each revocation is independently atomic -- partial success is possible. " +
            "Requires the consent:write role."
    )
    @APIResponses(
        {
            @APIResponse(
                responseCode = "200",
                description = "Batch result with succeeded/failed counts",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(
                        implementation = BatchConsentResponse.class
                    )
                )
            ),
            @APIResponse(
                responseCode = "400",
                description = "Validation error",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ErrorResponse.class)
                )
            ),
            @APIResponse(
                responseCode = "403",
                description = "Missing required role (consent:write)",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ErrorResponse.class)
                )
            ),
            @APIResponse(
                responseCode = "429",
                description = "Rate limit exceeded",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ErrorResponse.class)
                )
            ),
        }
    )
    public Response revokeBatch(
        @Context SecurityContext securityContext,
        @Valid BatchRevokeRequest request
    ) {
        Roles.requireRole(securityContext, Roles.CONSENT_WRITE);

        LOG.debug(
            "POST /consent/revoke-batch - subject={}, count={}",
            request.subject(),
            request.revokes() != null ? request.revokes().size() : 0
        );

        List<BatchConsentEntry> entries = request
            .revokes()
            .stream()
            .map(dto -> dto.toModel())
            .toList();

        BatchConsentResult result = operio.revokeConsentBatch(
            request.subject(),
            entries
        );

        return Response.ok(BatchConsentResponse.fromModel(result)).build();
    }

    // -------------------------------------------------------------------
    // Read-only endpoints -- require consent:read
    // -------------------------------------------------------------------

    @POST
    @Path("/evaluate")
    @Operation(
        operationId = "evaluateConsent",
        summary = "Evaluate consent for a subject against a set of scopes",
        description = "Returns which scopes are granted, denied, with reasons and " +
            "structured decision details. Requires the consent:read role."
    )
    @APIResponses(
        {
            @APIResponse(
                responseCode = "200",
                description = "Evaluation result with granted/denied sets and details",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = EvaluateResponse.class)
                )
            ),
            @APIResponse(
                responseCode = "400",
                description = "Validation error",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ErrorResponse.class)
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
            @APIResponse(
                responseCode = "429",
                description = "Rate limit exceeded",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ErrorResponse.class)
                )
            ),
        }
    )
    public Response evaluate(
        @Context SecurityContext securityContext,
        @Valid EvaluateRequest request
    ) {
        Roles.requireRole(securityContext, Roles.CONSENT_READ);

        LOG.debug(
            "POST /consent/evaluate - subject={}, scopeCount={}",
            request.subject(),
            request.scopes() != null ? request.scopes().size() : 0
        );

        Set<ConsentScope> coreScopes = request
            .scopes()
            .stream()
            .map(ConsentScopeDto::toModel)
            .collect(Collectors.toSet());

        ConsentResponse response = operio
            .consentService()
            .evaluateConsent(request.subject(), coreScopes);

        return Response.ok(EvaluateResponse.fromModel(response)).build();
    }

    @GET
    @Path("/subjects/{id}/summary")
    @Operation(
        operationId = "getSubjectSummary",
        summary = "List all consent policies for a subject",
        description = "Returns every active consent policy for the given data subject. " +
            "Requires the consent:read role."
    )
    @APIResponses(
        {
            @APIResponse(
                responseCode = "200",
                description = "List of consent policy summaries",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(
                        type = SchemaType.ARRAY,
                        implementation = PolicySummaryDto.class
                    )
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
            @APIResponse(
                responseCode = "429",
                description = "Rate limit exceeded",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ErrorResponse.class)
                )
            ),
        }
    )
    public Response subjectSummary(
        @Context SecurityContext securityContext,
        @Parameter(
            description = "The data subject identifier (e.g. user ID)",
            required = true,
            example = "user-12345"
        ) @PathParam("id") String subjectId
    ) {
        Roles.requireRole(securityContext, Roles.CONSENT_READ);

        LOG.debug("GET /consent/subjects/{}/summary", subjectId);

        ConsentAdminService<?> adminService = operio.consentAdminService();
        List<PolicySummaryEntry> entries = adminService.findPoliciesBySubject(
            subjectId
        );

        List<PolicySummaryDto> dtos = entries
            .stream()
            .map(PolicySummaryDto::fromModel)
            .toList();

        return Response.ok(dtos).build();
    }

    @GET
    @Path("/subjects/{id}/history")
    @Operation(
        operationId = "getSubjectHistory",
        summary = "Get consent audit history for a subject",
        description = "Returns the consent audit trail for the given data subject, " +
            "ordered by timestamp descending (most recent first). Optionally " +
            "filters by scope components. Requires the consent:read role."
    )
    @APIResponses(
        {
            @APIResponse(
                responseCode = "200",
                description = "List of consent audit entries",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(
                        type = SchemaType.ARRAY,
                        implementation = ConsentAuditEntryDto.class
                    )
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
            @APIResponse(
                responseCode = "429",
                description = "Rate limit exceeded",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ErrorResponse.class)
                )
            ),
        }
    )
    public Response subjectHistory(
        @Context SecurityContext securityContext,
        @Parameter(
            description = "The data subject identifier",
            required = true,
            example = "user-12345"
        ) @PathParam("id") String subjectId,
        @Parameter(
            description = "Filter by field name (optional)",
            example = "email"
        ) @QueryParam("fieldName") String fieldName,
        @Parameter(
            description = "Filter by entity name (optional)",
            example = "User"
        ) @QueryParam("entityName") String entityName,
        @Parameter(
            description = "Filter by purpose name (optional)",
            example = "marketing"
        ) @QueryParam("purposeName") String purposeName
    ) {
        Roles.requireRole(securityContext, Roles.CONSENT_READ);

        LOG.debug(
            "GET /consent/subjects/{}/history - field={}, entity={}, purpose={}",
            subjectId,
            fieldName,
            entityName,
            purposeName
        );

        ConsentAdminService<?> adminService = operio.consentAdminService();
        List<ConsentAuditEntry> entries;

        if (fieldName != null && !fieldName.isBlank()) {
            ConsentScope filterScope = ConsentScope.to(
                null, // category not used for history filtering
                fieldName,
                entityName,
                purposeName
            );
            entries = adminService.getConsentHistory(subjectId, filterScope);
        } else {
            entries = adminService.getConsentHistory(subjectId);
        }

        List<ConsentAuditEntryDto> dtos = entries
            .stream()
            .map(ConsentAuditEntryDto::fromModel)
            .toList();

        return Response.ok(dtos).build();
    }

    // -------------------------------------------------------------------
    // DSAR (Data Subject Access Request) endpoints
    // -------------------------------------------------------------------

    @GET
    @Path("/subjects/{id}/export")
    @Operation(
        operationId = "exportSubjectData",
        summary = "Export all data for a subject (GDPR Article 20)",
        description = "Returns a complete export of all consent-related data held for a subject, " +
            "including active policies, audit trail entries, and cached consent keys. " +
            "Requires the consent:write role."
    )
    @APIResponses(
        {
            @APIResponse(
                responseCode = "200",
                description = "Data export successful",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = SubjectDataExport.class)
                )
            ),
            @APIResponse(
                responseCode = "403",
                description = "Missing required role (consent:write) or missing tenant context " +
                    "when multi-tenant mode is enabled",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ErrorResponse.class)
                )
            ),
            @APIResponse(
                responseCode = "500",
                description = "Export failed",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ErrorResponse.class)
                )
            ),
        }
    )
    public Response exportSubjectData(
        @Context SecurityContext securityContext,
        @Parameter(
            description = "The data subject identifier",
            required = true,
            example = "user_123"
        ) @PathParam("id") String subjectId
    ) {
        Roles.requireRole(securityContext, Roles.CONSENT_WRITE);

        if (subjectId == null || subjectId.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(ErrorResponse.validation("subjectId must not be blank"))
                .build();
        }

        // Multi-tenant guard: if enabled, require an active TenantContext
        if (operioConfig.isMultiTenantEnabled()) {
            if (!TenantContext.isPresent()) {
                LOG.warn(
                    "DSAR export denied: TenantContext is required but not set"
                );
                return Response.status(Response.Status.FORBIDDEN)
                    .entity(
                        ErrorResponse.of(
                            "TENANT_CONTEXT_REQUIRED",
                            "This operation requires an active tenant context"
                        )
                    )
                    .build();
            }
            LOG.debug("DSAR export scoped to tenant: {}", TenantContext.get());
        }

        LOG.debug("GET /consent/subjects/{}/export", subjectId);

        try {
            ConsentAdminService<?> adminService = operio.consentAdminService();

            // Fetch all consent policies for the subject
            List<PolicySummaryEntry> policies =
                adminService.findPoliciesBySubject(subjectId);
            List<PolicySummaryDto> policyDtos = policies
                .stream()
                .map(PolicySummaryDto::fromModel)
                .toList();

            // Fetch audit trail entries for the subject
            List<ConsentAuditEntry> auditEntries =
                adminService.getConsentHistory(subjectId);
            List<ConsentAuditEntryDto> auditDtos = auditEntries
                .stream()
                .map(ConsentAuditEntryDto::fromModel)
                .toList();

            // Count cached Redis keys for the subject (if applicable)
            long cachedKeyCount = 0;
            ConsentDecisionService<?> decisionService = operio.consentService();
            if (
                decisionService instanceof
                    com.koostack.operio.plugin.consent.redis.RedisConsentDecisionService redis
            ) {
                cachedKeyCount = redis.countCachedKeysForSubject(subjectId);
            }

            SubjectDataExport export = new SubjectDataExport(
                subjectId,
                policyDtos,
                auditDtos,
                cachedKeyCount,
                Instant.now()
            );

            LOG.info(
                "Exported data for subject: {} ({} policies, {} audit entries)",
                subjectId,
                policies.size(),
                auditEntries.size()
            );

            return Response.ok(export).build();
        } catch (Exception e) {
            LOG.error("Failed to export data for subject: {}", subjectId, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(ErrorResponse.internal("Failed to export subject data"))
                .build();
        }
    }

    @DELETE
    @Path("/subjects/{id}")
    @Operation(
        operationId = "deleteSubjectData",
        summary = "Erase all data for a subject (GDPR Article 17)",
        description = "Atomically removes all consent policies, cached keys, and audit entries " +
            "for a subject. Logs the erasure in a separate DSAR audit table. " +
            "Requires the consent:write role and a confirmation token (?confirm=DELETE_{id}). " +
            "This operation is best-effort — if one store fails, others proceed."
    )
    @APIResponses(
        {
            @APIResponse(
                responseCode = "200",
                description = "Deletion completed (check status for SUCCESS/PARTIAL_FAILURE)",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = DsarDeletionReceipt.class)
                )
            ),
            @APIResponse(
                responseCode = "400",
                description = "Missing or invalid confirmation token",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ErrorResponse.class)
                )
            ),
            @APIResponse(
                responseCode = "403",
                description = "Missing required role (consent:write) or missing tenant context " +
                    "when multi-tenant mode is enabled",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ErrorResponse.class)
                )
            ),
            @APIResponse(
                responseCode = "500",
                description = "Deletion failed unexpectedly",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ErrorResponse.class)
                )
            ),
        }
    )
    public Response deleteSubjectData(
        @Context SecurityContext securityContext,
        @Parameter(
            description = "The data subject identifier",
            required = true,
            example = "user_123"
        ) @PathParam("id") String subjectId,
        @Parameter(
            description = "Confirmation token in format 'DELETE_{id}' to prevent accidental deletion",
            required = true,
            example = "DELETE_user_123"
        ) @QueryParam("confirm") String confirmToken
    ) {
        Roles.requireRole(securityContext, Roles.CONSENT_WRITE);

        // Validate confirmation token
        String expectedToken = "DELETE_" + subjectId;
        if (confirmToken == null || !confirmToken.equals(expectedToken)) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(
                    ErrorResponse.validation(
                        "Confirmation token invalid. Use ?confirm=" +
                            expectedToken
                    )
                )
                .build();
        }

        // Multi-tenant guard: if enabled, require an active TenantContext
        if (operioConfig.isMultiTenantEnabled()) {
            if (!TenantContext.isPresent()) {
                LOG.warn(
                    "DSAR delete denied: TenantContext is required but not set"
                );
                return Response.status(Response.Status.FORBIDDEN)
                    .entity(
                        ErrorResponse.of(
                            "TENANT_CONTEXT_REQUIRED",
                            "This operation requires an active tenant context"
                        )
                    )
                    .build();
            }
            LOG.debug("DSAR delete scoped to tenant: {}", TenantContext.get());
        }

        LOG.debug(
            "DELETE /consent/subjects/{} (with valid confirmation)",
            subjectId
        );

        String requestId = UUID.randomUUID().toString();
        long policiesDeleted = 0;
        long cachedKeysDeleted = 0;
        long auditEntriesDeleted = 0;
        String status = "SUCCESS";
        Instant completedAt = Instant.now();

        try {
            ConsentAdminService<?> adminService = operio.consentAdminService();
            ConsentDecisionService<?> decisionService = operio.consentService();

            // Step 1: Count policies BEFORE deletion, then bulk delete
            try {
                policiesDeleted = adminService.countPoliciesBySubject(
                    subjectId
                );
                adminService.revokeAllConsent(subjectId);
                LOG.info(
                    "Deleted {} consent policies for subject: {}",
                    policiesDeleted,
                    subjectId
                );
            } catch (Exception e) {
                LOG.error(
                    "Failed to delete consent policies for subject: {}",
                    subjectId,
                    e
                );
                status = "PARTIAL_FAILURE";
            }

            // Step 2: Delete cached keys from Redis (if applicable)
            try {
                if (
                    decisionService instanceof
                        com.koostack.operio.plugin.consent.redis.RedisConsentDecisionService redis
                ) {
                    cachedKeysDeleted = redis.deleteAllCachedKeysForSubject(
                        subjectId
                    );
                    LOG.info(
                        "Deleted {} cached keys for subject: {}",
                        cachedKeysDeleted,
                        subjectId
                    );
                }
            } catch (Exception e) {
                LOG.error(
                    "Failed to delete cached keys for subject: {}",
                    subjectId,
                    e
                );
                status = "PARTIAL_FAILURE";
            }

            // Step 3: Delete audit log entries (consent_audit_log)
            try {
                auditEntriesDeleted = adminService.deleteAuditLogBySubject(
                    subjectId
                );
                LOG.info(
                    "Deleted {} audit log entries for subject: {}",
                    auditEntriesDeleted,
                    subjectId
                );
            } catch (Exception e) {
                LOG.error(
                    "Failed to delete audit log entries for subject: {}",
                    subjectId,
                    e
                );
                status = "PARTIAL_FAILURE";
            }

            completedAt = Instant.now();

            // Step 4: Log the erasure in DSAR audit table (durable forensic record)
            try {
                if (
                    adminService instanceof
                        com.koostack.operio.plugin.consent.jooq.JooqConsentAdminService jooqAdmin
                ) {
                    jooqAdmin
                        .dsarAuditLogRepository()
                        .insert(
                            subjectId,
                            requestId,
                            (int) policiesDeleted,
                            (int) cachedKeysDeleted,
                            (int) auditEntriesDeleted,
                            status,
                            RequestContext.actor(),
                            RequestContext.ipAddress(),
                            RequestContext.userAgent(),
                            null
                        );
                    LOG.info(
                        "DSAR erasure request {} for subject {} recorded in dsar_audit_log with status {}",
                        requestId,
                        subjectId,
                        status
                    );
                } else {
                    LOG.info(
                        "DSAR erasure request {} for subject {} completed with status {} (no DSAR audit log — admin service is not jOOQ-backed)",
                        requestId,
                        subjectId,
                        status
                    );
                }
            } catch (Exception e) {
                LOG.error(
                    "Failed to log DSAR erasure for subject: {}",
                    subjectId,
                    e
                );
                // Don't change status here — logging failure is not critical
            }

            DsarDeletionReceipt receipt = new DsarDeletionReceipt(
                subjectId,
                requestId,
                policiesDeleted,
                cachedKeysDeleted,
                auditEntriesDeleted,
                status,
                completedAt
            );

            return Response.ok(receipt).build();
        } catch (Exception e) {
            LOG.error(
                "Unexpected error during DSAR erasure for subject: {}",
                subjectId,
                e
            );
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(
                    ErrorResponse.internal(
                        "Unexpected error during data erasure"
                    )
                )
                .build();
        }
    }
}
