package com.koostack.operio.core.platform.plan.events;

import com.koostack.operio.core.event.DomainEvent;

/** Emitted when a previously deactivated plan is re-activated. */
public record PlanActivatedEvent(String planId) implements DomainEvent {}

