package com.koostack.operio.core.control.events;

import com.koostack.operio.core.event.DomainEvent;

public record UsageLimitAdjustedEvent(
    String tenantId,
    String metric,
    long previousLimit,
    long newLimit
) implements DomainEvent {}
