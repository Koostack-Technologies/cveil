package com.koostack.operio.core.tenant.consent.events;

import java.time.Instant;

import com.koostack.operio.core.event.DomainEvent;
import com.koostack.operio.core.event.Shreddable;

public record ConsentGrantedEvent(
        String subjectId,
        long policyId,
        String purposeName,
        String policyType,
        String fieldName,
        String entityName,
        String categoryName,
        String actor,
        String ipAddress,
        String userAgent,
        Instant expiresAt
) implements DomainEvent, Shreddable {

    @Override
    public String subjectIdentifier() { return subjectId; }
}
