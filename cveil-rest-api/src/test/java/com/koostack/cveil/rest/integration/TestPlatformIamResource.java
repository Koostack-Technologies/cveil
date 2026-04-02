package com.koostack.cveil.rest.integration;

import com.koostack.cveil.core.cqrs.AbstractOrchestrator;
import com.koostack.cveil.core.cqrs.CommandDispatcher;
import com.koostack.cveil.core.exception.CveilAuthException;
import com.koostack.cveil.core.models.AuthResult;
import com.koostack.cveil.core.platform.iam.PermissionService;
import com.koostack.cveil.platform.billing.jooq.CreateBillingProfileCommandActuator;
import com.koostack.cveil.platform.billing.jooq.CreateSubscriptionCommandActuator;
import com.koostack.cveil.platform.core.jooq.CreateTenantCommandActuator;
import com.koostack.cveil.platform.core.jooq.DeleteTenantCommandActuator;
import com.koostack.cveil.platform.iam.jooq.AuthenticateCommandActuator;
import com.koostack.cveil.platform.iam.jooq.DeleteUserCommandActuator;
import com.koostack.cveil.platform.iam.jooq.ProvisionRedisCommandActuator;
import com.koostack.cveil.platform.iam.jooq.RegisterUserCommandActuator;
import com.koostack.cveil.rest.config.PlatformCqrsHolder;
import com.koostack.cveil.rest.dto.CommonDtos.ErrorResponse;
import com.koostack.cveil.rest.util.TenantIdDeriver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test-only JAX-RS resource providing the platform IAM endpoints required for integration test
 * setup. These endpoints were moved to cveil-site during the service split, but the integration
 * tests still need them to provision tenants and authenticate.
 *
 * <p>This class lives in src/test/java so it is only available during testing.
 */
@Path("/api/v1/platform/iam")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class TestPlatformIamResource {

    private static final Logger LOG = LoggerFactory.getLogger(TestPlatformIamResource.class);
    private static final String REALM = "cveil";

    @Inject PlatformCqrsHolder cqrs;

    // -- DTOs used only by this test resource ---------------------------------

    public record RegisterUserRequest(
            String username,
            String email,
            String fullName,
            String displayName,
            Set<String> roles,
            boolean temporaryPassword,
            String password) {}

    public record UserRegisteredResponse(
            String username, String email, String tenantId, String plan, String status) {}

    public record LoginRequest(String email, String password) {}

    public record AuthResponse(
            String accessToken,
            String refreshToken,
            String tokenType,
            long expiresIn,
            String tenantId) {
        static AuthResponse from(AuthResult r) {
            String tid = r.identity() != null ? r.identity().tenantId() : null;
            return new AuthResponse(
                    r.accessToken(), r.refreshToken(), "Bearer", r.expiresIn(), tid);
        }
    }

    // -- Endpoints ------------------------------------------------------------

    @POST
    @Path("/users")
    public Response registerUser(@Context SecurityContext sc, RegisterUserRequest request) {
        LOG.info(
                "[TEST] POST /platform/iam/users username={}, email={}",
                request.username(),
                request.email());

        String tenantId = TenantIdDeriver.derive(request.username());

        var orchestrator =
                new TestRegisterUserOrchestrator(
                        cqrs.iamDispatcher(),
                        cqrs.coreDispatcher(),
                        cqrs.billingDispatcher(),
                        cqrs.isPermissionServiceAvailable() ? cqrs.permissionService() : null);

        var command =
                new TestRegisterUserOrchestrator.RegisterUserCommand(
                        REALM,
                        request.username(),
                        request.email(),
                        request.fullName(),
                        request.displayName(),
                        Set.of(),
                        request.temporaryPassword(),
                        request.password(),
                        tenantId);

        try {
            TestRegisterUserOrchestrator.RegisterUserResult result = orchestrator.execute(command);
            return Response.status(Response.Status.CREATED)
                    .entity(
                            new UserRegisteredResponse(
                                    result.username(),
                                    result.email(),
                                    result.tenantId(),
                                    result.plan(),
                                    result.tenantStatus()))
                    .build();
        } catch (Exception e) {
            LOG.error("[TEST] User registration failed: {}", e.getMessage(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ErrorResponse.of("registration_failed", e.getMessage()))
                    .build();
        }
    }

    @POST
    @Path("/login")
    public Response login(LoginRequest request) {
        LOG.info("[TEST] POST /platform/iam/login email={}", request.email());

        try {
            AuthResult result =
                    cqrs.iamDispatcher()
                            .dispatch(
                                    new AuthenticateCommandActuator.AuthenticateCommand(
                                            REALM, request.email(), request.password(), null));
            return Response.ok(AuthResponse.from(result)).build();
        } catch (CveilAuthException e) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(ErrorResponse.of("unauthorized", e.getMessage()))
                    .build();
        }
    }

    // -- Inline orchestrator (replicated from cveil-site) ---------------------

    static class TestRegisterUserOrchestrator
            extends AbstractOrchestrator<
                    TestRegisterUserOrchestrator.RegisterUserCommand,
                    TestRegisterUserOrchestrator.RegisterUserResult> {

        private static final String IAM = "iam";
        private static final String CORE = "core";
        private static final String BILLING = "billing";

        private final PermissionService permissionService;

        record RegisterUserCommand(
                String realmId,
                String username,
                String email,
                String fullName,
                String displayName,
                Set<String> roles,
                boolean temporaryPassword,
                String password,
                String tenantId) {}

        record RegisterUserResult(
                String username,
                String email,
                String realmId,
                String keycloakUserId,
                String tenantId,
                String plan,
                String tenantStatus) {}

        TestRegisterUserOrchestrator(
                CommandDispatcher iamDispatcher,
                CommandDispatcher coreDispatcher,
                CommandDispatcher billingDispatcher,
                PermissionService permissionService) {
            registerDispatcher(IAM, iamDispatcher);
            registerDispatcher(CORE, coreDispatcher);
            registerDispatcher(BILLING, billingDispatcher);
            this.permissionService = permissionService;
        }

        @Override
        protected List<SagaStep<RegisterUserCommand>> buildSteps(RegisterUserCommand input) {
            var steps = new java.util.ArrayList<SagaStep<RegisterUserCommand>>();

            // Step 1: Register user in Keycloak
            steps.add(
                    SagaStep.of(
                            "register-user",
                            (in, ctx) -> {
                                var cmd =
                                        new RegisterUserCommandActuator.RegisterUserCommand(
                                                in.realmId(),
                                                in.username(),
                                                in.email(),
                                                in.fullName(),
                                                in.roles(),
                                                in.temporaryPassword(),
                                                in.password(),
                                                in.tenantId());
                                RegisterUserCommandActuator.RegisterUserResult result =
                                        dispatcher(IAM).dispatch(cmd);
                                ctx.put("userResult", result);
                                ctx.put("keycloakUserId", result.keycloakUserId());
                                ctx.put("userPreExisting", result.preExisting());
                            },
                            (in, ctx) -> {
                                try {
                                    String kcUserId = ctx.get("keycloakUserId");
                                    Boolean pre = ctx.get("userPreExisting");
                                    if (kcUserId != null && !Boolean.TRUE.equals(pre)) {
                                        dispatcher(IAM)
                                                .dispatch(
                                                        new DeleteUserCommandActuator
                                                                .DeleteUserCommand(
                                                                in.realmId(), kcUserId));
                                    }
                                } catch (Exception ignored) {
                                }
                            }));

            // Step 2: Create tenant
            steps.add(
                    SagaStep.of(
                            "create-tenant",
                            (in, ctx) -> {
                                String name =
                                        in.displayName() != null && !in.displayName().isBlank()
                                                ? in.displayName()
                                                : in.username();
                                dispatcher(CORE)
                                        .dispatch(
                                                new CreateTenantCommandActuator.CreateTenantCommand(
                                                        in.tenantId(), name, "free", "user"));
                                ctx.put("tenantCreated", true);
                            },
                            (in, ctx) -> {
                                try {
                                    dispatcher(CORE)
                                            .dispatch(
                                                    new DeleteTenantCommandActuator
                                                            .DeleteTenantCommand(in.tenantId()));
                                } catch (Exception ignored) {
                                }
                            }));

            // Step 3: Assign DB-backed role
            steps.add(
                    SagaStep.of(
                            "assign-db-roles",
                            (in, ctx) -> {
                                if (permissionService == null) return;
                                String kcUserId = ctx.get("keycloakUserId");
                                permissionService.assignRole(
                                        kcUserId, in.tenantId(), "tenant-admin", kcUserId);
                            }));

            // Step 4: Provision Redis (non-fatal)
            steps.add(
                    SagaStep.of(
                            "provision-redis",
                            (in, ctx) -> {
                                try {
                                    dispatcher(IAM)
                                            .dispatch(
                                                    new ProvisionRedisCommandActuator
                                                            .ProvisionRedisCommand(in.tenantId()));
                                } catch (Exception ignored) {
                                }
                            }));

            // Step 5: Billing profile
            steps.add(
                    SagaStep.of(
                            "create-billing-profile",
                            (in, ctx) ->
                                    dispatcher(BILLING)
                                            .dispatch(
                                                    new CreateBillingProfileCommandActuator
                                                            .CreateBillingProfileCommand(
                                                            in.tenantId(), in.email()))));

            // Step 6: Subscription
            steps.add(
                    SagaStep.of(
                            "create-subscription",
                            (in, ctx) ->
                                    dispatcher(BILLING)
                                            .dispatch(
                                                    new CreateSubscriptionCommandActuator
                                                            .CreateSubscriptionCommand(
                                                            in.tenantId(),
                                                            "free",
                                                            null,
                                                            null,
                                                            null))));

            return steps;
        }

        @Override
        protected RegisterUserResult toResult(RegisterUserCommand input, SagaContext context) {
            RegisterUserCommandActuator.RegisterUserResult userResult = context.get("userResult");
            return new RegisterUserResult(
                    userResult.username(),
                    userResult.email(),
                    userResult.realmId(),
                    context.get("keycloakUserId"),
                    input.tenantId(),
                    "free",
                    "created");
        }

        @Override
        protected String orchestratorName() {
            return "TestRegisterUserOrchestrator";
        }
    }
}
