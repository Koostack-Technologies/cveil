package com.koostack.operio.rest.dto;

import com.koostack.operio.core.models.AuthResult;
import com.koostack.operio.core.models.IdpUser;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.Set;

/**
 * Request and response DTOs for the authentication endpoints
 * ({@code /api/v1/auth}).
 */
public final class AuthDtos {

    private AuthDtos() {}

    // -------------------------------------------------------------------
    // Register
    // -------------------------------------------------------------------

    @Schema(description = "User registration request")
    public record RegisterRequest(
        @NotBlank @Schema(description = "Tenant to register in")
        String tenantId,

        @NotBlank @Email @Schema(description = "User email address")
        String email,

        @NotBlank @Size(min = 8, max = 128) @Schema(description = "Password (min 8 chars)")
        String password,

        @Schema(description = "User's full name")
        String fullName
    ) {}

    @Schema(description = "User registration response")
    public record RegisterResponse(
        @Schema(description = "IdP user identifier")
        String userId,

        @Schema(description = "User email address")
        String email,

        @Schema(description = "User's full name")
        String fullName,

        @Schema(description = "Account status (unverified until email is confirmed)")
        String status,

        @Schema(description = "When the account was created")
        Instant createdAt
    ) {
        public static RegisterResponse fromModel(IdpUser user) {
            return new RegisterResponse(
                user.userId(),
                user.email(),
                user.fullName(),
                user.status(),
                user.createdAt()
            );
        }
    }

    // -------------------------------------------------------------------
    // Login
    // -------------------------------------------------------------------

    @Schema(description = "Login request")
    public record LoginRequest(
        @NotBlank @Schema(description = "Tenant to authenticate against")
        String tenantId,

        @NotBlank @Email @Schema(description = "User email address")
        String email,

        @NotBlank @Schema(description = "User password")
        String password
    ) {}

    @Schema(description = "Login response with access and refresh tokens")
    public record LoginResponse(
        @Schema(description = "Short-lived JWT access token")
        String accessToken,

        @Schema(description = "Long-lived refresh token")
        String refreshToken,

        @Schema(description = "Access token TTL in seconds")
        long expiresIn,

        @Schema(description = "Token type (Bearer)")
        String tokenType,

        @Schema(description = "Authenticated user's subject identifier")
        String subject,

        @Schema(description = "User's email address")
        String email,

        @Schema(description = "User's roles")
        Set<String> roles
    ) {
        public static LoginResponse fromModel(AuthResult result) {
            return new LoginResponse(
                result.accessToken(),
                result.refreshToken(),
                result.expiresIn(),
                result.tokenType(),
                result.identity().subject(),
                result.identity().email(),
                result.identity().roles()
            );
        }
    }

    // -------------------------------------------------------------------
    // Refresh
    // -------------------------------------------------------------------

    @Schema(description = "Token refresh request")
    public record RefreshRequest(
        @NotBlank @Schema(description = "Tenant identifier")
        String tenantId,

        @NotBlank @Schema(description = "Refresh token from login")
        String refreshToken
    ) {}

    @Schema(description = "Token refresh response")
    public record RefreshResponse(
        @Schema(description = "New short-lived JWT access token")
        String accessToken,

        @Schema(description = "New refresh token (rotation)")
        String refreshToken,

        @Schema(description = "Access token TTL in seconds")
        long expiresIn,

        @Schema(description = "Token type (Bearer)")
        String tokenType
    ) {
        public static RefreshResponse fromModel(AuthResult result) {
            return new RefreshResponse(
                result.accessToken(),
                result.refreshToken(),
                result.expiresIn(),
                result.tokenType()
            );
        }
    }

    // -------------------------------------------------------------------
    // Logout
    // -------------------------------------------------------------------

    @Schema(description = "Logout request — revokes the refresh token")
    public record LogoutRequest(
        @NotBlank @Schema(description = "Tenant identifier")
        String tenantId,

        @NotBlank @Schema(description = "Refresh token to revoke")
        String refreshToken
    ) {}

    // -------------------------------------------------------------------
    // Email Verification
    // -------------------------------------------------------------------

    @Schema(description = "Email verification request")
    public record VerifyEmailRequest(
        @NotBlank @Schema(description = "Tenant identifier")
        String tenantId,

        @NotBlank @Schema(description = "User identifier")
        String userId,

        @NotBlank @Schema(description = "Verification token from email")
        String token
    ) {}

    // -------------------------------------------------------------------
    // Password Reset
    // -------------------------------------------------------------------

    @Schema(description = "Forgot password request — initiates reset flow")
    public record ForgotPasswordRequest(
        @NotBlank @Schema(description = "Tenant identifier")
        String tenantId,

        @NotBlank @Email @Schema(description = "User email address")
        String email
    ) {}

    @Schema(description = "Reset password request — completes the reset")
    public record ResetPasswordRequest(
        @NotBlank @Schema(description = "Tenant identifier")
        String tenantId,

        @NotBlank @Schema(description = "Reset token from email")
        String token,

        @NotBlank @Size(min = 8, max = 128) @Schema(description = "New password (min 8 chars)")
        String newPassword
    ) {}
}
