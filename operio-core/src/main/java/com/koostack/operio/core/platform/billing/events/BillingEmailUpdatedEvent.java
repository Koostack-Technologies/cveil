package com.koostack.operio.core.platform.billing.events;

import com.koostack.operio.core.event.DomainEvent;

public record BillingEmailUpdatedEvent(String tenantId, String newEmail) implements DomainEvent {}
