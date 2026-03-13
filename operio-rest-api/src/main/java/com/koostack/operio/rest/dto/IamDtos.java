package com.koostack.operio.rest.dto;

import com.koostack.operio.core.models.AuthResult;
import com.koostack.operio.core.models.IdpUser;
import com.koostack.operio.core.platform.iam.IamQueryService;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Set;

/**
 * Request and response DTOs for IAM endpoints ({@code /api/v1/platform/iam/*}).
 *
 * <p>Covers user management, organization lifecycle, service accounts, authentication
 * (login/refresh/logout), and role assignment. All types are Java records — immutable, serializable
 * by Jackson, and cheap to allocate.
 */
public final class IamDtos {

    private IamDtos() {}

    // ===================================================================
    // User Management
    // ===================================================================

    /** Request body for {@code POST /users}. */
    public record RegisterUserRequest(
            @NotBlank(message = "username must not be blank") @Size(max = 255) String username,
            @NotBlank(message = "email must not be blank") @Email(message = "email must be a valid email address") @Size(max = 320) String email,
            @Size(max = 255) String fullName,
            Set<String> roles,
            boolean temporaryPassword,
            @Size(max = 255) String password) {}

    /** Response after user registration. */
    public record UserRegisteredResponse(
            String username,
            String email,
            String realmId,
            String keycloakUserId,
            String tenantId,
            String plan,
            String tenantStatus) {
        public static UserRegisteredResponse from(
                com.koostack.operio.rest.orchestrator.RegisterUserOrchestrator.RegisterUserResult
                        r) {
            return new UserRegisteredResponse(
                    r.username(),
                    r.email(),
                    r.realmId(),
                    r.keycloakUserId(),
                    r.tenantId(),
                    r.plan(),
                    r.tenantStatus());
        }
    }

    /** Response for user queries. */
    public record UserResponse(
            String username, String email, String fullName, String status, Instant createdAt) {
        public static UserResponse from(IdpUser u) {
            return new UserResponse(
                    u.username(), u.email(), u.fullName(), u.status(), u.createdAt());
        }
    }

    /** Request body for role assignment/removal. */
    public record RoleRequest(@NotBlank(message = "role must not be blank") String role) {}

    // ===================================================================
    // Organizations
    // ===================================================================

    /** Request body for {@code POST /organizations}. */
    public record CreateOrganizationRequest(
            @NotBlank(message = "name must not be blank") @Size(max = 255) String name,
            @Size(max = 255) String displayName) {}

    /** Response after organization creation. */
    public record OrganizationCreatedResponse(String orgId, String orgName) {
        public static OrganizationCreatedResponse from(
                com.koostack.operio.platform.iam.jooq.CreateOrganizationCommandActuator
                                .CreateOrganizationResult
                        r) {
            return new OrganizationCreatedResponse(r.orgId(), r.orgName());
        }
    }

    /** Response after organization registration (with full provisioning). */
    public record OrganizationRegisteredResponse(
            String orgId, String orgName, String tenantId, String plan, String tenantStatus) {
        public static OrganizationRegisteredResponse from(
                com.koostack.operio.rest.orchestrator.RegisterOrganizationOrchestrator
                                .RegisterOrgResult
                        r) {
            return new OrganizationRegisteredResponse(
                    r.orgId(), r.orgName(), r.tenantId(), r.plan(), r.tenantStatus());
        }
    }

    /** Response for organization queries. */
    public record OrganizationResponse(
            String orgId, String orgName, String displayName, boolean enabled) {
        public static OrganizationResponse from(IamQueryService.OrganizationInfo o) {
            return new OrganizationResponse(o.orgId(), o.orgName(), o.displayName(), o.enabled());
        }
    }

    /** Request body for {@code POST /organizations/{orgId}/members}. */
    public record AddOrgMemberRequest(
            @NotBlank(message = "userId must not be blank") String userId,
            @Size(max = 255) String username) {}

    /** Request body for {@code POST /organizations/{orgId}/members/invites}. */
    public record InviteOrgMemberRequest(
            @NotBlank(message = "email must not be blank") @Email(message = "email must be a valid email address") String email) {}

    /** Response after inviting a member to an organization. */
    public record InviteOrgMemberResponse(String orgId, String inviteeEmail, String status) {
        public static InviteOrgMemberResponse from(
                com.koostack.operio.rest.orchestrator.InviteOrgMemberOrchestrator
                                .InviteOrgMemberResult
                        r) {
            return new InviteOrgMemberResponse(r.orgId(), r.inviteeEmail(), r.status());
        }
    }

    /** Request body for {@code PUT /organizations/{orgId}}. */
    public record UpdateOrganizationRequest(
            @Size(max = 255) String displayName, @Size(max = 1000) String description) {}

    /** Response after updating an organization. */
    public record OrganizationUpdatedResponse(String orgId, String displayName) {
        public static OrganizationUpdatedResponse from(
                com.koostack.operio.rest.orchestrator.UpdateOrganizationOrchestrator.UpdateOrgResult
                        r) {
            return new OrganizationUpdatedResponse(r.orgId(), r.displayName());
        }
    }

    /** Response for organization member queries. */
    public record OrgMemberResponse(String userId, String username, String email) {
        public static OrgMemberResponse from(IamQueryService.OrgMemberInfo m) {
            return new OrgMemberResponse(m.userId(), m.username(), m.email());
        }
    }

    // ===================================================================
    // Access Tokens
    // ===================================================================

    /** Request body for {@code POST /me/tokens}. */
    public record CreateAccessTokenRequest(
            @NotBlank(message = "name must not be blank") @Size(max = 100) String name,
            Instant expiresAt,
            String tenantId) {
        /** Backward-compatible constructor without explicit tenantId. */
        public CreateAccessTokenRequest(String name, Instant expiresAt) {
            this(name, expiresAt, null);
        }
    }

    /** Response after access token creation — plaintext token is shown exactly once. */
    public record AccessTokenCreatedResponse(
            String token, String tokenPrefix, String name, Instant expiresAt) {}

    /** Response for access token listing (no raw token or hash). */
    public record AccessTokenResponse(
            String tokenPrefix,
            String name,
            String tenantId,
            String roles,
            String status,
            Instant expiresAt,
            String createdBy,
            Instant lastUsedAt,
            Instant createdAt) {
        public static AccessTokenResponse from(
                com.koostack.operio.core.platform.iam.AccessTokenInfo t) {
            return new AccessTokenResponse(
                    t.tokenPrefix(),
                    t.name(),
                    t.tenantId(),
                    t.roles(),
                    t.status(),
                    t.expiresAt(),
                    t.createdBy(),
                    t.lastUsedAt(),
                    t.createdAt());
        }
    }

    // ===================================================================
    // Authentication
    // ===================================================================

    /** Request body for {@code POST /login}. */
    public record LoginRequest(
            @NotBlank(message = "email must not be blank") String email,
            @NotBlank(message = "password must not be blank") String password) {}

    /** Request body for {@code POST /refresh}. */
    public record RefreshTokenRequest(
            @NotBlank(message = "refreshToken must not be blank") String refreshToken) {}

    /** Request body for {@code POST /logout}. */
    public record LogoutRequest(
            @NotBlank(message = "refreshToken must not be blank") String refreshToken) {}

    /** Response for authentication/refresh. */
    public record AuthResponse(
            String accessToken,
            String refreshToken,
            long expiresIn,
            String tokenType,
            String subject,
            Set<String> roles) {
        public static AuthResponse from(AuthResult r) {
            return new AuthResponse(
                    r.accessToken(),
                    r.refreshToken(),
                    r.expiresIn(),
                    r.tokenType(),
                    r.identity() != null ? r.identity().subject() : null,
                    r.identity() != null ? r.identity().roles() : Set.of());
        }
    }

    // ===================================================================
    // Current User (/me)
    // ===================================================================

    /** Response for {@code GET /me}. */
    public record CurrentUserResponse(
            String userId,
            String username,
            String email,
            String fullName,
            Set<String> roles,
            Set<String> permissions,
            java.util.List<OrgMembership> organizations) {}

    /** Lightweight org membership included in /me response. */
    public record OrgMembership(
            String orgId,
            String orgName,
            String displayName,
            boolean isAdmin,
            String tenantId,
            Set<String> roles,
            Set<String> permissions) {
        /** Backward-compatible constructor without roles/permissions/tenantId. */
        public OrgMembership(String orgId, String orgName, String displayName, boolean isAdmin) {
            this(orgId, orgName, displayName, isAdmin, null, Set.of(), Set.of());
        }
    }

    /** Request body for {@code PUT /me}. */
    public record UpdateProfileRequest(
            @Size(max = 255) String fullName,
            @Email(message = "email must be a valid email address") @Size(max = 320) String email) {}

    /** Request body for {@code PUT /me/password}. */
    public record ChangePasswordRequest(
            @NotBlank(message = "newPassword must not be blank") @Size(min = 8, max = 255) String newPassword) {}

    /** Request body for creating a custom organization role. */
    public record CreateCustomRoleRequest(
            @NotBlank(message = "roleId must not be blank") @Size(max = 100) String roleId,
            @NotBlank(message = "displayName must not be blank") @Size(max = 200) String displayName,
            java.util.Set<String> permissions) {}

    /** Request body for assigning a role to a user. */
    public record AssignRoleRequest(
            @NotBlank(message = "roleId must not be blank") String roleId) {}
}
