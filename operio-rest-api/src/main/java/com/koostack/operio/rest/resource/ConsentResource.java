package com.koostack.operio.rest.resource;

import com.koostack.operio.core.Models.BatchConsentEntry;
import com.koostack.operio.core.Models.BatchConsentResult;
import com.koostack.operio.core.Models.ConsentAuditEntry;
import com.koostack.operio.core.Models.ConsentResponse;
import com.koostack.operio.core.Models.ConsentScope;
import com.koostack.operio.core.Models.PolicySummaryEntry;
import com.koostack.operio.core.Operio;
import com.koostack.operio.core.service.ConsentAdminService;
import com.koostack.operio.rest.auth.Roles;
import com.koostack.operio.rest.config.OperioOpenApiConfig;
import com.koostack.operio.rest.dto.CommonDtos.ErrorResponse;
import com.koostack.operio.rest.dto.CommonDtos.MessageResponse;
import com.koostack.operio.rest.dto.ConsentDtos.BatchConsentResponse;
import com.koostack.operio.rest.dto.ConsentDtos.BatchGrantRequest;
import com.koostack.operio.rest.dto.ConsentDtos.BatchRevokeRequest;
import com.koostack.operio.rest.dto.ConsentDtos.ConsentAuditEntryDto;
import com.koostack.operio.rest.dto.ConsentDtos.EvaluateRequest;
import com.koostack.operio.rest.dto.ConsentDtos.EvaluateResponse;
import com.koostack.operio.rest.dto.ConsentDtos.GrantRequest;
import com.koostack.operio.rest.dto.ConsentDtos.PolicySummaryDto;
import com.koostack.operio.rest.dto.ConsentDtos.RevokeRequest;
import com.koostack.operio.rest.dto.ConsentScopeDto;
import com.koostack.operio.rest.interceptor.TenantScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
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
import java.util.List;
import java.util.Set;
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

    // -------------------------------------------------------------------
    // Mutating endpoints -- require consent:write
    // -------------------------------------------------------------------

    @POST
    @Path("/grant")
    @Operation(
        operationId = "grantConsent",
        summary = "Grant consent for a single scope",
        description = "Records a consent grant for the specified subject and scope. "
            + "Requires the consent:write role. If the consent already exists, "
            + "this is an idempotent update (e.g. refreshing expiration)."
    )
    @APIResponses({
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
    })
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
        description = "Records a consent revocation for the specified subject and scope. "
            + "Requires the consent:write role."
    )
    @APIResponses({
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
    })
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
        description = "Grants consent for the given subject across multiple scopes. "
            + "Each grant is independently atomic -- partial success is possible. "
            + "Requires the consent:write role."
    )
    @APIResponses({
        @APIResponse(
            responseCode = "200",
            description = "Batch result with succeeded/failed counts",
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = BatchConsentResponse.class)
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
    })
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
        description = "Revokes consent for the given subject across multiple scopes. "
            + "Each revocation is independently atomic -- partial success is possible. "
            + "Requires the consent:write role."
    )
    @APIResponses({
        @APIResponse(
            responseCode = "200",
            description = "Batch result with succeeded/failed counts",
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = BatchConsentResponse.class)
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
    })
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
        description = "Returns which scopes are granted, denied, with reasons and "
            + "structured decision details. Requires the consent:read role."
    )
    @APIResponses({
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
    })
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
        description = "Returns every active consent policy for the given data subject. "
            + "Requires the consent:read role."
    )
    @APIResponses({
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
    })
    public Response subjectSummary(
        @Context SecurityContext securityContext,
        @Parameter(
            description = "The data subject identifier (e.g. user ID)",
            required = true,
            example = "user-12345"
        )
        @PathParam("id") String subjectId
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
        description = "Returns the consent audit trail for the given data subject, "
            + "ordered by timestamp descending (most recent first). Optionally "
            + "filters by scope components. Requires the consent:read role."
    )
    @APIResponses({
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
    })
    public Response subjectHistory(
        @Context SecurityContext securityContext,
        @Parameter(
            description = "The data subject identifier",
            required = true,
            example = "user-12345"
        )
        @PathParam("id") String subjectId,
        @Parameter(
            description = "Filter by field name (optional)",
            example = "email"
        )
        @QueryParam("fieldName") String fieldName,
        @Parameter(
            description = "Filter by entity name (optional)",
            example = "User"
        )
        @QueryParam("entityName") String entityName,
        @Parameter(
            description = "Filter by purpose name (optional)",
            example = "marketing"
        )
        @QueryParam("purposeName") String purposeName
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
}
