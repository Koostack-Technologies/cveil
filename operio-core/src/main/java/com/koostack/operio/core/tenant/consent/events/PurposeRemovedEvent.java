package com.koostack.operio.core.tenant.consent.events;

import com.koostack.operio.core.event.DomainEvent;

public record PurposeRemovedEvent(
        String purposeName
) implements DomainEvent {}
