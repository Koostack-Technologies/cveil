package com.koostack.operio.core.data.consent.events;

import com.koostack.operio.core.event.DomainEvent;

public record EntityFieldRemovedEvent(
    String entityName,
    String fieldName
) implements DomainEvent {}
