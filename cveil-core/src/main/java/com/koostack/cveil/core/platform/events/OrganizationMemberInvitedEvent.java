package com.koostack.cveil.core.platform.events;

import com.koostack.cveil.core.event.DomainEvent;

public record OrganizationMemberInvitedEvent(
        String orgId, String orgName, String inviteeEmail, String inviterUserId)
        implements DomainEvent {}
