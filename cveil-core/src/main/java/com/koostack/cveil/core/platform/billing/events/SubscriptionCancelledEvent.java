package com.koostack.cveil.core.platform.billing.events;

import com.koostack.cveil.core.event.DomainEvent;

public record SubscriptionCancelledEvent(String tenantId) implements DomainEvent {}
