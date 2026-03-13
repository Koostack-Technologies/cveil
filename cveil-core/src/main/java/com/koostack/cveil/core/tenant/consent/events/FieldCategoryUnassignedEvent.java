package com.koostack.cveil.core.tenant.consent.events;

import com.koostack.cveil.core.event.DomainEvent;

public record FieldCategoryUnassignedEvent(
        String entityName, String fieldName, String categoryName, FieldSnapshot snapshot)
        implements DomainEvent {}
