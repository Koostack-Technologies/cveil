package com.koostack.cveil.core.tenant.consent.events;

import com.koostack.cveil.core.event.DomainEvent;

public record CategoryUpdatedEvent(String categoryName, String description)
        implements DomainEvent {}
