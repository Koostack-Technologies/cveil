package com.koostack.cveil.core.tenant.consent.events;

import com.koostack.cveil.core.event.DomainEvent;
import com.koostack.cveil.core.event.Shreddable;

public record SubjectRemovedEvent(String subjectName) implements DomainEvent, Shreddable {

    @Override
    public String subjectIdentifier() {
        return subjectName;
    }
}
