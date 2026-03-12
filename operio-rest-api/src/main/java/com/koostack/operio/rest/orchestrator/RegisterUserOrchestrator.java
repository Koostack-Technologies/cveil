package com.koostack.operio.rest.orchestrator;

import com.koostack.operio.core.cqrs.AbstractOrchestrator;
import com.koostack.operio.core.cqrs.CommandDispatcher;
import com.koostack.operio.platform.billing.jooq.CreateBillingProfileCommandActuator;
import com.koostack.operio.platform.billing.jooq.CreateSubscriptionCommandActuator;
import com.koostack.operio.platform.core.jooq.CreateTenantCommandActuator;
import com.koostack.operio.platform.core.jooq.DeleteTenantCommandActuator;
import com.koostack.operio.platform.iam.jooq.DeleteUserCommandActuator;
import com.koostack.operio.platform.iam.jooq.ProvisionRedisCommandActuator;
import com.koostack.operio.platform.iam.jooq.RegisterUserCommandActuator;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Orchestrator for public self-service user registration.
 *
 * <p>Coordinates commands across three bounded contexts (IAM, Core, Billing) in a deterministic
 * saga with best-effort compensation on failure.
 *
 * <p>{@code username} becomes the user's personal {@code tenant_id}. Every registration creates a
 * personal tenant + billing profile + subscription on the free plan. No Keycloak Organization is
 * created — use {@link RegisterOrganizationOrchestrator} for that.
 *
 * <p>Steps executed in order:
 *
 * <ol>
 *   <li><b>register-user</b> — creates the user in Keycloak. Compensation: deletes the KC user.
 *   <li><b>create-tenant</b> — creates the tenant aggregate. Compensation: soft-deletes the tenant.
 *   <li><b>provision-redis</b> — provisions Redis stream + pub/sub + ACL user (non-fatal).
 *   <li><b>create-billing-profile</b> — creates billing profile.
 *   <li><b>create-subscription</b> — creates subscription on free plan.
 * </ol>
 */
public class RegisterUserOrchestrator
        extends AbstractOrchestrator<
                RegisterUserOrchestrator.RegisterUserCommand,
                RegisterUserOrchestrator.RegisterUserResult> {

    private static final Logger LOG = Logger.getLogger(RegisterUserOrchestrator.class.getName());

    public static final String DISPATCHER_IAM = "iam";
    public static final String DISPATCHER_CORE = "core";
    public static final String DISPATCHER_BILLING = "billing";

    // -----------------------------------------------------------------------
    // Input / Output
    // -----------------------------------------------------------------------

    /**
     * Command input for the user registration saga.
     *
     * @param realmId the Keycloak realm
     * @param username the user's unique login name
     * @param email the user's email address
     * @param fullName the user's display name (nullable)
     * @param roles initial roles to assign (nullable)
     * @param temporaryPassword if true, user must change password on first login
     * @param password the user's initial password (nullable)
     * @param tenantId the derived tenant ID (from username)
     */
    public record RegisterUserCommand(
            String realmId,
            String username,
            String email,
            String fullName,
            Set<String> roles,
            boolean temporaryPassword,
            String password,
            String tenantId) {}

    public record RegisterUserResult(
            String username,
            String email,
            String realmId,
            String keycloakUserId,
            String tenantId,
            String plan,
            String tenantStatus) {}

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    public RegisterUserOrchestrator(
            CommandDispatcher iamDispatcher,
            CommandDispatcher coreDispatcher,
            CommandDispatcher billingDispatcher) {
        registerDispatcher(DISPATCHER_IAM, iamDispatcher);
        registerDispatcher(DISPATCHER_CORE, coreDispatcher);
        registerDispatcher(DISPATCHER_BILLING, billingDispatcher);
    }

    // -----------------------------------------------------------------------
    // Saga definition
    // -----------------------------------------------------------------------

    @Override
    protected List<SagaStep<RegisterUserCommand>> buildSteps(RegisterUserCommand input) {
        return List.of(
                // Step 1: Register user in Keycloak
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
                                    dispatcher(DISPATCHER_IAM).dispatch(cmd);
                            ctx.put("userResult", result);
                            ctx.put("keycloakUserId", result.keycloakUserId());
                        },
                        (in, ctx) -> {
                            try {
                                String kcUserId = ctx.get("keycloakUserId");
                                if (kcUserId != null) {
                                    dispatcher(DISPATCHER_IAM)
                                            .dispatch(
                                                    new DeleteUserCommandActuator.DeleteUserCommand(
                                                            in.realmId(), kcUserId));
                                }
                            } catch (Exception e) {
                                LOG.warning(
                                        "Compensation failed for register-user: " + e.getMessage());
                            }
                        }),

                // Step 2: Create tenant aggregate
                SagaStep.of(
                        "create-tenant",
                        (in, ctx) -> {
                            dispatcher(DISPATCHER_CORE)
                                    .dispatch(
                                            new CreateTenantCommandActuator.CreateTenantCommand(
                                                    in.tenantId(), in.username(), "free"));
                            ctx.put("tenantCreated", true);
                        },
                        (in, ctx) -> {
                            try {
                                dispatcher(DISPATCHER_CORE)
                                        .dispatch(
                                                new DeleteTenantCommandActuator.DeleteTenantCommand(
                                                        in.tenantId()));
                            } catch (Exception e) {
                                LOG.warning(
                                        "Compensation failed for create-tenant: " + e.getMessage());
                            }
                        }),

                // Step 3: Provision Redis infrastructure (non-fatal)
                SagaStep.of(
                        "provision-redis",
                        (in, ctx) -> {
                            try {
                                var cmd =
                                        new ProvisionRedisCommandActuator.ProvisionRedisCommand(
                                                in.tenantId());
                                ProvisionRedisCommandActuator.ProvisionRedisResult result =
                                        dispatcher(DISPATCHER_IAM).dispatch(cmd);
                                ctx.put("redisStreamKey", result.streamKey());
                            } catch (Exception e) {
                                LOG.warning(
                                        "Redis provisioning non-fatal failure for tenant "
                                                + in.tenantId()
                                                + ": "
                                                + e.getMessage());
                                ctx.put("redisStreamKey", null);
                            }
                        }),

                // Step 4: Create billing profile
                SagaStep.of(
                        "create-billing-profile",
                        (in, ctx) -> {
                            dispatcher(DISPATCHER_BILLING)
                                    .dispatch(
                                            new CreateBillingProfileCommandActuator
                                                    .CreateBillingProfileCommand(
                                                    in.tenantId(), in.email()));
                            ctx.put("billingProfileCreated", true);
                        }),

                // Step 5: Create subscription on free plan
                SagaStep.of(
                        "create-subscription",
                        (in, ctx) -> {
                            dispatcher(DISPATCHER_BILLING)
                                    .dispatch(
                                            new CreateSubscriptionCommandActuator
                                                    .CreateSubscriptionCommand(
                                                    in.tenantId(), "free"));
                            ctx.put("subscriptionCreated", true);
                        }));
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
        return "RegisterUserOrchestrator";
    }
}
