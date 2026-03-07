package com.koostack.operio.rest.resource;

import com.koostack.operio.core.models.IdpUser;
import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.core.tenant.iam.TenantIamCommandService;
import com.koostack.operio.core.tenant.iam.TenantIamQueryService;
import com.koostack.operio.rest.auth.Roles;
import com.koostack.operio.rest.config.OperioOpenApiConfig;
import com.koostack.operio.rest.config.TenantIamHolder;
import com.koostack.operio.rest.dto.CommonDtos.ErrorResponse;
import com.koostack.operio.rest.dto.CommonDtos.MessageResponse;
import com.koostack.operio.rest.dto.IamDtos.*;
import com.koostack.operio.rest.interceptor.TenantScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.util.List;
import java.util.Set;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tenant-scoped IAM endpoints for managing users, service accounts, and authentication in the
 * tenant's Keycloak realm ({@code t_{tenantId}}).
 *
 * <p>The tenant is derived from the authenticated caller's identity context (set by the auth filter
 * and bound to {@link TenantContext}). Most endpoints require the {@code tenant-admin} role; login,
 * refresh, and logout are open to any authenticated tenant user.
 */
@Path("/api/v1/iam")
@TenantScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(ref = OperioOpenApiConfig.TAG_IAM)
@SecurityRequirement(name = OperioOpenApiConfig.SECURITY_SCHEME)
public class TenantIamResource {

    private static final Logger LOG = LoggerFactory.getLogger(TenantIamResource.class);

    @Inject TenantIamHolder tenantIam;

    // -----------------------------------------------------------------------
    // Users
    // -----------------------------------------------------------------------

    @POST
    @Path("/users")
    @Operation(
            operationId = "tenantRegisterUser",
            summary = "Register a tenant user",
            description = "Creates a new user in the tenant's Keycloak realm.")
    public Response registerUser(
            @Context SecurityContext sc, @Valid RegisterUserRequest request) {
        requireTenantAdmin(sc);
        String tenantId = requireTenantContext();
        requireIam();

        LOG.debug("POST /iam/users tenant={} email={}", tenantId, request.email());

        var result =
                tenantIam.commandService()
                        .registerUser(
                                tenantId,
                                request.email(),
                                request.fullName(),
                                request.roles(),
                                request.temporaryPassword());
        return Response.status(Response.Status.CREATED)
                .entity(UserRegisteredResponse.fromTenant(result))
                .build();
    }

    @GET
    @Path("/users")
    @Operation(operationId = "tenantListUsers", summary = "List tenant users")
    public Response listUsers(@Context SecurityContext sc) {
        requireTenantAdmin(sc);
        String tenantId = requireTenantContext();
        requireIam();

        LOG.debug("GET /iam/users tenant={}", tenantId);

        List<IdpUser> users = tenantIam.queryService().listUsers(tenantId);
        return Response.ok(users.stream().map(UserResponse::from).toList()).build();
    }

    @GET
    @Path("/users/{userId}")
    @Operation(operationId = "tenantGetUser", summary = "Get tenant user by ID")
    public Response getUser(
            @Context SecurityContext sc,
            @Parameter(description = "User ID") @PathParam("userId") String userId) {
        requireTenantAdmin(sc);
        String tenantId = requireTenantContext();
        requireIam();

        LOG.debug("GET /iam/users/{} tenant={}", userId, tenantId);

        IdpUser user = tenantIam.queryService().getUser(tenantId, userId);
        if (user == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(ErrorResponse.of("not_found", "User not found: " + userId))
                    .build();
        }
        return Response.ok(UserResponse.from(user)).build();
    }

    @PUT
    @Path("/users/{userId}/suspend")
    @Operation(operationId = "tenantSuspendUser", summary = "Suspend a tenant user")
    public Response suspendUser(
            @Context SecurityContext sc, @PathParam("userId") String userId) {
        requireTenantAdmin(sc);
        String tenantId = requireTenantContext();
        requireIam();

        LOG.debug("PUT /iam/users/{}/suspend tenant={}", userId, tenantId);
        tenantIam.commandService().suspendUser(tenantId, userId);
        return Response.ok(MessageResponse.of("User suspended")).build();
    }

    @PUT
    @Path("/users/{userId}/activate")
    @Operation(operationId = "tenantActivateUser", summary = "Activate a tenant user")
    public Response activateUser(
            @Context SecurityContext sc, @PathParam("userId") String userId) {
        requireTenantAdmin(sc);
        String tenantId = requireTenantContext();
        requireIam();

        LOG.debug("PUT /iam/users/{}/activate tenant={}", userId, tenantId);
        tenantIam.commandService().activateUser(tenantId, userId);
        return Response.ok(MessageResponse.of("User activated")).build();
    }

    @GET
    @Path("/users/{userId}/roles")
    @Operation(operationId = "tenantGetUserRoles", summary = "Get tenant user roles")
    public Response getUserRoles(
            @Context SecurityContext sc, @PathParam("userId") String userId) {
        requireTenantAdmin(sc);
        String tenantId = requireTenantContext();
        requireIam();

        Set<String> roles = tenantIam.queryService().getUserRoles(tenantId, userId);
        return Response.ok(roles).build();
    }

    @PUT
    @Path("/users/{userId}/roles")
    @Operation(operationId = "tenantAssignRole", summary = "Assign role to a tenant user")
    public Response assignRole(
            @Context SecurityContext sc,
            @PathParam("userId") String userId,
            @Valid RoleRequest request) {
        requireTenantAdmin(sc);
        String tenantId = requireTenantContext();
        requireIam();

        LOG.debug("PUT /iam/users/{}/roles role={} tenant={}", userId, request.role(), tenantId);
        tenantIam.commandService().assignRole(tenantId, userId, request.role());
        return Response.ok(MessageResponse.of("Role assigned")).build();
    }

    @DELETE
    @Path("/users/{userId}/roles/{role}")
    @Operation(operationId = "tenantRemoveRole", summary = "Remove role from a tenant user")
    public Response removeRole(
            @Context SecurityContext sc,
            @PathParam("userId") String userId,
            @PathParam("role") String role) {
        requireTenantAdmin(sc);
        String tenantId = requireTenantContext();
        requireIam();

        LOG.debug("DELETE /iam/users/{}/roles/{} tenant={}", userId, role, tenantId);
        tenantIam.commandService().removeRole(tenantId, userId, role);
        return Response.ok(MessageResponse.of("Role removed")).build();
    }

    // -----------------------------------------------------------------------
    // Service Accounts
    // -----------------------------------------------------------------------

    @POST
    @Path("/service-accounts")
    @Operation(
            operationId = "tenantCreateServiceAccount",
            summary = "Create a tenant service account",
            description =
                    "Creates a Keycloak confidential client with service account enabled in the"
                            + " tenant realm. The client secret is returned once — store it securely.")
    public Response createServiceAccount(
            @Context SecurityContext sc, @Valid CreateServiceAccountRequest request) {
        requireTenantAdmin(sc);
        String tenantId = requireTenantContext();
        requireIam();

        LOG.debug("POST /iam/service-accounts name={} tenant={}", request.name(), tenantId);

        var result =
                tenantIam.commandService()
                        .createServiceAccount(tenantId, request.name(), request.roles());
        return Response.status(Response.Status.CREATED)
                .entity(ServiceAccountCreatedResponse.fromTenant(result))
                .build();
    }

    @GET
    @Path("/service-accounts")
    @Operation(
            operationId = "tenantListServiceAccounts",
            summary = "List tenant service accounts")
    public Response listServiceAccounts(@Context SecurityContext sc) {
        requireTenantAdmin(sc);
        String tenantId = requireTenantContext();
        requireIam();

        LOG.debug("GET /iam/service-accounts tenant={}", tenantId);

        var accounts = tenantIam.queryService().listServiceAccounts(tenantId);
        return Response.ok(accounts.stream().map(ServiceAccountResponse::fromTenant).toList())
                .build();
    }

    @DELETE
    @Path("/service-accounts/{clientId}")
    @Operation(
            operationId = "tenantRevokeServiceAccount",
            summary = "Revoke a tenant service account")
    public Response revokeServiceAccount(
            @Context SecurityContext sc, @PathParam("clientId") String clientId) {
        requireTenantAdmin(sc);
        String tenantId = requireTenantContext();
        requireIam();

        LOG.debug("DELETE /iam/service-accounts/{} tenant={}", clientId, tenantId);
        tenantIam.commandService().revokeServiceAccount(tenantId, clientId);
        return Response.ok(MessageResponse.of("Service account revoked")).build();
    }

    // -----------------------------------------------------------------------
    // Authentication
    // -----------------------------------------------------------------------

    @POST
    @Path("/login")
    @Operation(
            operationId = "tenantLogin",
            summary = "Authenticate a tenant user",
            description =
                    "Resource Owner Password Credentials grant against the tenant's Keycloak"
                            + " realm.")
    public Response login(@Valid LoginRequest request) {
        String tenantId = requireTenantContext();
        requireIam();

        LOG.debug("POST /iam/login email={} tenant={}", request.email(), tenantId);

        try {
            var result =
                    tenantIam.queryService()
                            .authenticate(tenantId, request.email(), request.password());
            return Response.ok(AuthResponse.from(result)).build();
        } catch (com.koostack.operio.core.exception.OperioAuthException e) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(ErrorResponse.of("unauthorized", e.getMessage()))
                    .build();
        }
    }

    @POST
    @Path("/refresh")
    @Operation(operationId = "tenantRefresh", summary = "Refresh tenant access token")
    public Response refresh(@Valid RefreshTokenRequest request) {
        String tenantId = requireTenantContext();
        requireIam();

        LOG.debug("POST /iam/refresh tenant={}", tenantId);

        try {
            var result = tenantIam.queryService().refreshToken(tenantId, request.refreshToken());
            return Response.ok(AuthResponse.from(result)).build();
        } catch (com.koostack.operio.core.exception.OperioAuthException e) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(ErrorResponse.of("unauthorized", e.getMessage()))
                    .build();
        }
    }

    @POST
    @Path("/logout")
    @Operation(operationId = "tenantLogout", summary = "Logout a tenant user")
    public Response logout(@Valid LogoutRequest request) {
        String tenantId = requireTenantContext();
        requireIam();

        LOG.debug("POST /iam/logout tenant={}", tenantId);
        tenantIam.queryService().logout(tenantId, request.refreshToken());
        return Response.ok(MessageResponse.of("Logged out")).build();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void requireTenantAdmin(SecurityContext sc) {
        Roles.requireAnyRole(sc, Roles.TENANT_ADMIN, Roles.SUPER_ADMIN);
    }

    private String requireTenantContext() {
        String tenantId = TenantContext.get();
        if (tenantId == null || tenantId.isBlank()) {
            throw new com.koostack.operio.core.exception.OperioAuthException(
                    "Tenant context is required for tenant IAM operations");
        }
        return tenantId;
    }

    private void requireIam() {
        if (!tenantIam.isAvailable()) {
            throw new IllegalStateException(
                    "Tenant IAM services not available — is Keycloak configured?");
        }
    }
}
