package com.koostack.cveil.core.platform.plan;

/**
 * Represents a single feature flag / limit for a plan tier.
 *
 * @param planId the plan this feature belongs to (e.g. "free", "pro")
 * @param featureKey the feature identifier (e.g. "webhooks", "dsar", "api_keys")
 * @param enabled whether the feature is available on this plan
 * @param limitValue optional numeric limit (null = unlimited when enabled, 0 = disabled)
 */
public record PlanFeature(String planId, String featureKey, boolean enabled, Integer limitValue) {

    /** Returns true if the feature is enabled and has no numeric limit. */
    public boolean isUnlimited() {
        return enabled && (limitValue == null || limitValue < 0);
    }
}
