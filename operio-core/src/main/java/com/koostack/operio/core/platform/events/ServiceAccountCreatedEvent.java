package com.koostack.operio.core.platform.events;

import com.koostack.operio.core.event.DomainEvent;
import java.util.Set;

public record ServiceAccountCreatedEvent(
        String realmId, String clientId, String name, Set<String> roles) implements DomainEvent {}
