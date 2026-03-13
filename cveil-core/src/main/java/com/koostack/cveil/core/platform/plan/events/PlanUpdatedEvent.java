package com.koostack.cveil.core.platform.plan.events;

import com.koostack.cveil.core.event.DomainEvent;

/** Emitted when a plan's mutable fields are updated. */
public record PlanUpdatedEvent(
        String planId,
        String displayName,
        String description,
        long evalLimit,
        long subjectLimit,
        int burstLimit,
        boolean isPublic,
        int sortOrder)
        implements DomainEvent {}
