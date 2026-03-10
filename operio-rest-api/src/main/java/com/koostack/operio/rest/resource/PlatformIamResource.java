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
 * Unified IAM endpoints for managing users, organizations, service accounts, and authentication in
 * the single Keycloak realm ({@code operio}).
 *
 * <p>User registration optionally creates a Keycloak Organization (tenant) and provisions Redis
 * infrastructure via the {@link RegisterUserOrchestrator}. Organization membership determines
 * tenant scope for consent operations.
 *
 * <p>Most management endpoints require the {@code super-admin} role. Authentication endpoints
 * (login, refresh, logout) are open.
 */
@Path("/api/v1/platform/iam")
@TenantScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(ref = OperioOpenApiConfig.TAG_IAM)
@SecurityRequirement(name = OperioOpenApiConfig.SECURITY_SCHEME)
public class PlatformIamResource {

    private static final Logger LOG = LoggerFactory.getLogger(PlatformIamResource.class);
    private static final String REALM = "operio";

    @Inject PlatformCqrsHolder cqrs;

    // -----------------------------------------------------------------------
    // Users
    // -----------------------------------------------------------------------

    @POST
    @Path("/users")
    @Operation(
            operationId = "registerUser",
            summary = "Register a user",
            description =
                    "Creates a new user in Keycloak. If organizationName is provided, also creates"
                            + " the organization, adds the user as a member, and provisions Redis"
                            + " infrastructure.")
    public Response registerUser(@Context SecurityContext sc, @Valid RegisterUserRequest request) {
        requireSuperAdmin(sc);
        requireCqrs();

        LOG.debug(
                "POST /platform/iam/users username={}, email={}, org={}",
                request.username(),
                request.email(),
                request.organizationName());

        var command =
                new RegisterUserOrchestrator.RegisterUserWithOrgCommand(
                        REALM,
                        request.username(),
                        request.email(),
                        request.fullName(),
                        request.roles(),
                        request.temporaryPassword(),
                        request.organizationName(),
                        request.organizationDisplayName());
        RegisterUserOrchestrator.RegisterUserWithOrgResult result =
                cqrs.iamDispatcher().dispatch(command);
        return Response.status(Response.Status.CREATED)
                .entity(UserRegisteredResponse.from(result))
                .build();
    }

    @GET
    @Path("/users")
    @Operation(operationId = "listUsers", summary = "List users")
    public Response listUsers(@Context SecurityContext sc) {
        requireSuperAdmin(sc);
        requireCqrs();

        LOG.debug("GET /platform/iam/users");

        List<IdpUser> users =
                cqrs.iamQueryDispatcher()
                        .dispatch(new ListUsersQueryActuator.ListUsersQuery(REALM));
        return Response.ok(users.stream().map(UserResponse::from).toList()).build();
    }

    @GET
    @Path("/users/{username}")
    @Operation(operationId = "getUser", summary = "Get user by username")
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
                                        REALM, username));
        if (user == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(ErrorResponse.of("not_found", "User not found: " + username))
                    .build();
        }
        return Response.ok(UserResponse.from(user)).build();
    }

    @GET
    @Path("/users/by-email")
    @Operation(operationId = "findUserByEmail", summary = "Find a user by email address")
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
                                        REALM, email));
        if (user == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(ErrorResponse.of("not_found", "User not found: " + email))
                    .build();
        }
        return Response.ok(UserResponse.from(user)).build();
    }

    @PUT
    @Path("/users/{username}/suspend")
    @Operation(operationId = "suspendUser", summary = "Suspend a user")
    public Response suspendUser(
            @Context SecurityContext sc, @PathParam("username") String username) {
        requireSuperAdmin(sc);
        requireCqrs();

        LOG.debug("PUT /platform/iam/users/{}/suspend", username);
        cqrs.iamDispatcher()
                .dispatch(new SuspendUserCommandActuator.SuspendUserCommand(REALM, username));
        return Response.ok(MessageResponse.of("User suspended")).build();
    }

    @PUT
    @Path("/users/{username}/activate")
    @Operation(operationId = "activateUser", summary = "Activate a user")
    public Response activateUser(
            @Context SecurityContext sc, @PathParam("username") String username) {
        requireSuperAdmin(sc);
        requireCqrs();

        LOG.debug("PUT /platform/iam/users/{}/activate", username);
        cqrs.iamDispatcher()
                .dispatch(new ActivateUserCommandActuator.ActivateUserCommand(REALM, username));
        return Response.ok(MessageResponse.of("User activated")).build();
    }

    @GET
    @Path("/users/{username}/roles")
    @Operation(operationId = "getUserRoles", summary = "Get user roles")
    public Response getUserRoles(
            @Context SecurityContext sc, @PathParam("username") String username) {
        requireSuperAdmin(sc);
        requireCqrs();

        Set<String> roles =
                cqrs.iamQueryDispatcher()
                        .dispatch(
                                new GetUserRolesByUsernameQueryActuator.GetUserRolesByUsernameQuery(
                                        REALM, username));
        return Response.ok(roles).build();
    }

    @PUT
    @Path("/users/{username}/roles")
    @Operation(operationId = "assignRole", summary = "Assign role to a user")
    public Response assignRole(
            @Context SecurityContext sc,
            @PathParam("username") String username,
            @Valid RoleRequest request) {
        requireSuperAdmin(sc);
        requireCqrs();

        LOG.debug("PUT /platform/iam/users/{}/roles role={}", username, request.role());
        cqrs.iamDispatcher()
                .dispatch(
                        new AssignRoleCommandActuator.AssignRoleCommand(
                                REALM, username, request.role()));
        return Response.ok(MessageResponse.of("Role assigned")).build();
    }

    @DELETE
    @Path("/users/{username}/roles/{role}")
    @Operation(operationId = "removeRole", summary = "Remove role from a user")
    public Response removeRole(
            @Context SecurityContext sc,
            @PathParam("username") String username,
            @PathParam("role") String role) {
        requireSuperAdmin(sc);
        requireCqrs();

        LOG.debug("DELETE /platform/iam/users/{}/roles/{}", username, role);
        cqrs.iamDispatcher()
                .dispatch(new RemoveRoleCommandActuator.RemoveRoleCommand(REALM, username, role));
        return Response.ok(MessageResponse.of("Role removed")).build();
    }

    // -----------------------------------------------------------------------
    // Organizations
    // -----------------------------------------------------------------------

    @POST
    @Path("/organizations")
    @Operation(
            operationId = "createOrganization",
            summary = "Create an organization",
            description =
                    "Creates a Keycloak Organization (tenant). Organizations scope consent"
                            + " data and Redis infrastructure.")
    public Response createOrganization(
            @Context SecurityContext sc, @Valid CreateOrganizationRequest request) {
        requireSuperAdmin(sc);
        requireCqrs();

        LOG.debug("POST /platform/iam/organizations name={}", request.name());

        var command =
                new CreateOrganizationCommandActuator.CreateOrganizationCommand(
                        request.name(), request.displayName());
        CreateOrganizationCommandActuator.CreateOrganizationResult result =
                cqrs.iamDispatcher().dispatch(command);
        return Response.status(Response.Status.CREATED)
                .entity(OrganizationCreatedResponse.from(result))
                .build();
    }

    @GET
    @Path("/organizations")
    @Operation(operationId = "listOrganizations", summary = "List organizations")
    public Response listOrganizations(
            @Context SecurityContext sc,
            @QueryParam("offset") @DefaultValue("0") int offset,
            @QueryParam("limit") @DefaultValue("100") int limit) {
        requireSuperAdmin(sc);
        requireCqrs();

        LOG.debug("GET /platform/iam/organizations offset={} limit={}", offset, limit);

        List<IamQueryService.OrganizationInfo> orgs =
                cqrs.iamQueryDispatcher()
                        .dispatch(
                                new ListOrganizationsQueryActuator.ListOrganizationsQuery(
                                        offset, limit));
        return Response.ok(orgs.stream().map(OrganizationResponse::from).toList()).build();
    }

    @GET
    @Path("/organizations/{orgId}")
    @Operation(operationId = "getOrganization", summary = "Get organization by ID")
    public Response getOrganization(@Context SecurityContext sc, @PathParam("orgId") String orgId) {
        requireSuperAdmin(sc);
        requireCqrs();

        LOG.debug("GET /platform/iam/organizations/{}", orgId);

        IamQueryService.OrganizationInfo org =
                cqrs.iamQueryDispatcher()
                        .dispatch(new GetOrganizationQueryActuator.GetOrganizationQuery(orgId));
        if (org == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(ErrorResponse.of("not_found", "Organization not found: " + orgId))
                    .build();
        }
        return Response.ok(OrganizationResponse.from(org)).build();
    }

    @GET
    @Path("/organizations/{orgId}/members")
    @Operation(operationId = "listOrgMembers", summary = "List organization members")
    public Response listOrgMembers(
            @Context SecurityContext sc,
            @PathParam("orgId") String orgId,
            @QueryParam("offset") @DefaultValue("0") int offset,
            @QueryParam("limit") @DefaultValue("100") int limit) {
        requireSuperAdmin(sc);
        requireCqrs();

        LOG.debug("GET /platform/iam/organizations/{}/members", orgId);

        List<IamQueryService.OrgMemberInfo> members =
                cqrs.iamQueryDispatcher()
                        .dispatch(
                                new ListOrgMembersQueryActuator.ListOrgMembersQuery(
                                        orgId, offset, limit));
        return Response.ok(members.stream().map(OrgMemberResponse::from).toList()).build();
    }

    @POST
    @Path("/organizations/{orgId}/members")
    @Operation(operationId = "addOrgMember", summary = "Add a member to an organization")
    public Response addOrgMember(
            @Context SecurityContext sc,
            @PathParam("orgId") String orgId,
            @Valid AddOrgMemberRequest request) {
        requireSuperAdmin(sc);
        requireCqrs();

        LOG.debug("POST /platform/iam/organizations/{}/members userId={}", orgId, request.userId());

        cqrs.iamDispatcher()
                .dispatch(
                        new AddOrgMemberCommandActuator.AddOrgMemberCommand(
                                orgId, null, request.userId(), request.username()));
        return Response.status(Response.Status.CREATED)
                .entity(MessageResponse.of("Member added to organization"))
                .build();
    }

    @DELETE
    @Path("/organizations/{orgId}/members/{userId}")
    @Operation(operationId = "removeOrgMember", summary = "Remove a member from an organization")
    public Response removeOrgMember(
            @Context SecurityContext sc,
            @PathParam("orgId") String orgId,
            @PathParam("userId") String userId) {
        requireSuperAdmin(sc);
        requireCqrs();

        LOG.debug("DELETE /platform/iam/organizations/{}/members/{}", orgId, userId);

        cqrs.iamDispatcher()
                .dispatch(
                        new RemoveOrgMemberCommandActuator.RemoveOrgMemberCommand(
                                orgId, null, userId));
        return Response.ok(MessageResponse.of("Member removed from organization")).build();
    }

    // -----------------------------------------------------------------------
    // Service Accounts
    // -----------------------------------------------------------------------

    @POST
    @Path("/service-accounts")
    @Operation(
            operationId = "createServiceAccount",
            summary = "Create a service account",
            description =
                    "Creates a Keycloak confidential client with service account enabled. "
                            + "The client secret is returned once — store it securely.")
    public Response createServiceAccount(
            @Context SecurityContext sc, @Valid CreateServiceAccountRequest request) {
        requireSuperAdmin(sc);
        requireCqrs();

        LOG.debug("POST /platform/iam/service-accounts name={}", request.name());

        var command =
                new CreateServiceAccountCommandActuator.CreateServiceAccountCommand(
                        REALM, request.name(), request.roles());
        CreateServiceAccountCommandActuator.CreateServiceAccountResult result =
                cqrs.iamDispatcher().dispatch(command);
        return Response.status(Response.Status.CREATED)
                .entity(ServiceAccountCreatedResponse.from(result))
                .build();
    }

    @GET
    @Path("/service-accounts")
    @Operation(operationId = "listServiceAccounts", summary = "List service accounts")
    public Response listServiceAccounts(@Context SecurityContext sc) {
        requireSuperAdmin(sc);
        requireCqrs();

        LOG.debug("GET /platform/iam/service-accounts");

        var accounts =
                cqrs.iamQueryDispatcher()
                        .<ListServiceAccountsQueryActuator.ListServiceAccountsQuery,
                                List<IamQueryService.ServiceAccountInfo>>
                                dispatch(
                                        new ListServiceAccountsQueryActuator
                                                .ListServiceAccountsQuery(REALM));
        return Response.ok(accounts.stream().map(ServiceAccountResponse::from).toList()).build();
    }

    @GET
    @Path("/service-accounts/{clientId}")
    @Operation(operationId = "getServiceAccount", summary = "Get a service account by client ID")
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
                                                REALM, clientId));
        if (account == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(ErrorResponse.of("not_found", "Service account not found: " + clientId))
                    .build();
        }
        return Response.ok(ServiceAccountResponse.from(account)).build();
    }

    @DELETE
    @Path("/service-accounts/{clientId}")
    @Operation(operationId = "revokeServiceAccount", summary = "Revoke a service account")
    public Response revokeServiceAccount(
            @Context SecurityContext sc, @PathParam("clientId") String clientId) {
        requireSuperAdmin(sc);
        requireCqrs();

        LOG.debug("DELETE /platform/iam/service-accounts/{}", clientId);
        cqrs.iamDispatcher()
                .dispatch(
                        new RevokeServiceAccountCommandActuator.RevokeServiceAccountCommand(
                                REALM, clientId));
        return Response.ok(MessageResponse.of("Service account revoked")).build();
    }

    // -----------------------------------------------------------------------
    // Authentication
    // -----------------------------------------------------------------------

    @POST
    @Path("/login")
    @Operation(
            operationId = "login",
            summary = "Authenticate a user",
            description = "Resource Owner Password Credentials grant against the operio realm.")
    public Response login(@Valid LoginRequest request) {
        requireCqrs();

        LOG.debug("POST /platform/iam/login email={}", request.email());

        try {
            var result =
                    (com.koostack.operio.core.models.AuthResult)
                            cqrs.iamDispatcher()
                                    .dispatch(
                                            new AuthenticateCommandActuator.AuthenticateCommand(
                                                    REALM, request.email(), request.password()));
            return Response.ok(AuthResponse.from(result)).build();
        } catch (com.koostack.operio.core.exception.OperioAuthException e) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(ErrorResponse.of("unauthorized", e.getMessage()))
                    .build();
        }
    }

    @POST
    @Path("/refresh")
    @Operation(operationId = "refresh", summary = "Refresh access token")
    public Response refresh(@Valid RefreshTokenRequest request) {
        requireCqrs();

        LOG.debug("POST /platform/iam/refresh");

        try {
            var result =
                    (com.koostack.operio.core.models.AuthResult)
                            cqrs.iamDispatcher()
                                    .dispatch(
                                            new RefreshTokenCommandActuator.RefreshTokenCommand(
                                                    REALM, request.refreshToken()));
            return Response.ok(AuthResponse.from(result)).build();
        } catch (com.koostack.operio.core.exception.OperioAuthException e) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(ErrorResponse.of("unauthorized", e.getMessage()))
                    .build();
        }
    }

    @POST
    @Path("/logout")
    @Operation(operationId = "logout", summary = "Logout a user")
    public Response logout(@Context SecurityContext sc, @Valid LogoutRequest request) {
        requireCqrs();

        LOG.debug("POST /platform/iam/logout");
        cqrs.iamDispatcher()
                .dispatch(new LogoutCommandActuator.LogoutCommand(REALM, request.refreshToken()));
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
