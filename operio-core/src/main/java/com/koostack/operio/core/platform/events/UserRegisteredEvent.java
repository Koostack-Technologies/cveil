package com.koostack.operio.core.platform.events;

import com.koostack.operio.core.event.DomainEvent;
import java.util.Set;

public record UserRegisteredEvent(
        String realmId,
        String userId,
        String username,
        String email,
        String fullName,
        Set<String> roles,
        boolean temporaryPassword)
        implements DomainEvent {}
