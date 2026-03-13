package com.koostack.cveil.rest.orchestrator;

import com.koostack.cveil.core.cqrs.AbstractOrchestrator;
import com.koostack.cveil.core.cqrs.CommandDispatcher;
import com.koostack.cveil.platform.iam.jooq.InviteOrgMemberCommandActuator;
import java.util.List;

/**
 * Single-step orchestrator for inviting a member to a Keycloak Organization.
 *
 * <p>No compensation — invites are not reversible (Keycloak manages the invite lifecycle).
 */
public class InviteOrgMemberOrchestrator
        extends AbstractOrchestrator<
                InviteOrgMemberOrchestrator.InviteOrgMemberCommand,
                InviteOrgMemberOrchestrator.InviteOrgMemberResult> {

    public static final String DISPATCHER_IAM = "iam";

    public record InviteOrgMemberCommand(
            String orgId, String orgName, String inviteeEmail, String inviterUserId) {}

    public record InviteOrgMemberResult(String orgId, String inviteeEmail, String status) {}

    public InviteOrgMemberOrchestrator(CommandDispatcher iamDispatcher) {
        registerDispatcher(DISPATCHER_IAM, iamDispatcher);
    }

    @Override
    protected List<SagaStep<InviteOrgMemberCommand>> buildSteps(InviteOrgMemberCommand input) {
        return List.of(
                SagaStep.of(
                        "invite-member",
                        (in, ctx) -> {
                            var cmd =
                                    new InviteOrgMemberCommandActuator.InviteOrgMemberCommand(
                                            in.orgId(),
                                            in.orgName(),
                                            in.inviteeEmail(),
                                            in.inviterUserId());
                            InviteOrgMemberCommandActuator.InviteOrgMemberResult result =
                                    dispatcher(DISPATCHER_IAM).dispatch(cmd);
                            ctx.put("orgId", result.orgId());
                            ctx.put("inviteeEmail", result.inviteeEmail());
                        }));
    }

    @Override
    protected InviteOrgMemberResult toResult(InviteOrgMemberCommand input, SagaContext context) {
        return new InviteOrgMemberResult(
                context.get("orgId"), context.get("inviteeEmail"), "invited");
    }

    @Override
    protected String orchestratorName() {
        return "InviteOrgMemberOrchestrator";
    }
}
