package com.koostack.cveil.core.platform.billing.events;

import com.koostack.cveil.core.event.DomainEvent;
import java.time.Instant;

public record SubscriptionCreatedEvent(
        String tenantId,
        String planTier,
        int evalLimit,
        int subjectLimit,
        int burstLimit,
        String externalSubscriptionId,
        Instant periodStart,
        Instant periodEnd)
        implements DomainEvent {}
