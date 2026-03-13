package com.koostack.cveil.core.tenant.consent.events;

import com.koostack.cveil.core.event.DomainEvent;

public record EntityRegisteredEvent(String entityName, String description) implements DomainEvent {}
