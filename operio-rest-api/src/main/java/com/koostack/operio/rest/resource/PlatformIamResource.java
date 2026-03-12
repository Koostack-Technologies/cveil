package com.koostack.operio.rest.resource;

import com.koostack.operio.core.cqrs.AbstractOrchestrator.OrchestratorException;
import com.koostack.operio.core.exception.OperioAuthException;
import com.koostack.operio.core.models.IdpUser;
import com.koostack.operio.core.platform.iam.IamQueryService;
import com.koostack.operio.platform.iam.jooq.*;
import com.koostack.operio.rest.auth.Roles;
import com.koostack.operio.rest.config.OperioOpenApiConfig;
import com.koostack.operio.rest.config.PlatformCqrsHolder;
import com.koostack.operio.rest.context.OperioRequestHolder;
import com.koostack.operio.rest.dto.CommonDtos.ErrorResponse;
import com.koostack.operio.rest.dto.CommonDtos.MessageResponse;
import com.koostack.operio.rest.dto.IamDtos.*;
import com.koostack.operio.rest.interceptor.TenantScoped;
import com.koostack.operio.rest.orchestrator.InviteOrgMemberOrchestrator;
import com.koostack.operio.rest.orchestrator.RegisterOrganizationOrchestrator;
import com.koostack.operio.rest.orchestrator.RegisterUserOrchestrator;
import com.koostack.operio.rest.orchestrator.UpdateOrganizationOrchestrator;
import com.koostack.operio.rest.util.TenantIdDeriver;
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
 * Unified IAM endpoints for managing users, organizations, access tokens, and authentication in the
 * single Keycloak realm ({@code operio}).
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

    @Inject OperioRequestHolder requestHolder;

    // -----------------------------------------------------------------------
    // Users
    // -----------------------------------------------------------------------

    @POST
    @Path("/users")
    @Operation(
            operationId = "registerUser",
            summary = "Register a user",
            description =
                    "Self-service user registration. Creates a Keycloak user, personal tenant,"
                            + " billing profile, and subscription on the free plan. Public endpoint"
                            + " — no authentication required.")
    public Response registerUser(@Context SecurityContext sc, @Valid RegisterUserRequest request) {
        requireCqrs();

        LOG.debug(
                "POST /platform/iam/users username={}, email={}",
                request.username(),
                request.email());

        String tenantId = TenantIdDeriver.derive(request.username());

        var orchestrator =
                new RegisterUserOrchestrator(
                        cqrs.iamDispatcher(), cqrs.coreDispatcher(), cqrs.billingDispatcher());

        var command =
                new RegisterUserOrchestrator.RegisterUserCommand(
                        REALM,
                        request.username(),
                        request.email(),
                        request.fullName(),
                        request.roles(),
                        request.temporaryPassword(),
                        request.password(),
                        tenantId);

        try {
            RegisterUserOrchestrator.RegisterUserResult result = orchestrator.execute(command);
            return Response.status(Response.Status.CREATED)
                    .entity(UserRegisteredResponse.from(result))
                    .build();
        } catch (OrchestratorException e) {
            LOG.error(
                    "User registration saga failed at step '{}' ({}/{} completed): {}",
                    e.failedStep(),
                    e.completedSteps(),
                    e.totalSteps(),
                    e.getMessage(),
                    e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(
                            ErrorResponse.of(
                                    "registration_failed",
                                    "Registration failed at step '"
                                            + e.failedStep()
                                            + "'. Contact support."))
                    .build();
        }
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
                    "Creates a Keycloak Organization (tenant) with full provisioning: org, member,"
                            + " org-admin role, Redis, tenant, billing, subscription. Requires JWT"
                            + " authentication.")
    public Response createOrganization(
            @Context SecurityContext sc, @Valid CreateOrganizationRequest request) {
        requireAuthenticated(sc);
        requireJwtAuth();
        requireCqrs();

        LOG.debug("POST /platform/iam/organizations name={}", request.name());

        String kcUserId = requestHolder.identity().subject();
        String username = requestHolder.identity().email();

        var orchestrator =
                new RegisterOrganizationOrchestrator(
                        cqrs.iamDispatcher(), cqrs.coreDispatcher(), cqrs.billingDispatcher());

        var command =
                new RegisterOrganizationOrchestrator.RegisterOrgCommand(
                        REALM, request.name(), request.displayName(), kcUserId, username, "free");

        try {
            RegisterOrganizationOrchestrator.RegisterOrgResult result =
                    orchestrator.execute(command);
            return Response.status(Response.Status.CREATED)
                    .entity(OrganizationRegisteredResponse.from(result))
                    .build();
        } catch (OrchestratorException e) {
            LOG.error(
                    "Organization registration saga failed at step '{}' ({}/{} completed): {}",
                    e.failedStep(),
                    e.completedSteps(),
                    e.totalSteps(),
                    e.getMessage(),
                    e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(
                            ErrorResponse.of(
                                    "org_registration_failed",
                                    "Organization registration failed at step '"
                                            + e.failedStep()
                                            + "'. Contact support."))
                    .build();
        }
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
        requireAuthenticated(sc);
        requireJwtAuth();
        requireCqrs();
        requireOrgMember(sc, orgId);

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
        requireAuthenticated(sc);
        requireJwtAuth();
        requireCqrs();
        requireOrgMember(sc, orgId);

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
        requireAuthenticated(sc);
        requireJwtAuth();
        requireCqrs();
        String orgName = resolveOrgName(orgId);
        requireOrgAdmin(sc, orgName);

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
        requireAuthenticated(sc);
        requireJwtAuth();
        requireCqrs();
        String orgName = resolveOrgName(orgId);
        requireOrgAdmin(sc, orgName);

        LOG.debug("DELETE /platform/iam/organizations/{}/members/{}", orgId, userId);

        cqrs.iamDispatcher()
                .dispatch(
                        new RemoveOrgMemberCommandActuator.RemoveOrgMemberCommand(
                                orgId, null, userId));
        return Response.ok(MessageResponse.of("Member removed from organization")).build();
    }

    @POST
    @Path("/organizations/{orgId}/members/invites")
    @Operation(
            operationId = "inviteOrgMember",
            summary = "Invite a member to an organization",
            description =
                    "Sends an invite to the specified email address via Keycloak Organizations"
                            + " invite API. Requires org-admin or super-admin role.")
    public Response inviteOrgMember(
            @Context SecurityContext sc,
            @PathParam("orgId") String orgId,
            @Valid InviteOrgMemberRequest request) {
        requireAuthenticated(sc);
        requireJwtAuth();
        requireCqrs();

        String orgName = resolveOrgName(orgId);
        requireOrgAdmin(sc, orgName);

        LOG.debug(
                "POST /platform/iam/organizations/{}/members/invites email={}",
                orgId,
                request.email());

        String inviterUserId = requestHolder.identity().subject();

        var orchestrator = new InviteOrgMemberOrchestrator(cqrs.iamDispatcher());
        var command =
                new InviteOrgMemberOrchestrator.InviteOrgMemberCommand(
                        orgId, orgName, request.email(), inviterUserId);

        try {
            InviteOrgMemberOrchestrator.InviteOrgMemberResult result =
                    orchestrator.execute(command);
            return Response.status(Response.Status.CREATED)
                    .entity(InviteOrgMemberResponse.from(result))
                    .build();
        } catch (OrchestratorException e) {
            LOG.error("Member invite failed at step '{}': {}", e.failedStep(), e.getMessage(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ErrorResponse.of("invite_failed", "Invite failed: " + e.getMessage()))
                    .build();
        }
    }

    @PUT
    @Path("/organizations/{orgId}")
    @Operation(
            operationId = "updateOrganization",
            summary = "Update an organization",
            description =
                    "Updates organization metadata in Keycloak. Requires org-admin or super-admin"
                            + " role.")
    public Response updateOrganization(
            @Context SecurityContext sc,
            @PathParam("orgId") String orgId,
            @Valid UpdateOrganizationRequest request) {
        requireAuthenticated(sc);
        requireJwtAuth();
        requireCqrs();

        String orgName = resolveOrgName(orgId);
        requireOrgAdmin(sc, orgName);

        LOG.debug("PUT /platform/iam/organizations/{}", orgId);

        var orchestrator = new UpdateOrganizationOrchestrator(cqrs.iamDispatcher());
        var command =
                new UpdateOrganizationOrchestrator.UpdateOrgCommand(
                        orgId, orgName, request.displayName(), request.description());

        try {
            UpdateOrganizationOrchestrator.UpdateOrgResult result = orchestrator.execute(command);
            return Response.ok(OrganizationUpdatedResponse.from(result)).build();
        } catch (OrchestratorException e) {
            LOG.error(
                    "Organization update failed at step '{}': {}",
                    e.failedStep(),
                    e.getMessage(),
                    e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(
                            ErrorResponse.of(
                                    "update_failed",
                                    "Organization update failed: " + e.getMessage()))
                    .build();
        }
    }

    // -----------------------------------------------------------------------
    // Access Tokens (/me/tokens)
    // -----------------------------------------------------------------------

    @POST
    @Path("/me/tokens")
    @Operation(
            operationId = "createAccessToken",
            summary = "Create a personal access token",
            description =
                    "Creates a personal access token with the caller's current Keycloak roles"
                        + " snapshotted at creation time. The plaintext token is returned once —"
                        + " store it securely. Maximum expiration: 90 days.")
    public Response createAccessToken(
            @Context SecurityContext sc, @Valid CreateAccessTokenRequest request) {
        requireCqrs();
        requireAuthenticated(sc);
        requireJwtAuth();

        String tenantId = requestHolder.identity().tenantId();
        String createdBy = requestHolder.identity().subject();
        Set<String> callerRoles = requestHolder.identity().roles();
        String roles = callerRoles != null ? String.join(",", callerRoles) : "";

        LOG.debug(
                "POST /platform/iam/me/tokens name={} tenant={} createdBy={}",
                request.name(),
                tenantId,
                createdBy);

        // Validate expiration: required, max 90 days
        if (request.expiresAt() == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ErrorResponse.of("validation_error", "expiresAt is required"))
                    .build();
        }
        java.time.Instant now = java.time.Instant.now();
        java.time.Instant maxExpiry = now.plus(java.time.Duration.ofDays(90));
        if (request.expiresAt().isBefore(now)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ErrorResponse.of("validation_error", "expiresAt must be in the future"))
                    .build();
        }
        if (request.expiresAt().isAfter(maxExpiry)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(
                            ErrorResponse.of(
                                    "validation_error", "expiresAt must be within 90 days"))
                    .build();
        }

        try {
            var command =
                    new CreateAccessTokenCommandActuator.CreateAccessTokenCommand(
                            tenantId, request.name(), roles, createdBy, request.expiresAt());
            CreateAccessTokenCommandActuator.CreateAccessTokenResult result =
                    cqrs.iamDispatcher().dispatch(command);

            return Response.status(Response.Status.CREATED)
                    .entity(
                            new AccessTokenCreatedResponse(
                                    result.plaintextToken(),
                                    result.tokenPrefix(),
                                    request.name(),
                                    request.expiresAt()))
                    .build();
        } catch (Exception e) {
            LOG.error("Failed to create access token: {}", e.getMessage(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(
                            ErrorResponse.of(
                                    "token_creation_failed", "Failed to create access token"))
                    .build();
        }
    }

    @GET
    @Path("/me/tokens")
    @Operation(
            operationId = "listAccessTokens",
            summary = "List the caller's access tokens",
            description = "Returns metadata for all access tokens created by the caller.")
    public Response listAccessTokens(@Context SecurityContext sc) {
        requireCqrs();
        requireAuthenticated(sc);
        requireJwtAuth();

        String tenantId = requestHolder.identity().tenantId();
        String createdBy = requestHolder.identity().subject();
        LOG.debug("GET /platform/iam/me/tokens tenant={} createdBy={}", tenantId, createdBy);

        try {
            var tokens = cqrs.iamQueryService().listAccessTokensByCreator(tenantId, createdBy);
            return Response.ok(tokens.stream().map(AccessTokenResponse::from).toList()).build();
        } catch (Exception e) {
            LOG.error("Failed to list tokens: {}", e.getMessage(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ErrorResponse.of("list_failed", "Failed to list access tokens"))
                    .build();
        }
    }

    @DELETE
    @Path("/me/tokens/{tokenPrefix}")
    @Operation(
            operationId = "revokeAccessToken",
            summary = "Revoke an access token",
            description = "Revokes a personal access token by its prefix identifier.")
    public Response revokeAccessToken(
            @Context SecurityContext sc,
            @Parameter(description = "Token prefix (e.g. cveil_a8Kx3mNp)") @PathParam("tokenPrefix")
                    String tokenPrefix) {
        requireCqrs();
        requireAuthenticated(sc);
        requireJwtAuth();

        String tenantId = requestHolder.identity().tenantId();
        LOG.debug("DELETE /platform/iam/me/tokens/{} tenant={}", tokenPrefix, tenantId);

        try {
            RevokeAccessTokenCommandActuator.RevokeAccessTokenResult result =
                    cqrs.iamDispatcher()
                            .dispatch(
                                    new RevokeAccessTokenCommandActuator.RevokeAccessTokenCommand(
                                            tenantId, tokenPrefix));

            if (!result.success()) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ErrorResponse.of("not_found", "Token not found or already revoked"))
                        .build();
            }
            return Response.ok(MessageResponse.of("Access token revoked")).build();
        } catch (Exception e) {
            LOG.error("Failed to revoke token: {}", e.getMessage(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ErrorResponse.of("revoke_failed", "Failed to revoke token"))
                    .build();
        }
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
    // Current User (/me)
    // -----------------------------------------------------------------------

    @GET
    @Path("/me")
    @Operation(operationId = "getCurrentUser", summary = "Get the authenticated user's profile")
    public Response getCurrentUser(@Context SecurityContext sc) {
        requireCqrs();
        requireAuthenticated(sc);
        requireJwtAuth();

        String userId = requestHolder.identity().subject();
        LOG.debug("GET /platform/iam/me userId={}", userId);

        try {
            GetCurrentUserQueryActuator.CurrentUserResult result =
                    cqrs.iamQueryDispatcher()
                            .dispatch(new GetCurrentUserQueryActuator.GetCurrentUserQuery(userId));

            var orgs =
                    result.organizations().stream()
                            .map(
                                    o -> {
                                        boolean isAdmin =
                                                result.roles().contains("org-admin:" + o.orgName())
                                                        || result.roles()
                                                                .contains(Roles.SUPER_ADMIN);
                                        return new OrgMembership(
                                                o.orgId(), o.orgName(), o.displayName(), isAdmin);
                                    })
                            .toList();

            return Response.ok(
                            new CurrentUserResponse(
                                    result.userId(),
                                    result.username(),
                                    result.email(),
                                    result.fullName(),
                                    result.roles(),
                                    orgs))
                    .build();
        } catch (Exception e) {
            LOG.error("Failed to fetch current user: {}", e.getMessage(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ErrorResponse.of("fetch_failed", "Failed to fetch user profile"))
                    .build();
        }
    }

    @PUT
    @Path("/me")
    @Operation(operationId = "updateProfile", summary = "Update the authenticated user's profile")
    public Response updateProfile(
            @Context SecurityContext sc, @Valid UpdateProfileRequest request) {
        requireCqrs();
        requireAuthenticated(sc);
        requireJwtAuth();

        String userId = requestHolder.identity().subject();
        LOG.debug("PUT /platform/iam/me userId={}", userId);

        try {
            UpdateUserProfileCommandActuator.UpdateUserProfileResult result =
                    cqrs.iamDispatcher()
                            .dispatch(
                                    new UpdateUserProfileCommandActuator.UpdateUserProfileCommand(
                                            userId, request.fullName(), request.email()));

            return Response.ok(MessageResponse.of("Profile updated for user " + result.userId()))
                    .build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ErrorResponse.of("invalid_request", e.getMessage()))
                    .build();
        } catch (Exception e) {
            LOG.error("Failed to update profile: {}", e.getMessage(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ErrorResponse.of("update_failed", "Failed to update profile"))
                    .build();
        }
    }

    @PUT
    @Path("/me/password")
    @Operation(operationId = "changePassword", summary = "Change the authenticated user's password")
    public Response changePassword(
            @Context SecurityContext sc, @Valid ChangePasswordRequest request) {
        requireCqrs();
        requireAuthenticated(sc);
        requireJwtAuth();

        String userId = requestHolder.identity().subject();
        LOG.debug("PUT /platform/iam/me/password userId={}", userId);

        try {
            cqrs.iamDispatcher()
                    .dispatch(
                            new ChangePasswordCommandActuator.ChangePasswordCommand(
                                    userId, request.newPassword()));
            return Response.ok(MessageResponse.of("Password changed")).build();
        } catch (OperioAuthException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ErrorResponse.of("invalid_password", e.getMessage()))
                    .build();
        } catch (Exception e) {
            LOG.error("Failed to change password: {}", e.getMessage(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ErrorResponse.of("password_change_failed", "Failed to change password"))
                    .build();
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void requireSuperAdmin(SecurityContext sc) {
        Roles.requireRole(sc, Roles.SUPER_ADMIN);
    }

    private void requireAuthenticated(SecurityContext sc) {
        if (sc == null || sc.getUserPrincipal() == null) {
            throw new OperioAuthException("Authentication required");
        }
    }

    private void requireJwtAuth() {
        if (requestHolder == null || !requestHolder.isAuthenticated()) {
            throw new OperioAuthException("This endpoint requires JWT authentication");
        }
        String provider = requestHolder.identity().provider();
        if (!"keycloak".equals(provider) && !"oidc".equals(provider)) {
            throw new OperioAuthException("This endpoint requires JWT authentication");
        }
    }

    private void requireOrgAdmin(SecurityContext sc, String orgName) {
        if (sc != null && sc.isUserInRole(Roles.SUPER_ADMIN)) return;
        String requiredRole = "org-admin:" + orgName;
        if (sc == null || !sc.isUserInRole(requiredRole)) {
            throw new OperioAuthException(
                    "Access denied: requires role '" + requiredRole + "' or super-admin");
        }
    }

    private void requireOrgMember(SecurityContext sc, String orgId) {
        if (sc != null && sc.isUserInRole(Roles.SUPER_ADMIN)) return;
        // Verify the authenticated user is a member of this organization
        String userId = requestHolder.identity().subject();
        try {
            GetCurrentUserQueryActuator.CurrentUserResult user =
                    cqrs.iamQueryDispatcher()
                            .dispatch(new GetCurrentUserQueryActuator.GetCurrentUserQuery(userId));
            boolean isMember = user.organizations().stream().anyMatch(o -> o.orgId().equals(orgId));
            if (!isMember) {
                throw new OperioAuthException(
                        "Access denied: you are not a member of organization " + orgId);
            }
        } catch (OperioAuthException e) {
            throw e;
        } catch (Exception e) {
            throw new OperioAuthException("Access denied: unable to verify org membership");
        }
    }

    private String resolveOrgName(String orgId) {
        IamQueryService.OrganizationInfo org =
                cqrs.iamQueryDispatcher()
                        .dispatch(new GetOrganizationQueryActuator.GetOrganizationQuery(orgId));
        if (org == null) {
            throw new com.koostack.operio.core.exception.OperioServiceException(
                    "Organization not found: " + orgId);
        }
        return org.orgName();
    }

    private void requireCqrs() {
        if (!cqrs.isCqrsAvailable()) {
            throw new IllegalStateException(
                    "Platform CQRS services not available — is operio.mode=production?");
        }
    }
}
