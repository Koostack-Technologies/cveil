package com.koostack.cveil.core.platform.billing.events;

import com.koostack.cveil.core.event.DomainEvent;
import java.time.Instant;

public record SubscriptionRenewedEvent(String tenantId, Instant periodStart, Instant periodEnd)
        implements DomainEvent {}
