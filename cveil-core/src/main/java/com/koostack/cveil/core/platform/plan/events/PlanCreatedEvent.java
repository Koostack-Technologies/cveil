package com.koostack.cveil.core.platform.plan.events;

import com.koostack.cveil.core.event.DomainEvent;

/** Emitted when a new plan is defined. */
public record PlanCreatedEvent(
        String planId,
        String displayName,
        String description,
        long evalLimit,
        long subjectLimit,
        int burstLimit,
        boolean isPublic,
        boolean isActive,
        int sortOrder)
        implements DomainEvent {}
