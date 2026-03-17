package com.koostack.cveil.core.tenant.consent.events;

import com.koostack.cveil.core.event.DomainEvent;
import com.koostack.cveil.core.event.Shreddable;
import java.time.Instant;
import java.util.UUID;

public record ConsentGrantedEvent(
        String subjectId,
        UUID policyId,
        String purposeName,
        String policyType,
        String fieldName,
        String entityName,
        String categoryName,
        String actor,
        String ipAddress,
        String userAgent,
        Instant expiresAt)
        implements DomainEvent, Shreddable {

    @Override
    public String subjectIdentifier() {
        return subjectId;
    }
}
