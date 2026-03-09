package com.koostack.operio.core.platform.plan;

import com.koostack.operio.core.spi.Plugin;
import java.util.List;

/**
 * Query service for subscription plans — reads pre-seeded plan definitions from the {@code
 * platform_core.plan} table.
 *
 * <p>Used by the platform tenant-creation flow to validate that the requested plan exists and is
 * active, and to retrieve limits for subscription creation.
 *
 * @param <T> the configuration type for the backing implementation
 */
public interface PlanQueryService<T> extends Plugin<T> {

    /** Read-only view of a plan definition. */
    record PlanInfo(
            String planId,
            String displayName,
            String description,
            long evalLimit,
            long subjectLimit,
            int burstLimit,
            boolean isPublic,
            boolean isActive,
            int sortOrder) {}

    /**
     * Get a single plan by ID.
     *
     * @param planId the plan identifier
     * @return the plan info, or {@code null} if not found
     */
    PlanInfo getPlan(String planId);

    /**
     * List all plans.
     *
     * @return all plan definitions
     */
    List<PlanInfo> listPlans();

    /**
     * List only active plans.
     *
     * @return active plan definitions, sorted by {@code sort_order}
     */
    List<PlanInfo> listActivePlans();
}
