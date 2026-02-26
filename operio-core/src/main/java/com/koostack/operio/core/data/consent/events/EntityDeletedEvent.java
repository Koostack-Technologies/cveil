package com.koostack.operio.core.data.consent.events;

import com.koostack.operio.core.event.DomainEvent;

public record EntityDeletedEvent(
    String entityName
) implements DomainEvent {}
