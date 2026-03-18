package com.koostack.cveil.core.platform.plan;

import java.util.List;
import java.util.Optional;

/**
 * Service interface for querying feature-gating configuration per plan.
 *
 * <p>Implementations should cache aggressively — plan features change only on plan updates or
 * migrations, not per-request.
 */
public interface FeatureGateService {

    /** Returns {@code true} if the given feature is enabled for the plan. */
    boolean isFeatureEnabled(String planId, String featureKey);

    /**
     * Returns the numeric limit for the feature on the given plan.
     *
     * @return the limit value, or {@code -1} if unlimited, or {@code 0} if disabled
     */
    int getFeatureLimit(String planId, String featureKey);

    /** Returns the full feature record, if one exists. */
    Optional<PlanFeature> getFeature(String planId, String featureKey);

    /** Returns all features configured for the given plan. */
    List<PlanFeature> getFeaturesForPlan(String planId);
}
