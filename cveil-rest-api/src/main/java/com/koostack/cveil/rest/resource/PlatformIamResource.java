package com.koostack.cveil.rest.resource;

import com.koostack.cveil.core.cqrs.AbstractOrchestrator.OrchestratorException;
import com.koostack.cveil.core.cqrs.CommandDispatcher;
import com.koostack.cveil.core.exception.CveilAuthException;
import com.koostack.cveil.core.models.IdpUser;
import com.koostack.cveil.core.platform.iam.IamQueryService;
import com.koostack.cveil.platform.iam.jooq.*;
import com.koostack.cveil.rest.auth.Permissions;
import com.koostack.cveil.rest.config.CveilOpenApiConfig;
import com.koostack.cveil.rest.config.PlatformCqrsHolder;
import com.koostack.cveil.rest.context.CveilRequestHolder;
import com.koostack.cveil.rest.dto.CommonDtos.ErrorResponse;
import com.koostack.cveil.rest.dto.CommonDtos.MessageResponse;
import com.koostack.cveil.rest.dto.IamDtos.*;
import com.koostack.cveil.rest.interceptor.TenantScoped;
import com.koostack.cveil.rest.orchestrator.InviteOrgMemberOrchestrator;
import com.koostack.cveil.rest.orchestrator.RegisterOrganizationOrchestrator;
import com.koostack.cveil.rest.orchestrator.RegisterUserOrchestrator;
import com.koostack.cveil.rest.orchestrator.UpdateOrganizationOrchestrator;
import com.koostack.cveil.rest.util.TenantIdDeriver;
import jakarta.inject.Inject;
import jakarta.inject.Named;
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
 * single Keycloak realm ({@code cveil}).
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
@Tag(ref = CveilOpenApiConfig.TAG_IAM)
@SecurityRequirement(name = CveilOpenApiConfig.SECURITY_SCHEME)
public class PlatformIamResource {

    private static final Logger LOG = LoggerFactory.getLogger(PlatformIamResource.class);
    private static final String REALM = "cveil";

    @Inject PlatformCqrsHolder cqrs;

    @Inject
    @Named("consentDispatcher")
    CommandDispatcher consentDispatcher;

    @Inject CveilRequestHolder requestHolder;

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
                        cqrs.iamDispatcher(),
                        cqrs.coreDispatcher(),
                        cqrs.billingDispatcher(),
                        cqrs.isPermissionServiceAvailable() ? cqrs.permissionService() : null,
                        consentDispatcher);

        var command =
                new RegisterUserOrchestrator.RegisterUserCommand(
                        REALM,
                        request.username(),
                        request.email(),
                        request.fullName(),
                        request.displayName(),
                        Set.of(), // KC roles empty — DB-backed roles are authoritative
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
                        cqrs.iamDispatcher(),
                        cqrs.coreDispatcher(),
                        cqrs.billingDispatcher(),
                        cqrs.isPermissionServiceAvailable() ? cqrs.permissionService() : null,
                        consentDispatcher);

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
    // Organization Roles
    // -----------------------------------------------------------------------

    @GET
    @Path("/organizations/{orgId}/roles")
    @Operation(
            operationId = "listOrgRoles",
            summary = "List available roles for an organization",
            description =
                    "Returns system roles and any custom roles defined for this organization.")
    public Response listOrgRoles(@Context SecurityContext sc, @PathParam("orgId") String orgId) {
        requireAuthenticated(sc);
        requireJwtAuth();
        requireCqrs();
        requireOrgMember(sc, orgId);

        if (!cqrs.isPermissionServiceAvailable()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(ErrorResponse.of("unavailable", "Permission service not available"))
                    .build();
        }

        String tenantId = TenantIdDeriver.derive(resolveOrgName(orgId));
        var definitions = cqrs.permissionService().listRoleDefinitions(tenantId);
        return Response.ok(definitions).build();
    }

    @POST
    @Path("/organizations/{orgId}/roles")
    @Operation(
            operationId = "createCustomOrgRole",
            summary = "Create a custom role for an organization",
            description =
                    "Creates a new custom role with specified permissions, scoped to this"
                            + " organization.")
    public Response createCustomOrgRole(
            @Context SecurityContext sc,
            @PathParam("orgId") String orgId,
            @Valid CreateCustomRoleRequest request) {
        requireAuthenticated(sc);
        requireJwtAuth();
        requireCqrs();
        String orgName = resolveOrgName(orgId);
        requirePermissionInOrg(orgName, Permissions.ORG_ROLE_MANAGE);

        if (!cqrs.isPermissionServiceAvailable()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(ErrorResponse.of("unavailable", "Permission service not available"))
                    .build();
        }

        String tenantId = TenantIdDeriver.derive(orgName);
        String createdBy = requestHolder.identity().subject();
        List<String> requestedPermissions =
                request.permissions() != null ? List.copyOf(request.permissions()) : List.of();
        if (requestedPermissions.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(
                            ErrorResponse.of(
                                    "validation_error",
                                    "At least one permission is required for a custom role"))
                    .build();
        }
        // Prevent org admins from granting platform-scope permissions to custom roles
        for (String perm : requestedPermissions) {
            if (perm.startsWith("platform:") || perm.startsWith("iam:user:")) {
                return Response.status(Response.Status.FORBIDDEN)
                        .entity(
                                ErrorResponse.of(
                                        "forbidden",
                                        "Cannot grant platform-scope permission '"
                                                + perm
                                                + "' to a custom org role"))
                        .build();
            }
        }
        cqrs.permissionService()
                .createCustomRole(
                        tenantId,
                        request.roleId(),
                        request.displayName(),
                        null,
                        requestedPermissions);
        return Response.status(Response.Status.CREATED)
                .entity(MessageResponse.of("Custom role '" + request.roleId() + "' created"))
                .build();
    }

    @DELETE
    @Path("/organizations/{orgId}/roles/{roleId}")
    @Operation(operationId = "deleteCustomOrgRole", summary = "Delete a custom organization role")
    public Response deleteCustomOrgRole(
            @Context SecurityContext sc,
            @PathParam("orgId") String orgId,
            @PathParam("roleId") String roleId) {
        requireAuthenticated(sc);
        requireJwtAuth();
        requireCqrs();
        String orgName = resolveOrgName(orgId);
        requirePermissionInOrg(orgName, Permissions.ORG_ROLE_MANAGE);

        if (!cqrs.isPermissionServiceAvailable()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(ErrorResponse.of("unavailable", "Permission service not available"))
                    .build();
        }

        cqrs.permissionService().deleteCustomRole(TenantIdDeriver.derive(orgName), roleId);
        return Response.ok(MessageResponse.of("Custom role '" + roleId + "' deleted")).build();
    }

    @PUT
    @Path("/organizations/{orgId}/members/{userId}/roles")
    @Operation(
            operationId = "assignOrgMemberRole",
            summary = "Assign a role to an organization member",
            description = "Assigns a tenant-scoped role to a member of this organization.")
    public Response assignOrgMemberRole(
            @Context SecurityContext sc,
            @PathParam("orgId") String orgId,
            @PathParam("userId") String userId,
            @Valid AssignRoleRequest request) {
        requireAuthenticated(sc);
        requireJwtAuth();
        requireCqrs();
        String orgName = resolveOrgName(orgId);
        requirePermissionInOrg(orgName, Permissions.ORG_ROLE_MANAGE);

        if (!cqrs.isPermissionServiceAvailable()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(ErrorResponse.of("unavailable", "Permission service not available"))
                    .build();
        }

        String tenantId = TenantIdDeriver.derive(orgName);
        String grantedBy = requestHolder.identity().subject();
        cqrs.permissionService().assignRole(userId, tenantId, request.roleId(), grantedBy);
        return Response.ok(
                        MessageResponse.of(
                                "Role '" + request.roleId() + "' assigned to user " + userId))
                .build();
    }

    @DELETE
    @Path("/organizations/{orgId}/members/{userId}/roles/{roleId}")
    @Operation(
            operationId = "removeOrgMemberRole",
            summary = "Remove a role from an organization member")
    public Response removeOrgMemberRole(
            @Context SecurityContext sc,
            @PathParam("orgId") String orgId,
            @PathParam("userId") String userId,
            @PathParam("roleId") String roleId) {
        requireAuthenticated(sc);
        requireJwtAuth();
        requireCqrs();
        String orgName = resolveOrgName(orgId);
        requirePermissionInOrg(orgName, Permissions.ORG_ROLE_MANAGE);

        if (!cqrs.isPermissionServiceAvailable()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(ErrorResponse.of("unavailable", "Permission service not available"))
                    .build();
        }

        String tenantId = TenantIdDeriver.derive(orgName);
        cqrs.permissionService().removeRole(userId, tenantId, roleId);
        return Response.ok(MessageResponse.of("Role '" + roleId + "' removed from user " + userId))
                .build();
    }

    @GET
    @Path("/organizations/{orgId}/members/{userId}/roles")
    @Operation(
            operationId = "listOrgMemberRoles",
            summary = "List roles assigned to an organization member")
    public Response listOrgMemberRoles(
            @Context SecurityContext sc,
            @PathParam("orgId") String orgId,
            @PathParam("userId") String userId) {
        requireAuthenticated(sc);
        requireJwtAuth();
        requireCqrs();
        requireOrgMember(sc, orgId);

        if (!cqrs.isPermissionServiceAvailable()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(ErrorResponse.of("unavailable", "Permission service not available"))
                    .build();
        }

        String tenantId = TenantIdDeriver.derive(resolveOrgName(orgId));
        var assignments = cqrs.permissionService().listUserRolesInTenant(userId, tenantId);
        return Response.ok(assignments).build();
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

        String userId = requestHolder.identity().subject();
        String personalTenantId = requestHolder.identity().tenantId();

        // Determine target tenant: explicit request > personal tenant
        String targetTenantId = request.tenantId() != null ? request.tenantId() : personalTenantId;

        // If scoping to an org tenant, validate user is a member
        if (request.tenantId() != null && !request.tenantId().equals(personalTenantId)) {
            try {
                GetCurrentUserQueryActuator.CurrentUserResult user =
                        cqrs.iamQueryDispatcher()
                                .dispatch(
                                        new GetCurrentUserQueryActuator.GetCurrentUserQuery(
                                                userId));
                boolean isMember =
                        user != null
                                && user.organizations().stream()
                                        .anyMatch(
                                                o ->
                                                        TenantIdDeriver.derive(o.orgName())
                                                                .equals(request.tenantId()));
                if (!isMember) {
                    return Response.status(Response.Status.FORBIDDEN)
                            .entity(
                                    ErrorResponse.of(
                                            "not_member",
                                            "You are not a member of tenant: "
                                                    + request.tenantId()))
                            .build();
                }
            } catch (Exception e) {
                LOG.error(
                        "Failed to validate org membership for PAT creation: {}",
                        e.getMessage(),
                        e);
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity(
                                ErrorResponse.of(
                                        "membership_check_failed",
                                        "Failed to validate organization membership"))
                        .build();
            }
        }

        // Roles column is now informational — real authz resolved live from role_assignment
        String roles = "";
        if (cqrs.isPermissionServiceAvailable()) {
            var resolvedRoles = cqrs.permissionService().resolveRoles(userId, targetTenantId);
            roles = String.join(",", resolvedRoles);
        } else {
            Set<String> callerRoles = requestHolder.identity().roles();
            roles = callerRoles != null ? String.join(",", callerRoles) : "";
        }

        LOG.debug(
                "POST /platform/iam/me/tokens name={} tenant={} createdBy={}",
                request.name(),
                targetTenantId,
                userId);

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
                            targetTenantId, request.name(), roles, userId, request.expiresAt());
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
            description = "Resource Owner Password Credentials grant against the cveil realm.")
    public Response login(@Valid LoginRequest request) {
        requireCqrs();

        LOG.debug("POST /platform/iam/login email={}", request.email());

        try {
            var result =
                    (com.koostack.cveil.core.models.AuthResult)
                            cqrs.iamDispatcher()
                                    .dispatch(
                                            new AuthenticateCommandActuator.AuthenticateCommand(
                                                    REALM, request.email(), request.password()));
            return Response.ok(AuthResponse.from(result)).build();
        } catch (com.koostack.cveil.core.exception.CveilAuthException e) {
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
                    (com.koostack.cveil.core.models.AuthResult)
                            cqrs.iamDispatcher()
                                    .dispatch(
                                            new RefreshTokenCommandActuator.RefreshTokenCommand(
                                                    REALM, request.refreshToken()));
            return Response.ok(AuthResponse.from(result)).build();
        } catch (com.koostack.cveil.core.exception.CveilAuthException e) {
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

            // If the iam_user projection hasn't materialized yet (e.g. right after signup),
            // return a degraded response built from JWT claims so the frontend doesn't break.
            if (result == null) {
                LOG.info(
                        "iam_user not yet projected for userId={}; returning JWT-based response",
                        userId);
                var identity = requestHolder.identity();
                var claims = identity.claims();
                String fallbackUsername =
                        claims.containsKey("preferred_username")
                                ? String.valueOf(claims.get("preferred_username"))
                                : identity.email();
                String fallbackName =
                        claims.containsKey("name") ? String.valueOf(claims.get("name")) : null;
                var authzFallback = requestHolder.authorization();
                Set<String> fallbackRoles =
                        authzFallback != null ? authzFallback.roles() : Set.of();
                Set<String> fallbackPerms =
                        authzFallback != null ? authzFallback.permissions() : Set.of();
                return Response.ok(
                                new CurrentUserResponse(
                                        userId,
                                        fallbackUsername,
                                        identity.email(),
                                        fallbackName,
                                        fallbackRoles,
                                        fallbackPerms,
                                        List.of()))
                        .build();
            }

            var authz = requestHolder.authorization();
            boolean hasPermSvc = cqrs.isPermissionServiceAvailable();
            var orgs =
                    result.organizations().stream()
                            .map(
                                    o -> {
                                        String orgTenantId = TenantIdDeriver.derive(o.orgName());
                                        Set<String> orgRoles = Set.of();
                                        Set<String> orgPerms = Set.of();
                                        if (hasPermSvc) {
                                            orgRoles =
                                                    cqrs.permissionService()
                                                            .resolveRoles(userId, orgTenantId);
                                            orgPerms =
                                                    cqrs.permissionService()
                                                            .resolvePermissions(
                                                                    userId, orgTenantId);
                                        }
                                        boolean isAdmin =
                                                (authz != null && authz.isSuperAdmin())
                                                        || orgRoles.contains("org-admin");
                                        return new OrgMembership(
                                                o.orgId(),
                                                o.orgName(),
                                                o.displayName(),
                                                isAdmin,
                                                orgTenantId,
                                                orgRoles,
                                                orgPerms);
                                    })
                            .toList();

            // Active tenant roles and permissions from DB (not Keycloak)
            Set<String> activeRoles = authz != null ? authz.roles() : Set.of();
            Set<String> activePermissions = authz != null ? authz.permissions() : Set.of();

            return Response.ok(
                            new CurrentUserResponse(
                                    result.userId(),
                                    result.username(),
                                    result.email(),
                                    result.fullName(),
                                    activeRoles,
                                    activePermissions,
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
        } catch (CveilAuthException e) {
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
        Permissions.require(requestHolder.authorization(), Permissions.PLATFORM_TENANT_MANAGE);
    }

    private void requireAuthenticated(SecurityContext sc) {
        if (sc == null || sc.getUserPrincipal() == null) {
            throw new CveilAuthException("Authentication required");
        }
    }

    private void requireJwtAuth() {
        if (requestHolder == null || !requestHolder.isAuthenticated()) {
            throw new CveilAuthException("This endpoint requires JWT authentication");
        }
        String provider = requestHolder.identity().provider();
        if (!"keycloak".equals(provider) && !"oidc".equals(provider)) {
            throw new CveilAuthException("This endpoint requires JWT authentication");
        }
    }

    /**
     * Require that the caller has org:member:manage in the specified org's tenant, not just in
     * their active tenant. Prevents cross-org privilege escalation.
     */
    private void requireOrgAdmin(SecurityContext sc, String orgName) {
        requirePermissionInOrg(orgName, Permissions.ORG_MEMBER_MANAGE);
    }

    /**
     * Require that the caller has org:read in the specified org's tenant, not just in their active
     * tenant. Prevents cross-org privilege escalation.
     */
    private void requireOrgMember(SecurityContext sc, String orgId) {
        String orgName = resolveOrgName(orgId);
        requirePermissionInOrg(orgName, Permissions.ORG_READ);
    }

    /**
     * Resolve permissions against a specific target org's tenant (not the caller's active tenant).
     * This prevents cross-org privilege escalation where a user is admin in OrgA but tries to
     * manage OrgB by setting X-Org-Id to OrgA's tenant.
     */
    private void requirePermissionInOrg(String orgName, String permission) {
        var authz = requestHolder.authorization();
        if (authz != null && authz.isSuperAdmin()) {
            return;
        }
        String targetTenantId = TenantIdDeriver.derive(orgName);
        // If the caller's active tenant matches the target, the existing authz context is valid
        if (authz != null && targetTenantId.equals(authz.tenantId())) {
            Permissions.require(authz, permission);
            return;
        }
        // Otherwise, resolve permissions live against the target org's tenant
        if (!cqrs.isPermissionServiceAvailable()) {
            throw new CveilAuthException(
                    "Permission service not available for cross-org authorization check");
        }
        String userId = requestHolder.identity().subject();
        Set<String> targetPermissions =
                cqrs.permissionService().resolvePermissions(userId, targetTenantId);
        if (!targetPermissions.contains(permission)) {
            throw new CveilAuthException(
                    "Access denied: you do not have the '"
                            + permission
                            + "' permission in this organization");
        }
    }

    private String resolveOrgName(String orgId) {
        IamQueryService.OrganizationInfo org =
                cqrs.iamQueryDispatcher()
                        .dispatch(new GetOrganizationQueryActuator.GetOrganizationQuery(orgId));
        if (org == null) {
            throw new com.koostack.cveil.core.exception.CveilServiceException(
                    "Organization not found: " + orgId);
        }
        return org.orgName();
    }

    private void requireCqrs() {
        if (!cqrs.isCqrsAvailable()) {
            throw new IllegalStateException(
                    "Platform CQRS services not available — is cveil.mode=production?");
        }
    }
}
