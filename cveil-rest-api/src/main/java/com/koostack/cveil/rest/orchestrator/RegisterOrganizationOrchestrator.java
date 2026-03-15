package com.koostack.cveil.rest.orchestrator;

import com.koostack.cveil.core.cqrs.AbstractOrchestrator;
import com.koostack.cveil.core.cqrs.CommandDispatcher;
import com.koostack.cveil.core.platform.iam.PermissionService;
import com.koostack.cveil.platform.billing.jooq.CreateBillingProfileCommandActuator;
import com.koostack.cveil.platform.billing.jooq.CreateSubscriptionCommandActuator;
import com.koostack.cveil.platform.core.jooq.CreateTenantCommandActuator;
import com.koostack.cveil.platform.core.jooq.DeleteTenantCommandActuator;
import com.koostack.cveil.platform.iam.jooq.AddOrgMemberCommandActuator;
import com.koostack.cveil.platform.iam.jooq.CreateOrganizationCommandActuator;
import com.koostack.cveil.platform.iam.jooq.DeleteOrganizationCommandActuator;
import com.koostack.cveil.platform.iam.jooq.ProvisionRedisCommandActuator;
import com.koostack.cveil.platform.iam.jooq.RemoveOrgMemberCommandActuator;
import com.koostack.cveil.plugin.consent.jooq.SeedConsentGovernanceCommandActuator;
import com.koostack.cveil.rest.util.TenantIdDeriver;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Orchestrator for the full organization registration saga.
 *
 * <p>Coordinates commands across three bounded contexts (IAM, Core, Billing) to create a Keycloak
 * Organization, add the requesting user as admin, and provision the tenant with billing.
 *
 * <p>Steps executed in order:
 *
 * <ol>
 *   <li><b>create-organization</b> — creates a Keycloak Organization via IAM dispatcher.
 *       Compensation: deletes the organization.
 *   <li><b>add-org-member</b> — adds the requesting user as an org member. Compensation: removes
 *       the member.
 *   <li><b>assign-org-admin-role</b> — assigns {@code org-admin:{orgName}} realm role.
 *       Compensation: removes the role.
 *   <li><b>provision-redis</b> — provisions Redis stream + pub/sub (non-fatal).
 *   <li><b>create-tenant</b> — creates the tenant aggregate. Compensation: soft-deletes.
 *   <li><b>seed-consent-governance</b> — seeds default US consent governance data (non-fatal).
 *   <li><b>create-billing-profile</b> — creates billing profile.
 *   <li><b>create-subscription</b> — creates subscription.
 * </ol>
 */
public class RegisterOrganizationOrchestrator
        extends AbstractOrchestrator<
                RegisterOrganizationOrchestrator.RegisterOrgCommand,
                RegisterOrganizationOrchestrator.RegisterOrgResult> {

    private static final Logger LOG =
            Logger.getLogger(RegisterOrganizationOrchestrator.class.getName());

    public static final String DISPATCHER_IAM = "iam";
    public static final String DISPATCHER_CORE = "core";
    public static final String DISPATCHER_BILLING = "billing";
    public static final String DISPATCHER_CONSENT = "consent";

    public record RegisterOrgCommand(
            String realmId,
            String orgName,
            String displayName,
            String requestingKeycloakUserId,
            String requestingUsername,
            String plan) {}

    public record RegisterOrgResult(
            String orgId, String orgName, String tenantId, String plan, String tenantStatus) {}

    private final PermissionService permissionService;
    private final CommandDispatcher consentDispatcher;

    public RegisterOrganizationOrchestrator(
            CommandDispatcher iamDispatcher,
            CommandDispatcher coreDispatcher,
            CommandDispatcher billingDispatcher,
            PermissionService permissionService,
            CommandDispatcher consentDispatcher) {
        registerDispatcher(DISPATCHER_IAM, iamDispatcher);
        registerDispatcher(DISPATCHER_CORE, coreDispatcher);
        registerDispatcher(DISPATCHER_BILLING, billingDispatcher);
        this.permissionService = permissionService;
        this.consentDispatcher = consentDispatcher;
    }

    /** Backward-compatible constructor for callers without PermissionService or consent. */
    public RegisterOrganizationOrchestrator(
            CommandDispatcher iamDispatcher,
            CommandDispatcher coreDispatcher,
            CommandDispatcher billingDispatcher) {
        this(iamDispatcher, coreDispatcher, billingDispatcher, null, null);
    }

    @Override
    protected List<SagaStep<RegisterOrgCommand>> buildSteps(RegisterOrgCommand input) {
        List<SagaStep<RegisterOrgCommand>> steps = new ArrayList<>();

        // Step 1: Create organization in Keycloak
        steps.add(
                SagaStep.of(
                        "create-organization",
                        (in, ctx) -> {
                            var cmd =
                                    new CreateOrganizationCommandActuator.CreateOrganizationCommand(
                                            in.orgName(), in.displayName());
                            CreateOrganizationCommandActuator.CreateOrganizationResult result =
                                    dispatcher(DISPATCHER_IAM).dispatch(cmd);
                            ctx.put("orgId", result.orgId());
                            ctx.put("orgName", result.orgName());
                        },
                        (in, ctx) -> {
                            try {
                                String orgId = ctx.get("orgId");
                                if (orgId != null) {
                                    dispatcher(DISPATCHER_IAM)
                                            .dispatch(
                                                    new DeleteOrganizationCommandActuator
                                                            .DeleteOrganizationCommand(
                                                            orgId, in.orgName()));
                                }
                            } catch (Exception e) {
                                LOG.warning(
                                        "Compensation failed for create-organization: "
                                                + e.getMessage());
                            }
                        }));

        // Step 2: Add requesting user as organization member
        steps.add(
                SagaStep.of(
                        "add-org-member",
                        (in, ctx) -> {
                            String orgId = ctx.get("orgId");
                            dispatcher(DISPATCHER_IAM)
                                    .dispatch(
                                            new AddOrgMemberCommandActuator.AddOrgMemberCommand(
                                                    orgId,
                                                    in.orgName(),
                                                    in.requestingKeycloakUserId(),
                                                    in.requestingUsername()));
                        },
                        (in, ctx) -> {
                            try {
                                String orgId = ctx.get("orgId");
                                if (orgId != null) {
                                    dispatcher(DISPATCHER_IAM)
                                            .dispatch(
                                                    new RemoveOrgMemberCommandActuator
                                                            .RemoveOrgMemberCommand(
                                                            orgId,
                                                            in.orgName(),
                                                            in.requestingKeycloakUserId()));
                                }
                            } catch (Exception e) {
                                LOG.warning(
                                        "Compensation failed for add-org-member: "
                                                + e.getMessage());
                            }
                        }));

        // Step 3: Assign org-admin role to the requesting user
        // Step 3: KC org-admin role skipped — DB-backed roles are authoritative (step 4).
        // Kept as no-op to preserve saga step ordering for compensation.

        // Step 4: Assign DB-backed roles (org-admin + tenant-admin for the org's tenant)
        steps.add(
                SagaStep.of(
                        "assign-db-roles",
                        (in, ctx) -> {
                            if (permissionService == null) {
                                LOG.info(
                                        "PermissionService not available — skipping DB role"
                                                + " assignment");
                                return;
                            }
                            String tenantId = TenantIdDeriver.derive(in.orgName());
                            String userId = in.requestingKeycloakUserId();
                            permissionService.assignRole(userId, tenantId, "org-admin", userId);
                            permissionService.assignRole(userId, tenantId, "tenant-admin", userId);
                            ctx.put("dbRolesAssigned", true);
                        },
                        (in, ctx) -> {
                            try {
                                if (permissionService != null
                                        && ctx.get("dbRolesAssigned") != null) {
                                    String tenantId = TenantIdDeriver.derive(in.orgName());
                                    String userId = in.requestingKeycloakUserId();
                                    permissionService.removeRole(userId, tenantId, "org-admin");
                                    permissionService.removeRole(userId, tenantId, "tenant-admin");
                                }
                            } catch (Exception e) {
                                LOG.warning(
                                        "Compensation failed for assign-db-roles: "
                                                + e.getMessage());
                            }
                        }));

        // Step 5: Provision Redis infrastructure (non-fatal)
        steps.add(
                SagaStep.of(
                        "provision-redis",
                        (in, ctx) -> {
                            try {
                                var cmd =
                                        new ProvisionRedisCommandActuator.ProvisionRedisCommand(
                                                in.orgName());
                                ProvisionRedisCommandActuator.ProvisionRedisResult result =
                                        dispatcher(DISPATCHER_IAM).dispatch(cmd);
                                ctx.put("redisStreamKey", result.streamKey());
                            } catch (Exception e) {
                                ctx.put("redisStreamKey", null);
                            }
                        }));

        // Step 6: Create tenant aggregate
        steps.add(
                SagaStep.of(
                        "create-tenant",
                        (in, ctx) -> {
                            String displayName =
                                    in.displayName() != null ? in.displayName() : in.orgName();
                            dispatcher(DISPATCHER_CORE)
                                    .dispatch(
                                            new CreateTenantCommandActuator.CreateTenantCommand(
                                                    in.orgName(), displayName, in.plan(), "org"));
                            ctx.put("tenantCreated", true);
                        },
                        (in, ctx) -> {
                            try {
                                dispatcher(DISPATCHER_CORE)
                                        .dispatch(
                                                new DeleteTenantCommandActuator.DeleteTenantCommand(
                                                        in.orgName()));
                            } catch (Exception e) {
                                LOG.warning(
                                        "Compensation failed for create-tenant: " + e.getMessage());
                            }
                        }));

        // Step 7: Seed default consent governance data (non-fatal)
        steps.add(
                SagaStep.of(
                        "seed-consent-governance",
                        (in, ctx) -> {
                            if (consentDispatcher == null) {
                                LOG.info(
                                        "Consent dispatcher not available — skipping governance"
                                                + " seed");
                                return;
                            }
                            try {
                                consentDispatcher.dispatch(
                                        new SeedConsentGovernanceCommandActuator
                                                .SeedConsentGovernanceCommand(in.orgName()));
                                ctx.put("consentGovernanceSeeded", true);
                            } catch (Exception e) {
                                LOG.warning(
                                        "Consent governance seed failed (non-fatal): "
                                                + e.getMessage());
                            }
                        }));

        // Step 8: Create billing profile
        steps.add(
                SagaStep.of(
                        "create-billing-profile",
                        (in, ctx) -> {
                            String billingEmail = in.orgName() + "@billing.cveil.local";
                            dispatcher(DISPATCHER_BILLING)
                                    .dispatch(
                                            new CreateBillingProfileCommandActuator
                                                    .CreateBillingProfileCommand(
                                                    in.orgName(), billingEmail));
                            ctx.put("billingProfileCreated", true);
                        }));

        // Step 9: Create subscription
        steps.add(
                SagaStep.of(
                        "create-subscription",
                        (in, ctx) -> {
                            dispatcher(DISPATCHER_BILLING)
                                    .dispatch(
                                            new CreateSubscriptionCommandActuator
                                                    .CreateSubscriptionCommand(
                                                    in.orgName(), in.plan(), null, null, null));
                            ctx.put("subscriptionCreated", true);
                        }));

        return steps;
    }

    @Override
    protected RegisterOrgResult toResult(RegisterOrgCommand input, SagaContext context) {
        return new RegisterOrgResult(
                context.get("orgId"),
                context.get("orgName"),
                input.orgName(),
                input.plan(),
                "created");
    }

    @Override
    protected String orchestratorName() {
        return "RegisterOrganizationOrchestrator";
    }
}
