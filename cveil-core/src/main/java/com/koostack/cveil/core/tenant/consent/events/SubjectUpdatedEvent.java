package com.koostack.cveil.core.tenant.consent.events;

import com.koostack.cveil.core.event.DomainEvent;
import com.koostack.cveil.core.event.Shreddable;

public record SubjectUpdatedEvent(String subjectName, String description)
        implements DomainEvent, Shreddable {

    @Override
    public String subjectIdentifier() {
        return subjectName;
    }
}
