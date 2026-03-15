package com.koostack.cveil.rest.orchestrator;

import com.koostack.cveil.core.cqrs.AbstractOrchestrator;
import com.koostack.cveil.core.cqrs.CommandDispatcher;
import com.koostack.cveil.core.platform.iam.PermissionService;
import com.koostack.cveil.platform.core.jooq.CreateTenantCommandActuator;
import com.koostack.cveil.platform.core.jooq.DeleteTenantCommandActuator;
import com.koostack.cveil.platform.iam.jooq.DeleteUserCommandActuator;
import com.koostack.cveil.platform.iam.jooq.ProvisionRedisCommandActuator;
import com.koostack.cveil.platform.iam.jooq.RegisterUserCommandActuator;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Fast synchronous portion of user registration — steps 1–4 only.
 *
 * <p>Executes the critical-path steps that must complete before the user can be authenticated:
 *
 * <ol>
 *   <li><b>register-user</b> — creates the user in Keycloak
 *   <li><b>create-tenant</b> — creates the tenant aggregate
 *   <li><b>assign-db-roles</b> — assigns tenant-admin role
 *   <li><b>provision-redis</b> — provisions Redis infrastructure (non-fatal)
 * </ol>
 *
 * <p>The slower steps (governance seeding, billing) are handled asynchronously by {@link
 * com.koostack.cveil.rest.lifecycle.DeferredProvisioningTask}.
 *
 * @see RegisterUserOrchestrator the original all-in-one orchestrator (kept for backward
 *     compatibility)
 */
public class RegisterUserSyncOrchestrator
        extends AbstractOrchestrator<
                RegisterUserSyncOrchestrator.RegisterUserSyncCommand,
                RegisterUserSyncOrchestrator.RegisterUserSyncResult> {

    private static final Logger LOG =
            Logger.getLogger(RegisterUserSyncOrchestrator.class.getName());

    public static final String DISPATCHER_IAM = "iam";
    public static final String DISPATCHER_CORE = "core";

    /**
     * Input for the synchronous registration saga.
     *
     * @param realmId the Keycloak realm
     * @param username the user's unique login name
     * @param email the user's email address
     * @param fullName the user's display name (nullable)
     * @param displayName the tenant display name (nullable — falls back to username)
     * @param roles initial roles to assign (nullable)
     * @param temporaryPassword if true, user must change password on first login
     * @param password the user's initial password
     * @param tenantId the derived tenant ID (from username)
     */
    public record RegisterUserSyncCommand(
            String realmId,
            String username,
            String email,
            String fullName,
            String displayName,
            Set<String> roles,
            boolean temporaryPassword,
            String password,
            String tenantId) {}

    public record RegisterUserSyncResult(
            String username,
            String email,
            String realmId,
            String keycloakUserId,
            String tenantId) {}

    private final PermissionService permissionService;

    public RegisterUserSyncOrchestrator(
            CommandDispatcher iamDispatcher,
            CommandDispatcher coreDispatcher,
            PermissionService permissionService) {
        registerDispatcher(DISPATCHER_IAM, iamDispatcher);
        registerDispatcher(DISPATCHER_CORE, coreDispatcher);
        this.permissionService = permissionService;
    }

    @Override
    protected List<SagaStep<RegisterUserSyncCommand>> buildSteps(RegisterUserSyncCommand input) {
        var steps = new ArrayList<SagaStep<RegisterUserSyncCommand>>();

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
                                    dispatcher(DISPATCHER_IAM).dispatch(cmd);
                            ctx.put("userResult", result);
                            ctx.put("keycloakUserId", result.keycloakUserId());
                            ctx.put("userPreExisting", result.preExisting());
                        },
                        (in, ctx) -> {
                            try {
                                String kcUserId = ctx.get("keycloakUserId");
                                Boolean preExisting = ctx.get("userPreExisting");
                                if (kcUserId != null && !Boolean.TRUE.equals(preExisting)) {
                                    dispatcher(DISPATCHER_IAM)
                                            .dispatch(
                                                    new DeleteUserCommandActuator.DeleteUserCommand(
                                                            in.realmId(), kcUserId));
                                }
                            } catch (Exception e) {
                                LOG.warning(
                                        "Compensation failed for register-user: " + e.getMessage());
                            }
                        }));

        // Step 2: Create tenant aggregate
        steps.add(
                SagaStep.of(
                        "create-tenant",
                        (in, ctx) -> {
                            String tenantDisplayName =
                                    in.displayName() != null && !in.displayName().isBlank()
                                            ? in.displayName()
                                            : in.username();
                            dispatcher(DISPATCHER_CORE)
                                    .dispatch(
                                            new CreateTenantCommandActuator.CreateTenantCommand(
                                                    in.tenantId(),
                                                    tenantDisplayName,
                                                    "free",
                                                    "user"));
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
                        }));

        // Step 3: Assign DB-backed tenant-admin role
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
                            String kcUserId = ctx.get("keycloakUserId");
                            permissionService.assignRole(
                                    kcUserId, in.tenantId(), "tenant-admin", kcUserId);
                            ctx.put("dbRolesAssigned", true);
                        },
                        (in, ctx) -> {
                            try {
                                if (permissionService != null
                                        && ctx.get("dbRolesAssigned") != null) {
                                    String kcUserId = ctx.get("keycloakUserId");
                                    permissionService.removeRole(
                                            kcUserId, in.tenantId(), "tenant-admin");
                                }
                            } catch (Exception e) {
                                LOG.warning(
                                        "Compensation failed for assign-db-roles: "
                                                + e.getMessage());
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
                        }));

        return steps;
    }

    @Override
    protected RegisterUserSyncResult toResult(
            RegisterUserSyncCommand input, SagaContext context) {
        RegisterUserCommandActuator.RegisterUserResult userResult = context.get("userResult");
        return new RegisterUserSyncResult(
                userResult.username(),
                userResult.email(),
                userResult.realmId(),
                context.get("keycloakUserId"),
                input.tenantId());
    }

    @Override
    protected String orchestratorName() {
        return "RegisterUserSyncOrchestrator";
    }
}
