package com.koostack.operio.core.platform.billing.events;

import com.koostack.operio.core.event.DomainEvent;

public record BillingTaxIdUpdatedEvent(
    String tenantId,
    String taxId
) implements DomainEvent {}
