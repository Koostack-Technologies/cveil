package com.koostack.operio.core.tenant.consent.events;

import com.koostack.operio.core.event.DomainEvent;

public record PurposePolicyRemovedEvent(
        String purposeName,
        String policyType,
        String fieldName,
        String entityName,
        String categoryName,
        PurposeSnapshot snapshot
) implements DomainEvent {}
