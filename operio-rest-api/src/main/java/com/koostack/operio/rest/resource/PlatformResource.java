package com.koostack.operio.rest.resource;

import com.koostack.operio.core.Models.ApiKeyInfo;
import com.koostack.operio.core.Models.TenantInfo;
import com.koostack.operio.core.service.PlatformAdminService;
import com.koostack.operio.plugin.platform.jooq.JooqPlatformAdminService;
import com.koostack.operio.rest.auth.Roles;
import com.koostack.operio.rest.config.OperioOpenApiConfig;
import com.koostack.operio.rest.dto.CommonDtos.ErrorResponse;
import com.koostack.operio.rest.dto.CommonDtos.MessageResponse;
import com.koostack.operio.rest.dto.PlatformDtos.ApiKeyCreatedResponse;
import com.koostack.operio.rest.dto.PlatformDtos.ApiKeyResponse;
import com.koostack.operio.rest.dto.PlatformDtos.CreateApiKeyRequest;
import com.koostack.operio.rest.dto.PlatformDtos.CreateTenantRequest;
import com.koostack.operio.rest.dto.PlatformDtos.TenantResponse;
import com.koostack.operio.rest.dto.PlatformDtos.UpdatePlanRequest;
import com.koostack.operio.rest.interceptor.TenantScoped;
import com.koostack.operio.rest.lifecycle.BootstrapKeyLifecycleService;
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
import jakarta.ws.rs.QueryParam;
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
 * JAX-RS resource for platform-level administration: tenant lifecycle
 * and API key management.
 *
 * All endpoints require the super-admin role.
 */
@Path("/api/v1/platform")
@TenantScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(ref = OperioOpenApiConfig.TAG_PLATFORM)
@SecurityRequirement(name = OperioOpenApiConfig.SECURITY_SCHEME)
public class PlatformResource {

    private static final Logger LOG = LoggerFactory.getLogger(
        PlatformResource.class
    );

    @Inject
    @SuppressWarnings("rawtypes")
    PlatformAdminService platformAdminService;

    @Inject
    BootstrapKeyLifecycleService bootstrapLifecycle;

    @Inject
    RateLimitService rateLimitService;

    // -------------------------------------------------------------------
    // Role enforcement
    // -------------------------------------------------------------------

    private void requireSuperAdmin(SecurityContext securityContext) {
        Roles.requireRole(securityContext, Roles.SUPER_ADMIN);
    }

    private void requirePlatformAdmin() {
        if (platformAdminService == null) {
            throw new IllegalStateException(
                "Platform administration is not available in embedded mode. " +
                    "Set operio.mode=production to enable tenant management."
            );
        }
    }

    // -------------------------------------------------------------------
    // Tenants
    // -------------------------------------------------------------------

    @POST
    @Path("/tenants")
    @Operation(
        operationId = "createTenant",
        summary = "Create a new tenant",
        description = "Provisions a new tenant on the platform: creates a dedicated " +
            "Postgres schema, Redis ACL user, and inserts the tenant row. " +
            "Requires the super-admin role."
    )
    @APIResponses(
        {
            @APIResponse(
                responseCode = "201",
                description = "Tenant created successfully",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = TenantResponse.class)
                )
            ),
            @APIResponse(
                responseCode = "400",
                description = "Validation error (invalid tenantId, plan, etc.)",
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
            @APIResponse(
                responseCode = "500",
                description = "Platform admin not available (embedded mode)",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ErrorResponse.class)
                )
            ),
        }
    )
    public Response createTenant(
        @Context SecurityContext securityContext,
        @Valid CreateTenantRequest request
    ) {
        requireSuperAdmin(securityContext);
        requirePlatformAdmin();

        LOG.info(
            "POST /platform/tenants - creating tenant={}, plan={}",
            request.tenantId(),
            request.plan()
        );

        platformAdminService.createTenant(
            request.tenantId(),
            request.displayName(),
            request.plan()
        );

        TenantInfo tenant = platformAdminService.getTenant(request.tenantId());

        return Response.status(Response.Status.CREATED)
            .entity(TenantResponse.fromModel(tenant))
            .build();
    }

    @GET
    @Path("/tenants")
    @Operation(
        operationId = "listTenants",
        summary = "List all tenants",
        description = "Returns all tenants on the platform with their metadata " +
            "and plan-level limits. Requires the super-admin role."
    )
    @APIResponses(
        {
            @APIResponse(
                responseCode = "200",
                description = "List of all tenants",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(
                        type = SchemaType.ARRAY,
                        implementation = TenantResponse.class
                    )
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
    public Response listTenants(@Context SecurityContext securityContext) {
        requireSuperAdmin(securityContext);
        requirePlatformAdmin();

        LOG.debug("GET /platform/tenants");

        List<TenantInfo> tenants = platformAdminService.listTenants();

        List<TenantResponse> dtos = tenants
            .stream()
            .map(TenantResponse::fromModel)
            .toList();

        return Response.ok(dtos).build();
    }

    @GET
    @Path("/tenants/{id}")
    @Operation(
        operationId = "getTenant",
        summary = "Get tenant details",
        description = "Retrieves full information about a specific tenant including " +
            "plan-level limits and status. Requires the super-admin role."
    )
    @APIResponses(
        {
            @APIResponse(
                responseCode = "200",
                description = "Tenant details",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = TenantResponse.class)
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
    public Response getTenant(
        @Context SecurityContext securityContext,
        @Parameter(
            description = "Unique tenant identifier",
            required = true,
            example = "acme_corp"
        ) @PathParam("id") String tenantId
    ) {
        requireSuperAdmin(securityContext);
        requirePlatformAdmin();

        LOG.debug("GET /platform/tenants/{}", tenantId);

        TenantInfo tenant = platformAdminService.getTenant(tenantId);

        return Response.ok(TenantResponse.fromModel(tenant)).build();
    }

    @DELETE
    @Path("/tenants/{id}")
    @Operation(
        operationId = "deleteTenant",
        summary = "Delete a tenant",
        description = "Deletes a tenant and all associated resources: Postgres schema, " +
            "Redis ACL user, and all API keys. This action is irreversible. " +
            "Requires the super-admin role."
    )
    @APIResponses(
        {
            @APIResponse(
                responseCode = "200",
                description = "Tenant deleted successfully",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = MessageResponse.class)
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
    public Response deleteTenant(
        @Context SecurityContext securityContext,
        @Parameter(
            description = "Unique tenant identifier",
            required = true,
            example = "acme_corp"
        ) @PathParam("id") String tenantId
    ) {
        requireSuperAdmin(securityContext);
        requirePlatformAdmin();

        LOG.info("DELETE /platform/tenants/{}", tenantId);

        platformAdminService.deleteTenant(tenantId);

        return Response.ok(
            MessageResponse.of("Tenant '" + tenantId + "' deleted successfully")
        ).build();
    }

    // -------------------------------------------------------------------
    // API Keys
    // -------------------------------------------------------------------

    @POST
    @Path("/api-keys")
    @Operation(
        operationId = "createApiKey",
        summary = "Create an API key for a tenant",
        description = "Generates a new API key for the specified tenant with the given " +
            "roles. The raw key is returned only once in the response body and " +
            "is never stored in plaintext. Requires the super-admin role."
    )
    @APIResponses(
        {
            @APIResponse(
                responseCode = "201",
                description = "API key created (raw key returned once)",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(
                        implementation = ApiKeyCreatedResponse.class
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
                description = "Missing required role (super-admin)",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ErrorResponse.class)
                )
            ),
        }
    )
    public Response createApiKey(
        @Context SecurityContext securityContext,
        @Valid CreateApiKeyRequest request
    ) {
        requireSuperAdmin(securityContext);
        requirePlatformAdmin();

        LOG.info(
            "POST /platform/api-keys - tenant={}, subject={}",
            request.tenantId(),
            request.subject()
        );

        String rawKey = platformAdminService.createApiKey(
            request.tenantId(),
            request.subject(),
            request.roles()
        );

        String keyPrefix =
            rawKey.length() >= 8 ? rawKey.substring(0, 8) : rawKey;

        ApiKeyCreatedResponse response = new ApiKeyCreatedResponse(
            rawKey,
            keyPrefix,
            request.tenantId(),
            request.subject(),
            request.roles()
        );

        bootstrapLifecycle.markBootstrapConsumed();

        return Response.status(Response.Status.CREATED)
            .entity(response)
            .build();
    }

    @GET
    @Path("/api-keys")
    @Operation(
        operationId = "listApiKeys",
        summary = "List API keys for a tenant",
        description = "Returns metadata for all API keys belonging to the specified " +
            "tenant. Never returns the raw key or its hash. " +
            "Requires the super-admin role."
    )
    @APIResponses(
        {
            @APIResponse(
                responseCode = "200",
                description = "List of API key metadata",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(
                        type = SchemaType.ARRAY,
                        implementation = ApiKeyResponse.class
                    )
                )
            ),
            @APIResponse(
                responseCode = "400",
                description = "Missing tenantId query parameter",
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
    public Response listApiKeys(
        @Context SecurityContext securityContext,
        @Parameter(
            description = "Tenant identifier to list API keys for",
            required = true,
            example = "acme_corp"
        ) @QueryParam("tenantId") String tenantId
    ) {
        requireSuperAdmin(securityContext);
        requirePlatformAdmin();

        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException(
                "Query parameter 'tenantId' is required"
            );
        }

        LOG.debug("GET /platform/api-keys?tenantId={}", tenantId);

        List<ApiKeyInfo> keys = platformAdminService.listApiKeys(tenantId);

        List<ApiKeyResponse> dtos = keys
            .stream()
            .map(ApiKeyResponse::fromModel)
            .toList();

        return Response.ok(dtos).build();
    }

    @DELETE
    @Path("/api-keys/{prefix}")
    @Operation(
        operationId = "revokeApiKey",
        summary = "Revoke an API key by prefix",
        description = "Revokes an API key identified by its 8-character prefix. " +
            "The key becomes immediately unusable. Requires the super-admin role."
    )
    @APIResponses(
        {
            @APIResponse(
                responseCode = "200",
                description = "API key revoked successfully",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = MessageResponse.class)
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
                description = "API key not found",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ErrorResponse.class)
                )
            ),
        }
    )
    public Response revokeApiKey(
        @Context SecurityContext securityContext,
        @Parameter(
            description = "The 8-character key prefix used for identification",
            required = true,
            example = "opr_live"
        ) @PathParam("prefix") String keyPrefix
    ) {
        requireSuperAdmin(securityContext);
        requirePlatformAdmin();

        LOG.info("DELETE /platform/api-keys/{}", keyPrefix);

        platformAdminService.revokeApiKey(keyPrefix);

        return Response.ok(
            MessageResponse.of("API key '" + keyPrefix + "' revoked")
        ).build();
    }

    // -------------------------------------------------------------------
    // Plan management
    // -------------------------------------------------------------------

    @PUT
    @Path("/tenants/{id}/plan")
    @Operation(
        operationId = "updateTenantPlan",
        summary = "Update a tenant's subscription plan",
        description = "Changes the tenant's plan and recalculates all plan-level " +
            "rate limits (evaluation quota, subject limit, burst cap). " +
            "The new limits take effect immediately — cached rate-limit " +
            "data is evicted so the next request uses the updated values. " +
            "Requires the super-admin role.\n\n" +
            "This endpoint is what a Stripe webhook handler would call " +
            "after a subscription change."
    )
    @APIResponses(
        {
            @APIResponse(
                responseCode = "200",
                description = "Plan updated successfully",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = TenantResponse.class)
                )
            ),
            @APIResponse(
                responseCode = "400",
                description = "Validation error (invalid plan)",
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
    public Response updateTenantPlan(
        @Context SecurityContext securityContext,
        @Parameter(
            description = "Unique tenant identifier",
            required = true,
            example = "acme_corp"
        ) @PathParam("id") String tenantId,
        @Valid UpdatePlanRequest request
    ) {
        requireSuperAdmin(securityContext);
        requirePlatformAdmin();

        LOG.info(
            "PUT /platform/tenants/{}/plan - newPlan={}",
            tenantId,
            request.plan()
        );

        // Update plan + limits in Postgres
        if (
            platformAdminService instanceof JooqPlatformAdminService jooqAdmin
        ) {
            String actor =
                securityContext.getUserPrincipal() != null
                    ? securityContext.getUserPrincipal().getName()
                    : "super-admin";
            int updated = jooqAdmin
                .tenantRepository()
                .updatePlan(tenantId, request.plan(), actor);

            if (updated == 0) {
                return Response.status(Response.Status.NOT_FOUND)
                    .entity(
                        ErrorResponse.of(
                            "not_found",
                            "Tenant '" + tenantId + "' not found"
                        )
                    )
                    .build();
            }
        } else {
            throw new IllegalStateException(
                "Plan management requires the jOOQ platform admin service"
            );
        }

        // Evict cached rate limits so new limits take effect immediately
        rateLimitService.evictTenantCache(tenantId);

        // Re-fetch and return the updated tenant
        TenantInfo tenant = platformAdminService.getTenant(tenantId);

        return Response.ok(TenantResponse.fromModel(tenant)).build();
    }
}
