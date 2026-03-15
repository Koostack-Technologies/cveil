package com.koostack.cveil.core.platform.billing.events;

import com.koostack.cveil.core.event.DomainEvent;
import java.time.Instant;

/** Published when metered usage is reported to the external payment processor. */
public record UsageReportedEvent(
        String tenantId, String metricName, long quantity, Instant reportedAt)
        implements DomainEvent {}
