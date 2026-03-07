package com.koostack.operio.rest.resource;

import com.koostack.operio.core.models.IdpUser;
import com.koostack.operio.core.platform.iam.IamCommandService;
import com.koostack.operio.core.platform.iam.IamQueryService;
import com.koostack.operio.rest.auth.Roles;
import com.koostack.operio.rest.config.OperioOpenApiConfig;
import com.koostack.operio.rest.config.PlatformCqrsHolder;
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
 * Platform-level IAM endpoints for managing users, service accounts, and authentication in the
 * platform Keycloak realm ({@code operio-platform}).
 *
 * <p>All endpoints require the {@code super-admin} role. This resource operates on the platform
 * realm — for tenant-scoped IAM, see {@link TenantIamResource}.
 */
@Path("/api/v1/platform/iam")
@TenantScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(ref = OperioOpenApiConfig.TAG_IAM)
@SecurityRequirement(name = OperioOpenApiConfig.SECURITY_SCHEME)
public class PlatformIamResource {

    private static final Logger LOG = LoggerFactory.getLogger(PlatformIamResource.class);
    private static final String PLATFORM_REALM = "operio-platform";

    @Inject PlatformCqrsHolder cqrs;

    // -----------------------------------------------------------------------
    // Users
    // -----------------------------------------------------------------------

    @POST
    @Path("/users")
    @Operation(
            operationId = "platformRegisterUser",
            summary = "Register a platform user",
            description = "Creates a new user in the platform Keycloak realm.")
    public Response registerUser(
            @Context SecurityContext sc, @Valid RegisterUserRequest request) {
        requireSuperAdmin(sc);
        requireCqrs();

        LOG.debug("POST /platform/iam/users email={}", request.email());

        var result =
                cqrs.iamCommandService()
                        .registerUser(
                                PLATFORM_REALM,
                                request.email(),
                                request.fullName(),
                                request.roles(),
                                request.temporaryPassword());
        return Response.status(Response.Status.CREATED)
                .entity(UserRegisteredResponse.from(result))
                .build();
    }

    @GET
    @Path("/users")
    @Operation(operationId = "platformListUsers", summary = "List platform users")
    public Response listUsers(@Context SecurityContext sc) {
        requireSuperAdmin(sc);
        requireCqrs();

        LOG.debug("GET /platform/iam/users");

        List<IdpUser> users = cqrs.iamQueryService().listUsers(PLATFORM_REALM);
        return Response.ok(users.stream().map(UserResponse::from).toList()).build();
    }

    @GET
    @Path("/users/{userId}")
    @Operation(operationId = "platformGetUser", summary = "Get platform user by ID")
    public Response getUser(
            @Context SecurityContext sc,
            @Parameter(description = "User ID") @PathParam("userId") String userId) {
        requireSuperAdmin(sc);
        requireCqrs();

        LOG.debug("GET /platform/iam/users/{}", userId);

        IdpUser user = cqrs.iamQueryService().getUser(PLATFORM_REALM, userId);
        if (user == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(ErrorResponse.of("not_found", "User not found: " + userId))
                    .build();
        }
        return Response.ok(UserResponse.from(user)).build();
    }

    @PUT
    @Path("/users/{userId}/suspend")
    @Operation(operationId = "platformSuspendUser", summary = "Suspend a platform user")
    public Response suspendUser(
            @Context SecurityContext sc, @PathParam("userId") String userId) {
        requireSuperAdmin(sc);
        requireCqrs();

        LOG.debug("PUT /platform/iam/users/{}/suspend", userId);
        cqrs.iamCommandService().suspendUser(PLATFORM_REALM, userId);
        return Response.ok(MessageResponse.of("User suspended")).build();
    }

    @PUT
    @Path("/users/{userId}/activate")
    @Operation(operationId = "platformActivateUser", summary = "Activate a platform user")
    public Response activateUser(
            @Context SecurityContext sc, @PathParam("userId") String userId) {
        requireSuperAdmin(sc);
        requireCqrs();

        LOG.debug("PUT /platform/iam/users/{}/activate", userId);
        cqrs.iamCommandService().activateUser(PLATFORM_REALM, userId);
        return Response.ok(MessageResponse.of("User activated")).build();
    }

    @GET
    @Path("/users/{userId}/roles")
    @Operation(operationId = "platformGetUserRoles", summary = "Get platform user roles")
    public Response getUserRoles(
            @Context SecurityContext sc, @PathParam("userId") String userId) {
        requireSuperAdmin(sc);
        requireCqrs();

        Set<String> roles = cqrs.iamQueryService().getUserRoles(PLATFORM_REALM, userId);
        return Response.ok(roles).build();
    }

    @PUT
    @Path("/users/{userId}/roles")
    @Operation(operationId = "platformAssignRole", summary = "Assign role to a platform user")
    public Response assignRole(
            @Context SecurityContext sc,
            @PathParam("userId") String userId,
            @Valid RoleRequest request) {
        requireSuperAdmin(sc);
        requireCqrs();

        LOG.debug("PUT /platform/iam/users/{}/roles role={}", userId, request.role());
        cqrs.iamCommandService().assignRole(PLATFORM_REALM, userId, request.role());
        return Response.ok(MessageResponse.of("Role assigned")).build();
    }

    @DELETE
    @Path("/users/{userId}/roles/{role}")
    @Operation(operationId = "platformRemoveRole", summary = "Remove role from a platform user")
    public Response removeRole(
            @Context SecurityContext sc,
            @PathParam("userId") String userId,
            @PathParam("role") String role) {
        requireSuperAdmin(sc);
        requireCqrs();

        LOG.debug("DELETE /platform/iam/users/{}/roles/{}", userId, role);
        cqrs.iamCommandService().removeRole(PLATFORM_REALM, userId, role);
        return Response.ok(MessageResponse.of("Role removed")).build();
    }

    // -----------------------------------------------------------------------
    // Service Accounts
    // -----------------------------------------------------------------------

    @POST
    @Path("/service-accounts")
    @Operation(
            operationId = "platformCreateServiceAccount",
            summary = "Create a platform service account",
            description =
                    "Creates a Keycloak confidential client with service account enabled. "
                            + "The client secret is returned once — store it securely.")
    public Response createServiceAccount(
            @Context SecurityContext sc, @Valid CreateServiceAccountRequest request) {
        requireSuperAdmin(sc);
        requireCqrs();

        LOG.debug("POST /platform/iam/service-accounts name={}", request.name());

        var result =
                cqrs.iamCommandService()
                        .createServiceAccount(PLATFORM_REALM, request.name(), request.roles());
        return Response.status(Response.Status.CREATED)
                .entity(ServiceAccountCreatedResponse.from(result))
                .build();
    }

    @GET
    @Path("/service-accounts")
    @Operation(
            operationId = "platformListServiceAccounts",
            summary = "List platform service accounts")
    public Response listServiceAccounts(@Context SecurityContext sc) {
        requireSuperAdmin(sc);
        requireCqrs();

        LOG.debug("GET /platform/iam/service-accounts");

        var accounts = cqrs.iamQueryService().listServiceAccounts(PLATFORM_REALM);
        return Response.ok(accounts.stream().map(ServiceAccountResponse::from).toList()).build();
    }

    @DELETE
    @Path("/service-accounts/{clientId}")
    @Operation(
            operationId = "platformRevokeServiceAccount",
            summary = "Revoke a platform service account")
    public Response revokeServiceAccount(
            @Context SecurityContext sc, @PathParam("clientId") String clientId) {
        requireSuperAdmin(sc);
        requireCqrs();

        LOG.debug("DELETE /platform/iam/service-accounts/{}", clientId);
        cqrs.iamCommandService().revokeServiceAccount(PLATFORM_REALM, clientId);
        return Response.ok(MessageResponse.of("Service account revoked")).build();
    }

    // -----------------------------------------------------------------------
    // Authentication
    // -----------------------------------------------------------------------

    @POST
    @Path("/login")
    @Operation(
            operationId = "platformLogin",
            summary = "Authenticate a platform user",
            description = "Resource Owner Password Credentials grant against the platform realm.")
    public Response login(@Valid LoginRequest request) {
        requireCqrs();

        LOG.debug("POST /platform/iam/login email={}", request.email());

        try {
            var result =
                    cqrs.iamQueryService()
                            .authenticate(PLATFORM_REALM, request.email(), request.password());
            return Response.ok(AuthResponse.from(result)).build();
        } catch (com.koostack.operio.core.exception.OperioAuthException e) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(ErrorResponse.of("unauthorized", e.getMessage()))
                    .build();
        }
    }

    @POST
    @Path("/refresh")
    @Operation(operationId = "platformRefresh", summary = "Refresh platform access token")
    public Response refresh(@Valid RefreshTokenRequest request) {
        requireCqrs();

        LOG.debug("POST /platform/iam/refresh");

        try {
            var result =
                    cqrs.iamQueryService().refreshToken(PLATFORM_REALM, request.refreshToken());
            return Response.ok(AuthResponse.from(result)).build();
        } catch (com.koostack.operio.core.exception.OperioAuthException e) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(ErrorResponse.of("unauthorized", e.getMessage()))
                    .build();
        }
    }

    @POST
    @Path("/logout")
    @Operation(operationId = "platformLogout", summary = "Logout a platform user")
    public Response logout(@Context SecurityContext sc, @Valid LogoutRequest request) {
        requireCqrs();

        LOG.debug("POST /platform/iam/logout");
        cqrs.iamQueryService().logout(PLATFORM_REALM, request.refreshToken());
        return Response.ok(MessageResponse.of("Logged out")).build();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void requireSuperAdmin(SecurityContext sc) {
        Roles.requireRole(sc, Roles.SUPER_ADMIN);
    }

    private void requireCqrs() {
        if (!cqrs.isCqrsAvailable()) {
            throw new IllegalStateException(
                    "Platform CQRS services not available — is operio.mode=production?");
        }
    }
}
