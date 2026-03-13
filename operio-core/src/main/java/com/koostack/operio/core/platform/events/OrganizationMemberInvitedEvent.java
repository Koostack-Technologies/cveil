package com.koostack.operio.core.platform.events;

import com.koostack.operio.core.event.DomainEvent;

public record OrganizationMemberInvitedEvent(
        String orgId, String orgName, String inviteeEmail, String inviterUserId)
        implements DomainEvent {}
