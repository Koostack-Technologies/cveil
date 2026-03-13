package com.koostack.cveil.core.tenant.consent.events;

import com.koostack.cveil.core.event.DomainEvent;

public record PurposeRegulationLinkedEvent(
        String purposeName,
        String regulationName,
        String lawfulBasis,
        String onGranted,
        String onDenied,
        PurposeSnapshot snapshot)
        implements DomainEvent {}
