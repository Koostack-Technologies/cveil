package com.koostack.operio.core.data.consent.events;

import com.koostack.operio.core.event.DomainEvent;

public record PurposeRegulationLinkedEvent(
    String purposeName,
    String regulationName,
    String legalBasis
) implements DomainEvent {}
