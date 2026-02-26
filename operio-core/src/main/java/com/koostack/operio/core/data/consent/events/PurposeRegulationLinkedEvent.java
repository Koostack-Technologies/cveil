package com.koostack.operio.core.data.consent.events;

public record PurposeRegulationLinkedEvent(
    String purposeName,
    String regulationName,
    String legalBasis
) implements DomainEvent {}
