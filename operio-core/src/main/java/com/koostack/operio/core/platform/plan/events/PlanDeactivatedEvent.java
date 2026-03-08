package com.koostack.operio.core.platform.plan.events;

import com.koostack.operio.core.event.DomainEvent;

/** Emitted when a plan is deactivated (no longer available for new subscriptions). */
public record PlanDeactivatedEvent(String planId) implements DomainEvent {}
