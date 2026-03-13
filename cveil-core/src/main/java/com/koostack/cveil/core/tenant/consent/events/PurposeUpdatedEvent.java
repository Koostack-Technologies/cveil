package com.koostack.cveil.core.tenant.consent.events;

import com.koostack.cveil.core.event.DomainEvent;

public record PurposeUpdatedEvent(String purposeName, String description, PurposeSnapshot snapshot)
        implements DomainEvent {}
