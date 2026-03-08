package com.koostack.operio.core.platform.plan;

/**
 * Event type constants for plan domain events.
 *
 * <p>Each constant corresponds to a mutation published to the platform event store. Projections
 * consume these event types to materialize the plan read model.
 */
public final class PlanEventTypes {

    private PlanEventTypes() {}

    public static final String PLAN_CREATED = "plan.created";
    public static final String PLAN_UPDATED = "plan.updated";
    public static final String PLAN_DEACTIVATED = "plan.deactivated";
    public static final String PLAN_ACTIVATED = "plan.activated";
}
