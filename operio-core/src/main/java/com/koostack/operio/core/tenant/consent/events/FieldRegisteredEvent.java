package com.koostack.operio.core.tenant.consent.events;

import com.koostack.operio.core.event.DomainEvent;

public record FieldRegisteredEvent(
        String entityName, String fieldName, String description, String entityDescription)
        implements DomainEvent {}
