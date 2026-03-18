package com.koostack.cveil.platform.webhook.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Persistent webhook endpoint registered by a tenant. */
public record WebhookEndpoint(
        UUID id,
        String tenantId,
        String url,
        String secret,
        List<String> events,
        boolean enabled,
        String description,
        Instant createdAt,
        Instant updatedAt,
        int consecutiveFailures,
        Instant disabledAt,
        String disabledReason,
        String previousSecret,
        Instant previousSecretExpiresAt) {

    /** Convenience constructor for backwards compatibility (no reliability fields). */
    public WebhookEndpoint(
            UUID id,
            String tenantId,
            String url,
            String secret,
            List<String> events,
            boolean enabled,
            String description,
            Instant createdAt,
            Instant updatedAt) {
        this(
                id,
                tenantId,
                url,
                secret,
                events,
                enabled,
                description,
                createdAt,
                updatedAt,
                0,
                null,
                null,
                null,
                null);
    }
}
