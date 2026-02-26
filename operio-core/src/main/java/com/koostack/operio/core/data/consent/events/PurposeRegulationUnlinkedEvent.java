package com.koostack.operio.core.data.consent.events;

public record PurposeRegulationUnlinkedEvent(
    String purposeName,
    String regulationName
) implements DomainEvent {}
