package com.koostack.operio.core.tenant.consent.events;

import com.koostack.operio.core.event.DomainEvent;

public record CategoryRemovedEvent(String categoryName) implements DomainEvent {}
