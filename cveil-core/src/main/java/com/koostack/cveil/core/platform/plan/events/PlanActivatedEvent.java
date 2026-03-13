package com.koostack.cveil.core.platform.plan.events;

import com.koostack.cveil.core.event.DomainEvent;

/** Emitted when a previously deactivated plan is re-activated. */
public record PlanActivatedEvent(String planId) implements DomainEvent {}
