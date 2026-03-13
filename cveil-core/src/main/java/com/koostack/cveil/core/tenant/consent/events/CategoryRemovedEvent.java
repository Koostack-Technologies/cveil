package com.koostack.cveil.core.tenant.consent.events;

import com.koostack.cveil.core.event.DomainEvent;

public record CategoryRemovedEvent(String categoryName) implements DomainEvent {}
