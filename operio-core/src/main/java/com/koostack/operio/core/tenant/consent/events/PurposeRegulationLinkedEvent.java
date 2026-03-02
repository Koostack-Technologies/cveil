package com.koostack.operio.core.tenant.consent.events;

import com.koostack.operio.core.event.DomainEvent;

public record PurposeRegulationLinkedEvent(
        String purposeName,
        String regulationName,
        String lawfulBasis,
        String onGranted,
        String onDenied
) implements DomainEvent {}
