package com.koostack.cveil.core.platform.plan.events;

import com.koostack.cveil.core.event.DomainEvent;

/**
 * Emitted when a plan feature gate is created or updated.
 *
 * <p>Used by projections to materialize plan-level feature flag and limit data.
 */
public record PlanFeatureSetEvent(
        String planId, String featureKey, boolean enabled, Integer limitValue)
        implements DomainEvent {}
