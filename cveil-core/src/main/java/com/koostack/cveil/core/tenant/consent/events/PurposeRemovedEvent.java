package com.koostack.cveil.core.tenant.consent.events;

import com.koostack.cveil.core.event.DomainEvent;

public record PurposeRemovedEvent(String purposeName) implements DomainEvent {}
