package com.koostack.cveil.core.tenant.consent.events;

import com.koostack.cveil.core.event.DomainEvent;

public record FieldCategoryAssignedEvent(
        String entityName,
        String fieldName,
        String categoryName,
        String categoryDescription,
        FieldSnapshot snapshot)
        implements DomainEvent {}
