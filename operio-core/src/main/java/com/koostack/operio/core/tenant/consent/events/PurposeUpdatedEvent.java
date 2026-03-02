package com.koostack.operio.core.tenant.consent.events;

import com.koostack.operio.core.event.DomainEvent;

public record PurposeUpdatedEvent(
        String purposeName,
        String description,
        PurposeSnapshot snapshot
) implements DomainEvent {}
