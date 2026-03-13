package com.koostack.cveil.core.tenant.consent.events;

import com.koostack.cveil.core.event.DomainEvent;

public record PurposeRegulationUnlinkedEvent(
        String purposeName, String regulationName, PurposeSnapshot snapshot)
        implements DomainEvent {}
