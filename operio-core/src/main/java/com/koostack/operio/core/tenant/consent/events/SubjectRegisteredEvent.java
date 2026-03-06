package com.koostack.operio.core.tenant.consent.events;

import com.koostack.operio.core.event.DomainEvent;
import com.koostack.operio.core.event.Shreddable;

public record SubjectRegisteredEvent(String subjectName, String description)
        implements DomainEvent, Shreddable {

    @Override
    public String subjectIdentifier() {
        return subjectName;
    }
}
