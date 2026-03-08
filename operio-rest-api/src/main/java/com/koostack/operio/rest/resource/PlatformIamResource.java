package com.koostack.operio.rest.resource;

import com.koostack.operio.core.models.IdpUser;
import com.koostack.operio.core.platform.iam.IamQueryService;
import com.koostack.operio.platform.iam.jooq.*;
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
    public Response registerUser(@Context SecurityContext sc, @Valid RegisterUserRequest request) {
        requireSuperAdmin(sc);
        requireCqrs();

        LOG.debug(
                "POST /platform/iam/users username={}, email={}",
                request.username(),
                request.email());

        var command =
                new com.koostack.operio.platform.iam.jooq.RegisterUserCommandActuator
                        .RegisterUserCommand(
                        PLATFORM_REALM,
                        request.username(),
                        request.email(),
                        request.fullName(),
                        request.roles(),
                        request.temporaryPassword());
        com.koostack.operio.platform.iam.jooq.RegisterUserCommandActuator.RegisterUserResult
                result = cqrs.iamDispatcher().dispatch(command);
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

        List<IdpUser> users =
                cqrs.iamQueryDispatcher()
                        .dispatch(new ListUsersQueryActuator.ListUsersQuery(PLATFORM_REALM));
        return Response.ok(users.stream().map(UserResponse::from).toList()).build();
    }

    @GET
    @Path("/users/{username}")
    @Operation(operationId = "platformGetUser", summary = "Get platform user by username")
    public Response getUser(
            @Context SecurityContext sc,
            @Parameter(description = "Username") @PathParam("username") String username) {
        requireSuperAdmin(sc);
        requireCqrs();

        LOG.debug("GET /platform/iam/users/{}", username);

        IdpUser user =
                cqrs.iamQueryDispatcher()
                        .dispatch(
                                new FindUserByUsernameQueryActuator.FindUserByUsernameQuery(
                                        PLATFORM_REALM, username));
        if (user == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(ErrorResponse.of("not_found", "User not found: " + username))
                    .build();
        }
        return Response.ok(UserResponse.from(user)).build();
    }

    @GET
    @Path("/users/by-email")
    @Operation(
            operationId = "platformFindUserByEmail",
            summary = "Find a platform user by email address")
    public Response findUserByEmail(
            @Context SecurityContext sc,
            @Parameter(description = "Email address") @QueryParam("email") String email) {
        requireSuperAdmin(sc);
        requireCqrs();

        if (email == null || email.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ErrorResponse.of("bad_request", "email query parameter is required"))
                    .build();
        }

        LOG.debug("GET /platform/iam/users/by-email email={}", email);

        IdpUser user =
                cqrs.iamQueryDispatcher()
                        .dispatch(
                                new FindUserByEmailQueryActuator.FindUserByEmailQuery(
                                        PLATFORM_REALM, email));
        if (user == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(ErrorResponse.of("not_found", "User not found: " + email))
                    .build();
        }
        return Response.ok(UserResponse.from(user)).build();
    }

    @PUT
    @Path("/users/{username}/suspend")
    @Operation(operationId = "platformSuspendUser", summary = "Suspend a platform user")
    public Response suspendUser(
            @Context SecurityContext sc, @PathParam("username") String username) {
        requireSuperAdmin(sc);
        requireCqrs();

        LOG.debug("PUT /platform/iam/users/{}/suspend", username);
        cqrs.iamDispatcher()
                .dispatch(
                        new com.koostack.operio.platform.iam.jooq.SuspendUserCommandActuator
                                .SuspendUserCommand(PLATFORM_REALM, username));
        return Response.ok(MessageResponse.of("User suspended")).build();
    }

    @PUT
    @Path("/users/{username}/activate")
    @Operation(operationId = "platformActivateUser", summary = "Activate a platform user")
    public Response activateUser(
            @Context SecurityContext sc, @PathParam("username") String username) {
        requireSuperAdmin(sc);
        requireCqrs();

        LOG.debug("PUT /platform/iam/users/{}/activate", username);
        cqrs.iamDispatcher()
                .dispatch(
                        new com.koostack.operio.platform.iam.jooq.ActivateUserCommandActuator
                                .ActivateUserCommand(PLATFORM_REALM, username));
        return Response.ok(MessageResponse.of("User activated")).build();
    }

    @GET
    @Path("/users/{username}/roles")
    @Operation(operationId = "platformGetUserRoles", summary = "Get platform user roles")
    public Response getUserRoles(
            @Context SecurityContext sc, @PathParam("username") String username) {
        requireSuperAdmin(sc);
        requireCqrs();

        Set<String> roles =
                cqrs.iamQueryDispatcher()
                        .dispatch(
                                new GetUserRolesByUsernameQueryActuator
                                        .GetUserRolesByUsernameQuery(PLATFORM_REALM, username));
        return Response.ok(roles).build();
    }

    @PUT
    @Path("/users/{username}/roles")
    @Operation(operationId = "platformAssignRole", summary = "Assign role to a platform user")
    public Response assignRole(
            @Context SecurityContext sc,
            @PathParam("username") String username,
            @Valid RoleRequest request) {
        requireSuperAdmin(sc);
        requireCqrs();

        LOG.debug("PUT /platform/iam/users/{}/roles role={}", username, request.role());
        cqrs.iamDispatcher()
                .dispatch(
                        new com.koostack.operio.platform.iam.jooq.AssignRoleCommandActuator
                                .AssignRoleCommand(
                                PLATFORM_REALM, username, request.role()));
        return Response.ok(MessageResponse.of("Role assigned")).build();
    }

    @DELETE
    @Path("/users/{username}/roles/{role}")
    @Operation(operationId = "platformRemoveRole", summary = "Remove role from a platform user")
    public Response removeRole(
            @Context SecurityContext sc,
            @PathParam("username") String username,
            @PathParam("role") String role) {
        requireSuperAdmin(sc);
        requireCqrs();

        LOG.debug("DELETE /platform/iam/users/{}/roles/{}", username, role);
        cqrs.iamDispatcher()
                .dispatch(
                        new com.koostack.operio.platform.iam.jooq.RemoveRoleCommandActuator
                                .RemoveRoleCommand(PLATFORM_REALM, username, role));
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

        var command =
                new com.koostack.operio.platform.iam.jooq.CreateServiceAccountCommandActuator
                        .CreateServiceAccountCommand(
                        PLATFORM_REALM, request.name(), request.roles());
        com.koostack.operio.platform.iam.jooq.CreateServiceAccountCommandActuator
                        .CreateServiceAccountResult
                result = cqrs.iamDispatcher().dispatch(command);
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

        var accounts =
                cqrs.iamQueryDispatcher()
                        .<ListServiceAccountsQueryActuator.ListServiceAccountsQuery,
                                java.util.List<IamQueryService.ServiceAccountInfo>>
                                dispatch(
                                        new ListServiceAccountsQueryActuator
                                                .ListServiceAccountsQuery(PLATFORM_REALM));
        return Response.ok(accounts.stream().map(ServiceAccountResponse::from).toList()).build();
    }

    @GET
    @Path("/service-accounts/{clientId}")
    @Operation(
            operationId = "platformGetServiceAccount",
            summary = "Get a platform service account by client ID")
    public Response getServiceAccount(
            @Context SecurityContext sc,
            @Parameter(description = "Client ID") @PathParam("clientId") String clientId) {
        requireSuperAdmin(sc);
        requireCqrs();

        LOG.debug("GET /platform/iam/service-accounts/{}", clientId);

        var account =
                cqrs.iamQueryDispatcher()
                        .<GetServiceAccountQueryActuator.GetServiceAccountQuery,
                                IamQueryService.ServiceAccountInfo>
                                dispatch(
                                        new GetServiceAccountQueryActuator.GetServiceAccountQuery(
                                                PLATFORM_REALM, clientId));
        if (account == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(ErrorResponse.of("not_found", "Service account not found: " + clientId))
                    .build();
        }
        return Response.ok(ServiceAccountResponse.from(account)).build();
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
        cqrs.iamDispatcher()
                .dispatch(
                        new com.koostack.operio.platform.iam.jooq
                                .RevokeServiceAccountCommandActuator.RevokeServiceAccountCommand(
                                PLATFORM_REALM, clientId));
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
                    (com.koostack.operio.core.models.AuthResult)
                            cqrs.iamDispatcher()
                                    .dispatch(
                                            new com.koostack.operio.platform.iam.jooq
                                                    .AuthenticateCommandActuator
                                                    .AuthenticateCommand(
                                                    PLATFORM_REALM,
                                                    request.email(),
                                                    request.password()));
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
                    (com.koostack.operio.core.models.AuthResult)
                            cqrs.iamDispatcher()
                                    .dispatch(
                                            new com.koostack.operio.platform.iam.jooq
                                                    .RefreshTokenCommandActuator
                                                    .RefreshTokenCommand(
                                                    PLATFORM_REALM, request.refreshToken()));
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
        cqrs.iamDispatcher()
                .dispatch(
                        new com.koostack.operio.platform.iam.jooq.LogoutCommandActuator
                                .LogoutCommand(PLATFORM_REALM, request.refreshToken()));
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
