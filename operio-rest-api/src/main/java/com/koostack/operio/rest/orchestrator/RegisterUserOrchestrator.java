package com.koostack.operio.rest.orchestrator;

import com.koostack.operio.core.cqrs.AbstractOrchestrator;
import com.koostack.operio.core.cqrs.CommandDispatcher;
import com.koostack.operio.core.platform.iam.IamQueryService;
import com.koostack.operio.platform.billing.jooq.CreateBillingProfileCommandActuator;
import com.koostack.operio.platform.billing.jooq.CreateSubscriptionCommandActuator;
import com.koostack.operio.platform.core.jooq.CreateTenantCommandActuator;
import com.koostack.operio.platform.core.jooq.DeleteTenantCommandActuator;
import com.koostack.operio.platform.iam.jooq.AddOrgMemberCommandActuator;
import com.koostack.operio.platform.iam.jooq.CreateOrganizationCommandActuator;
import com.koostack.operio.platform.iam.jooq.DeleteOrganizationCommandActuator;
import com.koostack.operio.platform.iam.jooq.ProvisionRedisCommandActuator;
import com.koostack.operio.platform.iam.jooq.RegisterUserCommandActuator;
import com.koostack.operio.platform.iam.jooq.RemoveOrgMemberCommandActuator;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Unified orchestrator for the full user registration + tenant provisioning saga.
 *
 * <p>Coordinates commands across three bounded contexts (IAM, Core, Billing) in a single
 * deterministic saga with compensation on failure.
 *
 * <p>Steps executed in order (when an organization name is provided):
 * <ol>
 *   <li><b>register-user</b> — creates the user in Keycloak via IAM dispatcher.</li>
 *   <li><b>create-organization</b> — creates a Keycloak Organization via IAM dispatcher.
 *       Compensation: deletes the organization.</li>
 *   <li><b>add-org-member</b> — adds the user as an org member via IAM dispatcher.
 *       Compensation: removes the member.</li>
 *   <li><b>provision-redis</b> — provisions Redis stream + pub/sub via IAM dispatcher.
 *       Non-fatal — failure is swallowed.</li>
 *   <li><b>create-tenant</b> — creates the tenant aggregate via Core dispatcher.
 *       Compensation: soft-deletes the tenant.</li>
 *   <li><b>create-billing-profile</b> — creates billing profile via Billing dispatcher.</li>
 *   <li><b>create-subscription</b> — creates subscription via Billing dispatcher.</li>
 * </ol>
 *
 * <p>If no organization name is provided, only step 1 (register-user) is executed.
 */
public class RegisterUserOrchestrator
        extends AbstractOrchestrator<
                RegisterUserOrchestrator.RegisterUserWithOrgCommand,
                RegisterUserOrchestrator.RegisterUserWithOrgResult> {

    public static final String DISPATCHER_IAM = "iam";
    public static final String DISPATCHER_CORE = "core";
    public static final String DISPATCHER_BILLING = "billing";

    private IamQueryService<?> queryService;

    // -----------------------------------------------------------------------
    // Input / Output
    // -----------------------------------------------------------------------

    /**
     * Command input for the full registration saga.
     *
     * @param realmId                 the Keycloak realm
     * @param username                the user's unique login name
     * @param email                   the user's email address
     * @param fullName                the user's display name (nullable)
     * @param roles                   initial roles to assign (nullable)
     * @param temporaryPassword       if true, user must change password on first login
     * @param password                the user's initial password (nullable)
     * @param organizationName        the organization/tenant name (null to skip org + tenant steps)
     * @param organizationDisplayName human-readable org display name (nullable)
     */
    public record RegisterUserWithOrgCommand(
            String realmId,
            String username,
            String email,
            String fullName,
            Set<String> roles,
            boolean temporaryPassword,
            String password,
            String organizationName,
            String organizationDisplayName) {

        /** Convenience constructor for registration without organization. */
        public RegisterUserWithOrgCommand(
                String realmId,
                String username,
                String email,
                String fullName,
                Set<String> roles,
                boolean temporaryPassword) {
            this(realmId, username, email, fullName, roles, temporaryPassword, null, null, null);
        }
    }

    public record RegisterUserWithOrgResult(
            String username,
            String email,
            String realmId,
            String keycloakUserId,
            String orgId,
            String orgName,
            String redisStreamKey,
            String tenantId,
            String plan,
            String tenantStatus) {}

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    /**
     * Creates the orchestrator with all three dispatchers.
     *
     * @param iamDispatcher     dispatcher for IAM bounded context (Keycloak)
     * @param coreDispatcher    dispatcher for Core bounded context (tenants)
     * @param billingDispatcher dispatcher for Billing bounded context (profiles, subscriptions)
     */
    public RegisterUserOrchestrator(
            CommandDispatcher iamDispatcher,
            CommandDispatcher coreDispatcher,
            CommandDispatcher billingDispatcher) {
        registerDispatcher(DISPATCHER_IAM, iamDispatcher);
        registerDispatcher(DISPATCHER_CORE, coreDispatcher);
        registerDispatcher(DISPATCHER_BILLING, billingDispatcher);
    }

    /** Set the query service for Keycloak user ID resolution. */
    public void setQueryService(IamQueryService<?> queryService) {
        this.queryService = queryService;
    }

    // -----------------------------------------------------------------------
    // Saga definition
    // -----------------------------------------------------------------------

    @Override
    protected List<SagaStep<RegisterUserWithOrgCommand>> buildSteps(
            RegisterUserWithOrgCommand input) {
        List<SagaStep<RegisterUserWithOrgCommand>> steps = new ArrayList<>();

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
                                            in.password());
                            RegisterUserCommandActuator.RegisterUserResult result =
                                    dispatcher(DISPATCHER_IAM).dispatch(cmd);
                            ctx.put("userResult", result);

                            // Use the Keycloak user ID directly from the result
                            // (resolveKeycloakUserId is fragile — silently returns null
                            // if the query service or lookup fails)
                            String kcUserId = result.keycloakUserId();
                            if (kcUserId == null) {
                                kcUserId = resolveKeycloakUserId(in.realmId(), in.email());
                            }
                            ctx.put("keycloakUserId", kcUserId);
                        }));

        String orgName = input.organizationName();
        boolean hasOrg = orgName != null && !orgName.isBlank();

        if (hasOrg) {
            // Step 2: Create organization (idempotent in Keycloak)
            //   Compensation: delete the organization
            steps.add(
                    SagaStep.of(
                            "create-organization",
                            (in, ctx) -> {
                                var cmd =
                                        new CreateOrganizationCommandActuator
                                                .CreateOrganizationCommand(
                                                in.organizationName(),
                                                in.organizationDisplayName());
                                CreateOrganizationCommandActuator.CreateOrganizationResult result =
                                        dispatcher(DISPATCHER_IAM).dispatch(cmd);
                                ctx.put("orgId", result.orgId());
                                ctx.put("orgName", result.orgName());
                            },
                            (in, ctx) -> {
                                String orgId = ctx.get("orgId");
                                if (orgId != null) {
                                    dispatcher(DISPATCHER_IAM)
                                            .dispatch(
                                                    new DeleteOrganizationCommandActuator
                                                            .DeleteOrganizationCommand(
                                                            orgId, in.organizationName()));
                                }
                            }));

            // Step 3: Add user as organization member
            //   Compensation: remove the member
            steps.add(
                    SagaStep.of(
                            "add-org-member",
                            (in, ctx) -> {
                                String kcUserId = ctx.get("keycloakUserId");
                                String orgId = ctx.get("orgId");
                                if (kcUserId != null && orgId != null) {
                                    dispatcher(DISPATCHER_IAM)
                                            .dispatch(
                                                    new AddOrgMemberCommandActuator
                                                            .AddOrgMemberCommand(
                                                            orgId,
                                                            in.organizationName(),
                                                            kcUserId,
                                                            in.username()));
                                }
                            },
                            (in, ctx) -> {
                                String kcUserId = ctx.get("keycloakUserId");
                                String orgId = ctx.get("orgId");
                                if (kcUserId != null && orgId != null) {
                                    dispatcher(DISPATCHER_IAM)
                                            .dispatch(
                                                    new RemoveOrgMemberCommandActuator
                                                            .RemoveOrgMemberCommand(
                                                            orgId,
                                                            in.organizationName(),
                                                            kcUserId));
                                }
                            }));

            // Step 4: Provision Redis infrastructure (non-fatal)
            steps.add(
                    SagaStep.of(
                            "provision-redis",
                            (in, ctx) -> {
                                try {
                                    var cmd =
                                            new ProvisionRedisCommandActuator.ProvisionRedisCommand(
                                                    in.organizationName());
                                    ProvisionRedisCommandActuator.ProvisionRedisResult result =
                                            dispatcher(DISPATCHER_IAM).dispatch(cmd);
                                    ctx.put("redisStreamKey", result.streamKey());
                                } catch (Exception e) {
                                    // Redis provisioning failure is non-fatal
                                    ctx.put("redisStreamKey", null);
                                }
                            }));

            // Step 5: Create tenant aggregate (core context)
            //   Compensation: soft-delete the tenant
            steps.add(
                    SagaStep.of(
                            "create-tenant",
                            (in, ctx) -> {
                                String displayName =
                                        in.organizationDisplayName() != null
                                                ? in.organizationDisplayName()
                                                : in.organizationName();
                                dispatcher(DISPATCHER_CORE)
                                        .dispatch(
                                                new CreateTenantCommandActuator
                                                        .CreateTenantCommand(
                                                        in.organizationName(),
                                                        displayName,
                                                        "free"));
                                ctx.put("tenantCreated", true);
                            },
                            (in, ctx) -> {
                                dispatcher(DISPATCHER_CORE)
                                        .dispatch(
                                                new DeleteTenantCommandActuator
                                                        .DeleteTenantCommand(
                                                        in.organizationName()));
                            }));

            // Step 6: Create billing profile (billing context)
            steps.add(
                    SagaStep.of(
                            "create-billing-profile",
                            (in, ctx) -> {
                                String billingEmail =
                                        in.organizationName() + "@billing.operio.local";
                                dispatcher(DISPATCHER_BILLING)
                                        .dispatch(
                                                new CreateBillingProfileCommandActuator
                                                        .CreateBillingProfileCommand(
                                                        in.organizationName(), billingEmail));
                                ctx.put("billingProfileCreated", true);
                            }));

            // Step 7: Create subscription (billing context)
            steps.add(
                    SagaStep.of(
                            "create-subscription",
                            (in, ctx) -> {
                                dispatcher(DISPATCHER_BILLING)
                                        .dispatch(
                                                new CreateSubscriptionCommandActuator
                                                        .CreateSubscriptionCommand(
                                                        in.organizationName(), "free"));
                                ctx.put("subscriptionCreated", true);
                            }));
        }

        return steps;
    }

    @Override
    protected RegisterUserWithOrgResult toResult(
            RegisterUserWithOrgCommand input, SagaContext context) {
        RegisterUserCommandActuator.RegisterUserResult userResult = context.get("userResult");
        boolean tenantCreated =
                context.has("tenantCreated")
                        && Boolean.TRUE.equals(context.get("tenantCreated"));

        return new RegisterUserWithOrgResult(
                userResult.username(),
                userResult.email(),
                userResult.realmId(),
                context.get("keycloakUserId"),
                context.get("orgId"),
                context.get("orgName"),
                context.get("redisStreamKey"),
                tenantCreated ? input.organizationName() : null,
                tenantCreated ? "free" : null,
                tenantCreated ? "created" : null);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private String resolveKeycloakUserId(String realmId, String email) {
        if (queryService == null) {
            return null;
        }
        try {
            return queryService.resolveUserId(realmId, email);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected String orchestratorName() {
        return "RegisterUserOrchestrator";
    }
}

