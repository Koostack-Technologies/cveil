package com.koostack.cveil.core.platform.billing.events;

import com.koostack.cveil.core.event.DomainEvent;

public record BillingTaxIdUpdatedEvent(String tenantId, String taxId) implements DomainEvent {}
