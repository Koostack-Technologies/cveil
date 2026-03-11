package com.koostack.operio.rest.orchestrator;

import com.koostack.operio.core.cqrs.AbstractOrchestrator;
import com.koostack.operio.core.cqrs.CommandDispatcher;
import com.koostack.operio.platform.iam.jooq.UpdateOrganizationCommandActuator;
import java.util.List;

/**
 * Single-step orchestrator for updating a Keycloak Organization's metadata.
 *
 * <p>No compensation — would need to store previous state; out of scope.
 */
public class UpdateOrganizationOrchestrator
        extends AbstractOrchestrator<
                UpdateOrganizationOrchestrator.UpdateOrgCommand,
                UpdateOrganizationOrchestrator.UpdateOrgResult> {

    public static final String DISPATCHER_IAM = "iam";

    public record UpdateOrgCommand(
            String orgId, String orgName, String displayName, String description) {}

    public record UpdateOrgResult(String orgId, String displayName) {}

    public UpdateOrganizationOrchestrator(CommandDispatcher iamDispatcher) {
        registerDispatcher(DISPATCHER_IAM, iamDispatcher);
    }

    @Override
    protected List<SagaStep<UpdateOrgCommand>> buildSteps(UpdateOrgCommand input) {
        return List.of(
                SagaStep.of(
                        "update-organization",
                        (in, ctx) -> {
                            var cmd =
                                    new UpdateOrganizationCommandActuator.UpdateOrganizationCommand(
                                            in.orgId(),
                                            in.orgName(),
                                            in.displayName(),
                                            in.description());
                            UpdateOrganizationCommandActuator.UpdateOrganizationResult result =
                                    dispatcher(DISPATCHER_IAM).dispatch(cmd);
                            ctx.put("orgId", result.orgId());
                            ctx.put("displayName", result.displayName());
                        }));
    }

    @Override
    protected UpdateOrgResult toResult(UpdateOrgCommand input, SagaContext context) {
        return new UpdateOrgResult(context.get("orgId"), context.get("displayName"));
    }

    @Override
    protected String orchestratorName() {
        return "UpdateOrganizationOrchestrator";
    }
}
