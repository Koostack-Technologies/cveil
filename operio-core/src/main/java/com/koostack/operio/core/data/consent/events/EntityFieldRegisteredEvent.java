package com.koostack.operio.core.data.consent.events;

import com.koostack.operio.core.event.DomainEvent;

public record EntityFieldRegisteredEvent(
    String entityName,
    String fieldName,
    String description
) implements DomainEvent {}
