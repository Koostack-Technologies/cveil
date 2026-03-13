package com.koostack.operio.core.platform.events;

import com.koostack.operio.core.event.DomainEvent;

public record UserActivatedEvent(String realmId, String userId) implements DomainEvent {}
