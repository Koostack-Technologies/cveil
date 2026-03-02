package com.koostack.operio.core.tenant.consent.events;

import com.koostack.operio.core.event.DomainEvent;

public record FieldCategoryAssignedEvent(
        String entityName,
        String fieldName,
        String categoryName,
        String categoryDescription
) implements DomainEvent {}
