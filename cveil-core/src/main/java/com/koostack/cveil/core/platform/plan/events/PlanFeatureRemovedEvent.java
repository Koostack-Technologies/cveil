package com.koostack.cveil.core.platform.plan.events;

import com.koostack.cveil.core.event.DomainEvent;

/** Emitted when a plan feature gate is removed. */
public record PlanFeatureRemovedEvent(String planId, String featureKey) implements DomainEvent {}
