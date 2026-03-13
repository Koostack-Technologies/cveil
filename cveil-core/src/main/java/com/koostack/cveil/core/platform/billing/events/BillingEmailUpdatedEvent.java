package com.koostack.cveil.core.platform.billing.events;

import com.koostack.cveil.core.event.DomainEvent;

public record BillingEmailUpdatedEvent(String tenantId, String newEmail) implements DomainEvent {}
