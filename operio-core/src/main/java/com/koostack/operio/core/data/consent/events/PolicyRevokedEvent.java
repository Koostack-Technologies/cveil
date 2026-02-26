package com.koostack.operio.core.data.consent.events;

import com.koostack.operio.core.event.DomainEvent;

import java.time.Instant;

public record PolicyRevokedEvent(
    String subjectId,
    String purposeName,
    String fieldName,
    String entityName,
    String category,
    boolean granted,
    Instant expiresAt
) implements DomainEvent {}
