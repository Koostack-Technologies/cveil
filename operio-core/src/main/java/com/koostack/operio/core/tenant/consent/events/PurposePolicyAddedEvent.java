package com.koostack.operio.core.tenant.consent.events;

import com.koostack.operio.core.event.DomainEvent;

public record PurposePolicyAddedEvent(
        String purposeName,
        String policyType,
        String fieldName,
        String entityName,
        String categoryName
) implements DomainEvent {}
