package com.koostack.operio.core.platform.events;

import com.koostack.operio.core.event.DomainEvent;

public record OrganizationUpdatedEvent(
        String orgId, String orgName, String displayName, String description)
        implements DomainEvent {}
