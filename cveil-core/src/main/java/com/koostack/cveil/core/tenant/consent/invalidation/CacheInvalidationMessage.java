package com.koostack.cveil.core.tenant.consent.invalidation;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Lightweight notification telling a client which cache entry to evict.
 *
 * <p>Only {@code messageId}, {@code tenantId}, and {@code action} are always present. The remaining
 * fields are nullable and populated depending on the event type — see {@link InvalidationAction}
 * for semantics.
 *
 * <p>When {@code subject} is {@code null}, the invalidation affects <em>all</em> subjects (e.g.,
 * metadata events like {@link InvalidationAction#CATEGORY_CHANGED} or {@link
 * InvalidationAction#FIELD_REMOVED}). Clients must conservatively flush all matching entries.
 *
 * @param messageId UUID for SSE {@code Last-Event-ID} / deduplication
 * @param tenantId tenant that owns the consent data
 * @param subject nullable — the consent subject; null for metadata-only events
 * @param entity nullable — entity name; null for entity-less fields or ALL_REVOKED
 * @param field nullable — field name; null for ALL_REVOKED or CATEGORY_DELETED
 * @param category nullable — category name; populated for CATEGORY_CHANGED events
 * @param purpose nullable — purpose name; null for ALL_REVOKED or metadata events
 * @param action the type of mutation that triggered this invalidation
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CacheInvalidationMessage(
        String messageId,
        String tenantId,
        String subject,
        String entity,
        String field,
        String category,
        String purpose,
        InvalidationAction action) {}
