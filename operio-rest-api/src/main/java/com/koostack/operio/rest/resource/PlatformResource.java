package com.koostack.operio.rest.resource;

import com.koostack.operio.platform.core.jooq.ChangeTenantPlanCommandActuator;
import com.koostack.operio.platform.core.jooq.DeleteTenantCommandActuator;
import com.koostack.operio.platform.core.jooq.GetTenantQueryActuator;
import com.koostack.operio.platform.core.jooq.ListTenantsQueryActuator;
import com.koostack.operio.rest.auth.Roles;
import com.koostack.operio.rest.config.OperioOpenApiConfig;
import com.koostack.operio.rest.config.PlatformCqrsHolder;
import com.koostack.operio.rest.dto.CommonDtos.ErrorResponse;
import com.koostack.operio.rest.dto.CommonDtos.MessageResponse;
import com.koostack.operio.rest.dto.PlatformDtos.CreateTenantRequest;
import com.koostack.operio.rest.dto.PlatformDtos.TenantResponse;
import com.koostack.operio.rest.dto.PlatformDtos.UpdatePlanRequest;
import com.koostack.operio.rest.interceptor.TenantScoped;
import com.koostack.operio.rest.ratelimit.RateLimitService;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.util.List;
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
 * JAX-RS resource for platform-level administration: tenant lifecycle and API key management.
 *
 * <p>All endpoints require the super-admin role.
 */
@Path("/api/v1/platform")
@TenantScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(ref = OperioOpenApiConfig.TAG_PLATFORM)
@SecurityRequirement(name = OperioOpenApiConfig.SECURITY_SCHEME)
public class PlatformResource {

    private static final Logger LOG = LoggerFactory.getLogger(PlatformResource.class);

    @Inject PlatformCqrsHolder cqrs;

    @Inject RateLimitService rateLimitService;

    // -------------------------------------------------------------------
    // Role enforcement
    // -------------------------------------------------------------------

    private void requireSuperAdmin(SecurityContext securityContext) {
        Roles.requireRole(securityContext, Roles.SUPER_ADMIN);
    }

    private void requireCqrsServices() {
        if (!cqrs.isCqrsAvailable()) {
            throw new IllegalStateException(
                    "Platform CQRS services are not available in embedded mode. "
                            + "Set operio.mode=production to enable tenant management.");
        }
    }

    private void requireDispatchers() {
        if (cqrs.coreCommandService() == null || cqrs.billingCommandService() == null) {
            throw new IllegalStateException(
                    "Platform dispatchers are not available in embedded mode. "
                            + "Set operio.mode=production to enable tenant management.");
        }
    }

    // -------------------------------------------------------------------
    // Tenants
    // -------------------------------------------------------------------

    @POST
    @Path("/tenants")
    @Operation(
            operationId = "createTenant",
            summary = "Create a new tenant (deprecated — use POST /api/v1/platform/iam/users)",
            description =
                    "This endpoint is no longer supported. Tenant creation is now part of the "
                            + "unified user registration flow via POST /api/v1/platform/iam/users, "
                            + "which orchestrates user registration, organization creation, tenant "
                            + "provisioning, and billing setup in a single saga.")
    @APIResponses({
        @APIResponse(
                responseCode = "501",
                description = "Operation not supported",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = ErrorResponse.class))),
    })
    public Response createTenant(
            @Context SecurityContext securityContext, @Valid CreateTenantRequest request) {
        requireSuperAdmin(securityContext);

        LOG.info(
                "POST /platform/tenants — unsupported, tenant={}, plan={}",
                request.tenantId(),
                request.plan());

        return Response.status(501)
                .entity(
                        ErrorResponse.of(
                                "unsupported_operation",
                                "Tenant creation via this endpoint is no longer supported. "
                                        + "Use POST /api/v1/platform/iam/users instead."))
                .build();
    }

    @GET
    @Path("/tenants")
    @Operation(
            operationId = "listTenants",
            summary = "List all tenants",
            description =
                    "Returns all tenants on the platform with their metadata "
                            + "and plan-level limits. Requires the super-admin role.")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "List of all tenants",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema =
                                        @Schema(
                                                type = SchemaType.ARRAY,
                                                implementation = TenantResponse.class))),
        @APIResponse(
                responseCode = "403",
                description = "Missing required role (super-admin)",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = ErrorResponse.class))),
    })
    public Response listTenants(@Context SecurityContext securityContext) {
        requireSuperAdmin(securityContext);
        requireCqrsServices();

        LOG.debug("GET /platform/tenants");

        @SuppressWarnings("unchecked")
        List<com.koostack.operio.core.platform.core.models.TenantInfo> tenants =
                cqrs.coreQueryDispatcher()
                        .dispatch(new ListTenantsQueryActuator.ListTenantsQuery());

        List<TenantResponse> dtos = tenants.stream().map(TenantResponse::fromCoreModel).toList();

        return Response.ok(dtos).build();
    }

    @GET
    @Path("/tenants/{id}")
    @Operation(
            operationId = "getTenant",
            summary = "Get tenant details",
            description =
                    "Retrieves full information about a specific tenant including "
                            + "plan-level limits and status. Requires the super-admin role.")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Tenant details",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = TenantResponse.class))),
        @APIResponse(
                responseCode = "403",
                description = "Missing required role (super-admin)",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = ErrorResponse.class))),
        @APIResponse(
                responseCode = "404",
                description = "Tenant not found",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = ErrorResponse.class))),
    })
    public Response getTenant(
            @Context SecurityContext securityContext,
            @Parameter(
                            description = "Unique tenant identifier",
                            required = true,
                            example = "acme_corp")
                    @PathParam("id")
                    String tenantId) {
        requireSuperAdmin(securityContext);
        requireCqrsServices();

        LOG.debug("GET /platform/tenants/{}", tenantId);

        com.koostack.operio.core.platform.core.models.TenantInfo tenant =
                cqrs.coreQueryDispatcher()
                        .dispatch(new GetTenantQueryActuator.GetTenantQuery(tenantId));
        if (tenant == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(ErrorResponse.of("not_found", "Tenant not found: " + tenantId))
                    .build();
        }

        return Response.ok(TenantResponse.fromCoreModel(tenant)).build();
    }

    @DELETE
    @Path("/tenants/{id}")
    @Operation(
            operationId = "deleteTenant",
            summary = "Delete a tenant",
            description =
                    "Deletes a tenant and all associated resources: Postgres schema, "
                            + "Redis ACL user, and all API keys. This action is irreversible. "
                            + "Requires the super-admin role.")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Tenant deleted successfully",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = MessageResponse.class))),
        @APIResponse(
                responseCode = "403",
                description = "Missing required role (super-admin)",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = ErrorResponse.class))),
        @APIResponse(
                responseCode = "404",
                description = "Tenant not found",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = ErrorResponse.class))),
    })
    public Response deleteTenant(
            @Context SecurityContext securityContext,
            @Parameter(
                            description = "Unique tenant identifier",
                            required = true,
                            example = "acme_corp")
                    @PathParam("id")
                    String tenantId) {
        requireSuperAdmin(securityContext);
        requireDispatchers();

        LOG.info("DELETE /platform/tenants/{}", tenantId);

        cqrs.coreDispatcher()
                .dispatch(new DeleteTenantCommandActuator.DeleteTenantCommand(tenantId));

        return Response.ok(MessageResponse.of("Tenant '" + tenantId + "' deleted successfully"))
                .build();
    }

    // -------------------------------------------------------------------
    // Plan management
    // -------------------------------------------------------------------

    @PUT
    @Path("/tenants/{id}/plan")
    @Operation(
            operationId = "updateTenantPlan",
            summary = "Update a tenant's subscription plan",
            description =
                    "Changes the tenant's plan and recalculates all plan-level "
                            + "rate limits (evaluation quota, subject limit, burst cap). "
                            + "The new limits take effect immediately — cached rate-limit "
                            + "data is evicted so the next request uses the updated values. "
                            + "Requires the super-admin role.\n\n"
                            + "This endpoint is what a Stripe webhook handler would call "
                            + "after a subscription change.")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Plan updated successfully",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = TenantResponse.class))),
        @APIResponse(
                responseCode = "400",
                description = "Validation error (invalid plan)",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = ErrorResponse.class))),
        @APIResponse(
                responseCode = "403",
                description = "Missing required role (super-admin)",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = ErrorResponse.class))),
        @APIResponse(
                responseCode = "404",
                description = "Tenant not found",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON,
                                schema = @Schema(implementation = ErrorResponse.class))),
    })
    public Response updateTenantPlan(
            @Context SecurityContext securityContext,
            @Parameter(
                            description = "Unique tenant identifier",
                            required = true,
                            example = "acme_corp")
                    @PathParam("id")
                    String tenantId,
            @Valid UpdatePlanRequest request) {
        requireSuperAdmin(securityContext);
        requireDispatchers();

        LOG.info("PUT /platform/tenants/{}/plan - newPlan={}", tenantId, request.plan());

        cqrs.coreDispatcher()
                .dispatch(
                        new ChangeTenantPlanCommandActuator.ChangeTenantPlanCommand(
                                tenantId, request.plan()));

        // Evict cached rate limits so new limits take effect immediately
        rateLimitService.evictTenantCache(tenantId);

        return Response.ok(
                        MessageResponse.of(
                                "Plan change to '"
                                        + request.plan()
                                        + "' dispatched for tenant '"
                                        + tenantId
                                        + "'"))
                .build();
    }
}
