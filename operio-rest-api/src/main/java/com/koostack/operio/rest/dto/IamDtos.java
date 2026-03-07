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
 * Request and response DTOs for IAM endpoints ({@code /api/v1/platform/iam/*} and {@code
 * /api/v1/iam/*}).
 *
 * <p>Covers user management, service account lifecycle, authentication (login/refresh/logout), and
 * role assignment. All types are Java records — immutable, serializable by Jackson, and cheap to
 * allocate.
 */
public final class IamDtos {

    private IamDtos() {}

    // ===================================================================
    // User Management
    // ===================================================================

    /** Request body for {@code POST /users}. */
    public record RegisterUserRequest(
            @NotBlank(message = "email must not be blank")
                    @Email(message = "email must be a valid email address")
                    @Size(max = 320)
                    String email,
            @Size(max = 255) String fullName,
            Set<String> roles,
            boolean temporaryPassword) {}

    /** Response after user registration. */
    public record UserRegisteredResponse(String userId, String email, String realmId) {
        public static UserRegisteredResponse from(IamCommandService.RegisterUserResult r) {
            return new UserRegisteredResponse(r.userId(), r.email(), r.realmId());
        }

        public static UserRegisteredResponse fromTenant(
                com.koostack.operio.core.tenant.iam.TenantIamCommandService.RegisterUserResult r) {
            return new UserRegisteredResponse(r.userId(), r.email(), r.realmId());
        }
    }

    /** Response for user queries. */
    public record UserResponse(
            String userId, String email, String fullName, String status, Instant createdAt) {
        public static UserResponse from(IdpUser u) {
            return new UserResponse(u.userId(), u.email(), u.fullName(), u.status(), u.createdAt());
        }
    }

    /** Request body for role assignment/removal. */
    public record RoleRequest(@NotBlank(message = "role must not be blank") String role) {}

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

        public static ServiceAccountCreatedResponse fromTenant(
                com.koostack.operio.core.tenant.iam.TenantIamCommandService
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

        public static ServiceAccountResponse fromTenant(
                com.koostack.operio.core.tenant.iam.TenantIamQueryService.ServiceAccountInfo sa) {
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
