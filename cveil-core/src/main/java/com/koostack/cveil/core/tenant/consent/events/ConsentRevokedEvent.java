package com.koostack.cveil.core.tenant.consent.events;

import com.koostack.cveil.core.event.DomainEvent;
import com.koostack.cveil.core.event.Shreddable;

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
        String userAgent)
        implements DomainEvent, Shreddable {

    @Override
    public String subjectIdentifier() {
        return subjectId;
    }
}
