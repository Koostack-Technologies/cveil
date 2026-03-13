package com.koostack.operio.core.tenant.consent.events;

import com.koostack.operio.core.event.DomainEvent;

public record FieldCategoryUnassignedEvent(
        String entityName, String fieldName, String categoryName, FieldSnapshot snapshot)
        implements DomainEvent {}
