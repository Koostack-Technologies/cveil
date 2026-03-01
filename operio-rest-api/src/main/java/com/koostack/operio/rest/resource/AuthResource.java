package com.koostack.operio.rest.resource;

import com.koostack.operio.core.PlatformService;
import com.koostack.operio.core.exception.OperioAuthException;
import com.koostack.operio.core.exception.OperioServiceException;
import com.koostack.operio.core.models.AuthResult;
import com.koostack.operio.core.models.IdpUser;
import com.koostack.operio.core.platform.iam.IdpProvider;
import com.koostack.operio.core.platform.iam.IdpTenantResolver;
import com.koostack.operio.rest.dto.AuthDtos.ForgotPasswordRequest;
import com.koostack.operio.rest.dto.AuthDtos.LoginRequest;
import com.koostack.operio.rest.dto.AuthDtos.LoginResponse;
import com.koostack.operio.rest.dto.AuthDtos.LogoutRequest;
import com.koostack.operio.rest.dto.AuthDtos.RefreshRequest;
import com.koostack.operio.rest.dto.AuthDtos.RefreshResponse;
import com.koostack.operio.rest.dto.AuthDtos.RegisterRequest;
import com.koostack.operio.rest.dto.AuthDtos.RegisterResponse;
import com.koostack.operio.rest.dto.AuthDtos.ResetPasswordRequest;
import com.koostack.operio.rest.dto.AuthDtos.VerifyEmailRequest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Public authentication endpoints — no bearer token required.
 *
 * <p>User management operations (register, verify-email, password reset)
 * delegate to {@link PlatformService}. Token operations (login, refresh,
 * logout) resolve the tenant's {@link IdpProvider} directly.
 *
 * <p>These paths are whitelisted in {@code ApiKeyAuthFilter.isPublicPath()}.
 */
@Path("/api/v1/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Authentication", description = "User authentication and account management")
public class AuthResource {

    private static final Logger LOG = LoggerFactory.getLogger(AuthResource.class);

    @Inject
    Instance<PlatformService> platformServiceInstance;

    @Inject
    Instance<IdpTenantResolver> idpTenantResolverInstance;

    // -------------------------------------------------------------------
    // User management (delegated to PlatformService)
    // -------------------------------------------------------------------

    @POST
    @Path("/register")
    @Operation(summary = "Register a new user",
        description = "Creates a new user account. The account starts in 'unverified' " +
            "status until the email is confirmed.")
    @APIResponse(responseCode = "201", description = "User created successfully")
    @APIResponse(responseCode = "409", description = "Email already registered")
    @APIResponse(responseCode = "503", description = "IdP not available")
    public Response register(@Valid RegisterRequest request) {
        PlatformService platform = requirePlatformService();

        try {
            IdpUser user = platform.registerUser(
                request.tenantId(),
                request.email(),
                request.password(),
                request.fullName()
            );

            LOG.info("User registered: tenant={}, userId={}", request.tenantId(), user.userId());

            return Response.status(Response.Status.CREATED)
                .entity(RegisterResponse.fromModel(user))
                .build();
        } catch (OperioServiceException e) {
            if (e.getMessage() != null && e.getMessage().contains("already exists")) {
                return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", "conflict", "message", e.getMessage()))
                    .type(jakarta.ws.rs.core.MediaType.APPLICATION_JSON)
                    .build();
            }
            throw e;
        }
    }

    @POST
    @Path("/verify-email")
    @Operation(summary = "Verify email address",
        description = "Confirms the user's email address using the token from the verification email.")
    @APIResponse(responseCode = "204", description = "Email verified")
    @APIResponse(responseCode = "401", description = "Invalid or expired token")
    public Response verifyEmail(@Valid VerifyEmailRequest request) {
        PlatformService platform = requirePlatformService();
        try {
            platform.verifyEmail(request.tenantId(), request.userId(), request.token());
            return Response.noContent().build();
        } catch (OperioAuthException e) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(Map.of("error", "unauthorized", "message", e.getMessage()))
                .type(jakarta.ws.rs.core.MediaType.APPLICATION_JSON)
                .build();
        }
    }

    @POST
    @Path("/forgot-password")
    @Operation(summary = "Initiate password reset",
        description = "Sends a password reset email. Does not reveal whether the email exists.")
    @APIResponse(responseCode = "202", description = "Reset email sent (if account exists)")
    public Response forgotPassword(@Valid ForgotPasswordRequest request) {
        PlatformService platform = requirePlatformService();
        platform.initiatePasswordReset(request.tenantId(), request.email());
        // Always return 202 to avoid user enumeration
        return Response.accepted(Map.of(
            "message", "If an account with this email exists, a password reset email has been sent."
        )).build();
    }

    @POST
    @Path("/reset-password")
    @Operation(summary = "Complete password reset",
        description = "Resets the password using the token from the reset email.")
    @APIResponse(responseCode = "204", description = "Password reset successfully")
    @APIResponse(responseCode = "401", description = "Invalid or expired token")
    public Response resetPassword(@Valid ResetPasswordRequest request) {
        PlatformService platform = requirePlatformService();
        try {
            platform.completePasswordReset(request.tenantId(), request.token(), request.newPassword());
            return Response.noContent().build();
        } catch (OperioAuthException e) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(Map.of("error", "unauthorized", "message", e.getMessage()))
                .type(jakarta.ws.rs.core.MediaType.APPLICATION_JSON)
                .build();
        }
    }

    // -------------------------------------------------------------------
    // Token operations (direct IdP proxy)
    // -------------------------------------------------------------------

    @POST
    @Path("/login")
    @Operation(summary = "Authenticate user",
        description = "Authenticates with email and password, returns access and refresh tokens.")
    @APIResponse(responseCode = "200", description = "Authentication successful")
    @APIResponse(responseCode = "401", description = "Invalid credentials")
    public Response login(@Valid LoginRequest request) {
        IdpProvider<?> idp = resolveIdp(request.tenantId());

        try {
            AuthResult result = idp.authenticate(
                request.tenantId(),
                request.email(),
                request.password()
            );

            return Response.ok(LoginResponse.fromModel(result)).build();
        } catch (OperioAuthException e) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(Map.of("error", "unauthorized", "message", e.getMessage()))
                .type(jakarta.ws.rs.core.MediaType.APPLICATION_JSON)
                .build();
        }
    }

    @POST
    @Path("/refresh")
    @Operation(summary = "Refresh access token",
        description = "Exchanges a valid refresh token for new access and refresh tokens.")
    @APIResponse(responseCode = "200", description = "Tokens refreshed")
    @APIResponse(responseCode = "401", description = "Invalid or expired refresh token")
    public Response refresh(@Valid RefreshRequest request) {
        IdpProvider<?> idp = resolveIdp(request.tenantId());

        try {
            AuthResult result = idp.refreshToken(
                request.tenantId(),
                request.refreshToken()
            );

            return Response.ok(RefreshResponse.fromModel(result)).build();
        } catch (OperioAuthException e) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(Map.of("error", "unauthorized", "message", e.getMessage()))
                .type(jakarta.ws.rs.core.MediaType.APPLICATION_JSON)
                .build();
        }
    }

    @POST
    @Path("/logout")
    @Operation(summary = "Logout — revoke refresh token",
        description = "Revokes the given refresh token, ending the session.")
    @APIResponse(responseCode = "204", description = "Logged out successfully")
    public Response logout(@Valid LogoutRequest request) {
        IdpProvider<?> idp = resolveIdp(request.tenantId());
        idp.logout(request.tenantId(), request.refreshToken());
        return Response.noContent().build();
    }

    // -------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------

    private PlatformService requirePlatformService() {
        if (!platformServiceInstance.isResolvable()) {
            throw new OperioServiceException(
                "Platform service not configured — " +
                "is operio.mode=production with Keycloak configured?"
            );
        }
        PlatformService service = platformServiceInstance.get();
        if (service == null) {
            throw new OperioServiceException(
                "Platform service not configured — " +
                "is operio.mode=production with Keycloak configured?"
            );
        }
        return service;
    }

    private IdpProvider<?> resolveIdp(String tenantId) {
        if (!idpTenantResolverInstance.isResolvable()) {
            throw new OperioServiceException(
                "Identity provider not configured — " +
                "is operio.mode=production with Keycloak configured?"
            );
        }
        return idpTenantResolverInstance.get().resolve(tenantId);
    }
}
