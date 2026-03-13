package com.koostack.cveil.core.tenant.consent.events;

import com.koostack.cveil.core.event.DomainEvent;

public record RegulationRemovedEvent(String regulationName) implements DomainEvent {}
