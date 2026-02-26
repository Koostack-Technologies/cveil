package com.koostack.operio.core.data.consent.events;

public record FieldCategoryLinkedEvent(
    String entityName,
    String fieldName,
    String categoryName
) implements DomainEvent {}
