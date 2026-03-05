package com.koostack.operio.core.tenant.consent.events;

import com.koostack.operio.core.event.DomainEvent;
import com.koostack.operio.core.event.Shreddable;

public record ConsentExpiredEvent(
        String subjectId,
        long policyId,
        String purposeName,
        String policyType,
        String fieldName,
        String entityName,
        String categoryName
) implements DomainEvent, Shreddable {

    @Override
    public String subjectIdentifier() { return subjectId; }
}
