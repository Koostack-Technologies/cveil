package com.koostack.operio.core.data.consent.events;

public record FieldCategoryUnlinkedEvent(
    String entityName,
    String fieldName,
    String categoryName
) implements DomainEvent {}
