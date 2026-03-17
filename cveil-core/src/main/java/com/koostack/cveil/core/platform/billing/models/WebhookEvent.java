package com.koostack.cveil.core.platform.billing.models;

/**
 * Processor-agnostic representation of a verified webhook event.
 *
 * <p>The {@code payload} is the raw JSON body — projections and command handlers inspect the {@code
 * eventType} to determine how to interpret it.
 */
public record WebhookEvent(String externalEventId, String eventType, String payload) {}
