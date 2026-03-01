package com.koostack.operio.core.platform.billing.events;

import com.koostack.operio.core.event.DomainEvent;

import java.time.Instant;

public record SubscriptionRenewedEvent(
    String tenantId,
    Instant periodStart,
    Instant periodEnd
) implements DomainEvent {}
