package com.koostack.operio.core.data.consent.events;

import com.koostack.operio.core.event.DomainEvent;

public record FieldCategoryUnlinkedEvent(
    String entityName,
    String fieldName,
    String categoryName
) implements DomainEvent {}
