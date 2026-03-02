package com.koostack.operio.core.tenant.consent.events;

import com.koostack.operio.core.event.DomainEvent;

public record PurposeRegulationUnlinkedEvent(
        String purposeName,
        String regulationName,
        PurposeSnapshot snapshot
) implements DomainEvent {}
