package com.koostack.operio.rest.dto;

import com.koostack.operio.core.models.AuthResult;
import com.koostack.operio.core.models.IdpUser;
import com.koostack.operio.core.platform.iam.IamCommandService;
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
            @Size(max = 255) String password,
            @Size(max = 255) String organizationName,
            @Size(max = 255) String organizationDisplayName) {}

    /** Response after user registration (with optional org + tenant). */
    public record UserRegisteredResponse(
            String username,
            String email,
            String realmId,
            String keycloakUserId,
            String orgId,
            String orgName,
            String tenantId,
            String plan,
            String tenantStatus) {
        public static UserRegisteredResponse from(IamCommandService.RegisterUserResult r) {
            return new UserRegisteredResponse(
                    r.username(), r.email(), r.realmId(), null, null, null, null, null, null);
        }

        public static UserRegisteredResponse from(
                com.koostack.operio.platform.iam.jooq.RegisterUserCommandActuator.RegisterUserResult
                        r) {
            return new UserRegisteredResponse(
                    r.username(), r.email(), r.realmId(), null, null, null, null, null, null);
        }

        public static UserRegisteredResponse from(
                com.koostack.operio.rest.orchestrator.RegisterUserOrchestrator
                                .RegisterUserWithOrgResult
                        r) {
            return new UserRegisteredResponse(
                    r.username(),
                    r.email(),
                    r.realmId(),
                    r.keycloakUserId(),
                    r.orgId(),
                    r.orgName(),
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

    /** Response for organization member queries. */
    public record OrgMemberResponse(String userId, String username, String email) {
        public static OrgMemberResponse from(IamQueryService.OrgMemberInfo m) {
            return new OrgMemberResponse(m.userId(), m.username(), m.email());
        }
    }

    // ===================================================================
    // Service Accounts
    // ===================================================================

    /** Request body for {@code POST /service-accounts}. */
    public record CreateServiceAccountRequest(
            @NotBlank(message = "name must not be blank") @Size(max = 255) String name,
            Set<String> roles) {}

    /** Response after service account creation. */
    public record ServiceAccountCreatedResponse(
            String clientId, String clientSecret, String realmId) {
        public static ServiceAccountCreatedResponse from(
                IamCommandService.CreateServiceAccountResult r) {
            return new ServiceAccountCreatedResponse(r.clientId(), r.clientSecret(), r.realmId());
        }

        public static ServiceAccountCreatedResponse from(
                com.koostack.operio.platform.iam.jooq.CreateServiceAccountCommandActuator
                                .CreateServiceAccountResult
                        r) {
            return new ServiceAccountCreatedResponse(r.clientId(), r.clientSecret(), r.realmId());
        }
    }

    /** Response for service account queries. */
    public record ServiceAccountResponse(
            String clientId,
            String name,
            String realmId,
            Set<String> roles,
            String status,
            Instant createdAt) {
        public static ServiceAccountResponse from(IamQueryService.ServiceAccountInfo sa) {
            return new ServiceAccountResponse(
                    sa.clientId(),
                    sa.name(),
                    sa.realmId(),
                    sa.roles(),
                    sa.status(),
                    sa.createdAt());
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
}
