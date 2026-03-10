package com.koostack.operio.platform.iam.jooq;

import com.koostack.operio.core.cqrs.CommandActuator;
import com.koostack.operio.core.cqrs.CommandDispatcher;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.platform.iam.IamQueryService;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrator that handles the full user registration flow:
 *
 * <ol>
 *   <li>Register user in Keycloak (single realm)
 *   <li>Create Keycloak Organization if it doesn't exist
 *   <li>Add user as organization member
 *   <li>Provision Redis infrastructure for the tenant
 * </ol>
 *
 * <p>Each step is dispatched through the command dispatcher, producing its own domain events. The
 * orchestrator itself does not publish additional events — it coordinates the sub-commands.
 *
 * <p>This replaces the old async provisioning pipeline (CreateTenant → ProvisioningStarted → schema
 * creation → Flyway → Redis ACL → Keycloak realm → API keys → TenantProvisioned) with a
 * synchronous, deterministic command chain.
 */
public class RegisterUserOrchestrator
        implements CommandActuator<
                RegisterUserOrchestrator.RegisterUserWithOrgCommand,
                RegisterUserOrchestrator.RegisterUserWithOrgResult> {

    private static final Logger LOG = LoggerFactory.getLogger(RegisterUserOrchestrator.class);

    private final CommandDispatcher dispatcher;
    private final EventService<?> eventService;
    private IamQueryService<?> queryService;

    public RegisterUserOrchestrator(CommandDispatcher dispatcher, EventService<?> eventService) {
        this.dispatcher = dispatcher;
        this.eventService = eventService;
    }

    /** Set the query service for user ID resolution. */
    public void setQueryService(IamQueryService<?> queryService) {
        this.queryService = queryService;
    }

    /**
     * Command input for registering a user with organization creation.
     *
     * @param realmId the Keycloak realm (single realm)
     * @param username the user's unique login name
     * @param email the user's email address
     * @param fullName the user's display name (nullable)
     * @param roles initial roles to assign (nullable — defaults to empty)
     * @param temporaryPassword if true, user must change password on first login
     * @param organizationName the organization/tenant name (null to skip org creation)
     * @param organizationDisplayName human-readable org display name (nullable)
     */
    public record RegisterUserWithOrgCommand(
            String realmId,
            String username,
            String email,
            String fullName,
            Set<String> roles,
            boolean temporaryPassword,
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
            this(realmId, username, email, fullName, roles, temporaryPassword, null, null);
        }
    }

    public record RegisterUserWithOrgResult(
            String username,
            String email,
            String realmId,
            String keycloakUserId,
            String orgId,
            String orgName,
            String redisStreamKey) {}

    @Override
    public RegisterUserWithOrgResult execute(RegisterUserWithOrgCommand command) {
        LOG.info(
                "Orchestrating user registration: username={}, email={}, org={}",
                command.username(),
                command.email(),
                command.organizationName());

        // Step 1: Register user in Keycloak
        var registerCmd =
                new RegisterUserCommandActuator.RegisterUserCommand(
                        command.realmId(),
                        command.username(),
                        command.email(),
                        command.fullName(),
                        command.roles(),
                        command.temporaryPassword());
        RegisterUserCommandActuator.RegisterUserResult userResult =
                dispatcher.dispatch(registerCmd);
        LOG.debug("Step 1 complete: user registered — username={}", userResult.username());

        // Resolve the Keycloak user ID for org membership
        String keycloakUserId = resolveKeycloakUserId(command.realmId(), command.email());

        String orgId = null;
        String orgName = command.organizationName();
        String redisStreamKey = null;

        if (orgName != null && !orgName.isBlank()) {
            // Step 2: Create organization (idempotent)
            var createOrgCmd =
                    new CreateOrganizationCommandActuator.CreateOrganizationCommand(
                            orgName, command.organizationDisplayName());
            CreateOrganizationCommandActuator.CreateOrganizationResult orgResult =
                    dispatcher.dispatch(createOrgCmd);
            orgId = orgResult.orgId();
            LOG.debug("Step 2 complete: organization created — orgId={}", orgId);

            // Step 3: Add user as organization member
            if (keycloakUserId != null) {
                var addMemberCmd =
                        new AddOrgMemberCommandActuator.AddOrgMemberCommand(
                                orgId, orgName, keycloakUserId, command.username());
                dispatcher.dispatch(addMemberCmd);
                LOG.debug("Step 3 complete: user added to org");
            } else {
                LOG.warn(
                        "Could not resolve Keycloak user ID for {} — skipping org membership",
                        command.email());
            }

            // Step 4: Provision Redis infrastructure for the tenant
            try {
                var provisionRedisCmd =
                        new ProvisionRedisCommandActuator.ProvisionRedisCommand(orgName);
                ProvisionRedisCommandActuator.ProvisionRedisResult redisResult =
                        dispatcher.dispatch(provisionRedisCmd);
                redisStreamKey = redisResult.streamKey();
                LOG.debug("Step 4 complete: Redis provisioned — stream={}", redisStreamKey);
            } catch (Exception e) {
                // Redis provisioning failure is non-fatal — the tenant can still operate
                // with degraded functionality. Redis can be provisioned later.
                LOG.warn(
                        "Redis provisioning failed for tenant {} (non-fatal): {}",
                        orgName,
                        e.getMessage());
            }
        }

        LOG.info(
                "User registration orchestration complete: username={}, org={}, orgId={}",
                command.username(),
                orgName,
                orgId);

        return new RegisterUserWithOrgResult(
                userResult.username(),
                userResult.email(),
                userResult.realmId(),
                keycloakUserId,
                orgId,
                orgName,
                redisStreamKey);
    }

    /**
     * Resolves the Keycloak user ID from email. Uses the query service to look up the user by email
     * in the Keycloak realm.
     */
    private String resolveKeycloakUserId(String realmId, String email) {
        if (queryService == null) {
            LOG.warn("Query service not set — cannot resolve Keycloak user ID for {}", email);
            return null;
        }
        try {
            return queryService.resolveUserId(realmId, email);
        } catch (Exception e) {
            LOG.warn("Could not resolve Keycloak user ID for email {}: {}", email, e.getMessage());
            return null;
        }
    }

    @Override
    public EventService<?> eventPublisher() {
        return eventService;
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
