package com.koostack.operio.core.data.consent.events;

import com.koostack.operio.core.event.DomainEvent;

public record CategoryRegisteredEvent(
    String categoryName,
    String description
) implements DomainEvent {}
