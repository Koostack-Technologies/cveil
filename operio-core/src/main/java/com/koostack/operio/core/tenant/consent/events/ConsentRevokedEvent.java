package com.koostack.operio.core.tenant.consent.events;

import com.koostack.operio.core.event.DomainEvent;

public record ConsentRevokedEvent(
        String subjectId,
        long policyId,
        String purposeName,
        String policyType,
        String fieldName,
        String entityName,
        String categoryName,
        String actor,
        String ipAddress,
        String userAgent
) implements DomainEvent {}
