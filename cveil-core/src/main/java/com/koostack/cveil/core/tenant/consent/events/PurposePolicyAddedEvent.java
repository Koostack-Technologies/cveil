package com.koostack.cveil.core.tenant.consent.events;

import com.koostack.cveil.core.event.DomainEvent;

public record PurposePolicyAddedEvent(
        String purposeName,
        String policyType,
        String fieldName,
        String entityName,
        String categoryName,
        PurposeSnapshot snapshot)
        implements DomainEvent {}
