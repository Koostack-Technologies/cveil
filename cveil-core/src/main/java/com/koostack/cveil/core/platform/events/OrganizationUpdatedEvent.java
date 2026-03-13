package com.koostack.cveil.core.platform.events;

import com.koostack.cveil.core.event.DomainEvent;

public record OrganizationUpdatedEvent(
        String orgId, String orgName, String displayName, String description)
        implements DomainEvent {}
