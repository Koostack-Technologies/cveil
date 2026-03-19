package com.koostack.cveil.platform.webhook;

import com.koostack.cveil.platform.webhook.model.WebhookEndpoint;
import com.koostack.cveil.plugin.event.jooq.StreamEventHandler;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Redis stream handler that forwards domain events to registered webhook endpoints. Registered as a
 * consumer-group handler on the sharded tenant event streams.
 *
 * <h2>Delivery semantics</h2>
 *
 * <p>This handler provides <strong>at-least-once</strong> delivery. If the process crashes after
 * {@code deliver()} succeeds but before the stream entry is acknowledged ({@code XACK}), the entry
 * will be redelivered on restart. Receivers <strong>must</strong> deduplicate on the {@code
 * eventId} field included in every payload.
 */
public class WebhookEventHandler implements StreamEventHandler {

    private static final Logger LOG = LoggerFactory.getLogger(WebhookEventHandler.class);

    /** Consumer group name — unique across all stream consumers. */
    public static final String GROUP_NAME = "webhook-delivery";

    /** Event types eligible for outbound webhook delivery. */
    private static final Set<String> SUBSCRIBABLE_EVENTS =
            Set.of(
                    "consent.subject.granted",
                    "consent.subject.revoked",
                    "consent.subject.all-revoked",
                    "consent.subject-entity.registered",
                    "consent.subject-entity.removed",
                    "consent.dsar.data-deleted",
                    "consent.dsar.shredded",
                    "consent.regulation.registered",
                    "consent.regulation.updated",
                    "consent.regulation.removed",
                    "consent.purpose.registered",
                    "consent.purpose.updated",
                    "consent.purpose.removed",
                    "consent.jurisdiction.registered",
                    "consent.jurisdiction.updated",
                    "consent.jurisdiction.removed");

    private final WebhookDeliveryService deliveryService;

    /** Virtual-thread executor for parallel fan-out to multiple endpoints. */
    private final ExecutorService fanOutExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public WebhookEventHandler(WebhookDeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    @Override
    public void onEvent(String streamKey, String messageId, Map<String, String> fields) {
        String eventType = fields.get("eventType");
        String tenantId = fields.get("tenantId");

        if (eventType == null || tenantId == null) {
            return;
        }

        if (!SUBSCRIBABLE_EVENTS.contains(eventType)) {
            return;
        }

        List<WebhookEndpoint> endpoints;
        try {
            endpoints = deliveryService.findMatchingEndpoints(tenantId, eventType);
        } catch (Exception e) {
            LOG.warn(
                    "Failed to query webhook endpoints for tenant {}: {}",
                    tenantId,
                    e.getMessage());
            return;
        }

        if (endpoints.isEmpty()) {
            return;
        }

        String jsonPayload = buildPayload(fields);

        // Fan out to all matching endpoints in parallel so a slow/failing
        // endpoint does not delay delivery to the others.
        CompletableFuture<?>[] futures =
                endpoints.stream()
                        .map(
                                ep ->
                                        CompletableFuture.runAsync(
                                                () -> {
                                                    try {
                                                        boolean success =
                                                                deliveryService.deliver(
                                                                        ep, eventType, jsonPayload);
                                                        if (success) {
                                                            LOG.debug(
                                                                    "Delivered {} to {} for tenant"
                                                                            + " {}",
                                                                    eventType,
                                                                    ep.url(),
                                                                    tenantId);
                                                        }
                                                    } catch (Exception e) {
                                                        LOG.warn(
                                                                "Unexpected error delivering"
                                                                        + " webhook to {}: {}",
                                                                ep.url(),
                                                                e.getMessage());
                                                    }
                                                },
                                                fanOutExecutor))
                        .toArray(CompletableFuture[]::new);

        CompletableFuture.allOf(futures).join();
    }

    private static String buildPayload(Map<String, String> fields) {
        // Deterministic key ordering for consistent signatures
        String[] keys = {
            "eventId", "eventType", "aggregateId", "aggregateType", "tenantId", "version"
        };
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (String key : keys) {
            String val = fields.get(key);
            if (val == null) continue;
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(key).append("\":\"").append(escapeJson(val)).append("\"");
        }
        // Nest raw payload JSON (already a JSON string from the event store)
        String rawPayload = fields.get("payload");
        if (rawPayload != null) {
            if (!first) sb.append(",");
            sb.append("\"data\":").append(rawPayload);
        }
        sb.append("}");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
