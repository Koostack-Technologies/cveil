package com.koostack.operio.rest.resource;

import com.koostack.operio.core.models.IdpUser;
import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.rest.auth.Roles;
import com.koostack.operio.rest.config.OperioOpenApiConfig;
import com.koostack.operio.rest.config.TenantIamHolder;
import com.koostack.operio.rest.dto.CommonDtos.ErrorResponse;
import com.koostack.operio.rest.dto.CommonDtos.MessageResponse;
import com.koostack.operio.rest.dto.IamDtos.*;
import com.koostack.operio.rest.interceptor.TenantScoped;
import com.koostack.operio.tenant.iam.kc.*;
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
    public Response registerUser(@Context SecurityContext sc, @Valid RegisterUserRequest request) {
        requireTenantAdmin(sc);
        String tenantId = requireTenantContext();
        requireIam();

        LOG.debug(
                "POST /iam/users tenant={} username={} email={}",
                tenantId,
                request.username(),
                request.email());

        var result =
                (TenantRegisterUserCommandActuator.RegisterUserResult)
                        tenantIam
                                .commandDispatcher()
                                .dispatch(
                                        new TenantRegisterUserCommandActuator.RegisterUserCommand(
                                                tenantId,
                                                request.username(),
                                                request.email(),
                                                request.fullName(),
                                                request.roles(),
                                                request.temporaryPassword()));
        return Response.status(Response.Status.CREATED)
                .entity(
                        new UserRegisteredResponse(
                                result.username(), result.email(), result.realmId()))
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

        List<IdpUser> users = tenantIam.queryDispatcher()
                .dispatch(new TenantListUsersQueryActuator.TenantListUsersQuery(tenantId));
        return Response.ok(users.stream().map(UserResponse::from).toList()).build();
    }

    @GET
    @Path("/users/{username}")
    @Operation(operationId = "tenantGetUser", summary = "Get tenant user by username")
    public Response getUser(
            @Context SecurityContext sc,
            @Parameter(description = "Username") @PathParam("username") String username) {
        requireTenantAdmin(sc);
        String tenantId = requireTenantContext();
        requireIam();

        LOG.debug("GET /iam/users/{} tenant={}", username, tenantId);

        IdpUser user = tenantIam.queryDispatcher()
                .dispatch(new TenantFindUserByUsernameQueryActuator.TenantFindUserByUsernameQuery(
                        tenantId, username));
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
            operationId = "tenantFindUserByEmail",
            summary = "Find a tenant user by email address")
    public Response findUserByEmail(
            @Context SecurityContext sc,
            @Parameter(description = "Email address") @QueryParam("email") String email) {
        requireTenantAdmin(sc);
        String tenantId = requireTenantContext();
        requireIam();

        if (email == null || email.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ErrorResponse.of("bad_request", "email query parameter is required"))
                    .build();
        }

        LOG.debug("GET /iam/users/by-email email={} tenant={}", email, tenantId);

        IdpUser user = tenantIam.queryDispatcher()
                .dispatch(new TenantFindUserByEmailQueryActuator.TenantFindUserByEmailQuery(
                        tenantId, email));
        if (user == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(ErrorResponse.of("not_found", "User not found: " + email))
                    .build();
        }
        return Response.ok(UserResponse.from(user)).build();
    }

    @PUT
    @Path("/users/{username}/suspend")
    @Operation(operationId = "tenantSuspendUser", summary = "Suspend a tenant user")
    public Response suspendUser(
            @Context SecurityContext sc, @PathParam("username") String username) {
        requireTenantAdmin(sc);
        String tenantId = requireTenantContext();
        requireIam();

        LOG.debug("PUT /iam/users/{}/suspend tenant={}", username, tenantId);
        tenantIam.commandDispatcher()
                .dispatch(new TenantSuspendUserCommandActuator.SuspendUserCommand(
                        tenantId, username));
        return Response.ok(MessageResponse.of("User suspended")).build();
    }

    @PUT
    @Path("/users/{username}/activate")
    @Operation(operationId = "tenantActivateUser", summary = "Activate a tenant user")
    public Response activateUser(
            @Context SecurityContext sc, @PathParam("username") String username) {
        requireTenantAdmin(sc);
        String tenantId = requireTenantContext();
        requireIam();

        LOG.debug("PUT /iam/users/{}/activate tenant={}", username, tenantId);
        tenantIam.commandDispatcher()
                .dispatch(new TenantActivateUserCommandActuator.ActivateUserCommand(
                        tenantId, username));
        return Response.ok(MessageResponse.of("User activated")).build();
    }

    @GET
    @Path("/users/{username}/roles")
    @Operation(operationId = "tenantGetUserRoles", summary = "Get tenant user roles")
    public Response getUserRoles(
            @Context SecurityContext sc, @PathParam("username") String username) {
        requireTenantAdmin(sc);
        String tenantId = requireTenantContext();
        requireIam();

        Set<String> roles = tenantIam.queryDispatcher()
                .dispatch(
                        new TenantGetUserRolesByUsernameQueryActuator
                                .TenantGetUserRolesByUsernameQuery(tenantId, username));
        return Response.ok(roles).build();
    }

    @PUT
    @Path("/users/{username}/roles")
    @Operation(operationId = "tenantAssignRole", summary = "Assign role to a tenant user")
    public Response assignRole(
            @Context SecurityContext sc,
            @PathParam("username") String username,
            @Valid RoleRequest request) {
        requireTenantAdmin(sc);
        String tenantId = requireTenantContext();
        requireIam();

        LOG.debug("PUT /iam/users/{}/roles role={} tenant={}", username, request.role(), tenantId);
        tenantIam.commandDispatcher()
                .dispatch(new TenantAssignRoleCommandActuator.AssignRoleCommand(
                        tenantId, username, request.role()));
        return Response.ok(MessageResponse.of("Role assigned")).build();
    }

    @DELETE
    @Path("/users/{username}/roles/{role}")
    @Operation(operationId = "tenantRemoveRole", summary = "Remove role from a tenant user")
    public Response removeRole(
            @Context SecurityContext sc,
            @PathParam("username") String username,
            @PathParam("role") String role) {
        requireTenantAdmin(sc);
        String tenantId = requireTenantContext();
        requireIam();

        LOG.debug("DELETE /iam/users/{}/roles/{} tenant={}", username, role, tenantId);
        tenantIam.commandDispatcher()
                .dispatch(new TenantRemoveRoleCommandActuator.RemoveRoleCommand(
                        tenantId, username, role));
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
                (TenantCreateServiceAccountCommandActuator.CreateServiceAccountResult)
                        tenantIam
                                .commandDispatcher()
                                .dispatch(
                                        new TenantCreateServiceAccountCommandActuator
                                                .CreateServiceAccountCommand(
                                                tenantId, request.name(), request.roles()));
        return Response.status(Response.Status.CREATED)
                .entity(
                        new ServiceAccountCreatedResponse(
                                result.clientId(), result.clientSecret(), result.realmId()))
                .build();
    }

    @GET
    @Path("/service-accounts")
    @Operation(operationId = "tenantListServiceAccounts", summary = "List tenant service accounts")
    public Response listServiceAccounts(@Context SecurityContext sc) {
        requireTenantAdmin(sc);
        String tenantId = requireTenantContext();
        requireIam();

        LOG.debug("GET /iam/service-accounts tenant={}", tenantId);

        @SuppressWarnings("unchecked")
        List<com.koostack.operio.core.tenant.iam.TenantIamQueryService.ServiceAccountInfo>
                accounts = tenantIam.queryDispatcher()
                        .dispatch(new TenantListServiceAccountsQueryActuator
                                .TenantListServiceAccountsQuery(tenantId));
        return Response.ok(accounts.stream().map(ServiceAccountResponse::fromTenant).toList())
                .build();
    }

    @GET
    @Path("/service-accounts/{clientId}")
    @Operation(
            operationId = "tenantGetServiceAccount",
            summary = "Get a tenant service account by client ID")
    public Response getServiceAccount(
            @Context SecurityContext sc,
            @Parameter(description = "Client ID") @PathParam("clientId") String clientId) {
        requireTenantAdmin(sc);
        String tenantId = requireTenantContext();
        requireIam();

        LOG.debug("GET /iam/service-accounts/{} tenant={}", clientId, tenantId);

        var account = (com.koostack.operio.core.tenant.iam.TenantIamQueryService.ServiceAccountInfo)
                tenantIam.queryDispatcher()
                        .dispatch(new TenantGetServiceAccountQueryActuator
                                .TenantGetServiceAccountQuery(tenantId, clientId));
        if (account == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(ErrorResponse.of("not_found", "Service account not found: " + clientId))
                    .build();
        }
        return Response.ok(ServiceAccountResponse.fromTenant(account)).build();
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
        tenantIam.commandDispatcher()
                .dispatch(new TenantRevokeServiceAccountCommandActuator.RevokeServiceAccountCommand(
                        tenantId, clientId));
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
                    (com.koostack.operio.core.models.AuthResult)
                            tenantIam
                                    .commandDispatcher()
                                    .dispatch(
                                            new TenantAuthenticateCommandActuator
                                                    .AuthenticateCommand(
                                                    tenantId,
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
    @Operation(operationId = "tenantRefresh", summary = "Refresh tenant access token")
    public Response refresh(@Valid RefreshTokenRequest request) {
        String tenantId = requireTenantContext();
        requireIam();

        LOG.debug("POST /iam/refresh tenant={}", tenantId);

        try {
            var result =
                    (com.koostack.operio.core.models.AuthResult)
                            tenantIam
                                    .commandDispatcher()
                                    .dispatch(
                                            new TenantRefreshTokenCommandActuator
                                                    .RefreshTokenCommand(
                                                    tenantId, request.refreshToken()));
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
        tenantIam
                .commandDispatcher()
                .dispatch(
                        new TenantLogoutCommandActuator.LogoutCommand(
                                tenantId, request.refreshToken()));
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
