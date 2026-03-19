package com.koostack.cveil.platform.webhook.model;

import java.time.Instant;
import java.util.UUID;

/** Immutable delivery-log entry written after each outbound webhook attempt. */
public record WebhookDeliveryAttempt(
        UUID id,
        UUID endpointId,
        String eventType,
        String payload,
        Integer statusCode,
        String responseBody,
        int attempt,
        Instant deliveredAt,
        Instant nextRetryAt,
        Instant createdAt) {}
