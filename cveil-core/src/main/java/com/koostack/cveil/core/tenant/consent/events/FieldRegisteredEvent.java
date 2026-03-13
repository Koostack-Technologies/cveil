package com.koostack.cveil.core.tenant.consent.events;

import com.koostack.cveil.core.event.DomainEvent;

public record FieldRegisteredEvent(
        String entityName, String fieldName, String description, String entityDescription)
        implements DomainEvent {}
