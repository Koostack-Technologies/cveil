package com.koostack.cveil.platform.webhook;

import com.koostack.cveil.platform.webhook.model.WebhookEndpoint;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delivers webhook payloads to registered endpoints via HTTP POST with HMAC-SHA256 signing.
 *
 * <p>Uses Resilience4j for two reliability mechanisms:
 *
 * <ul>
 *   <li><strong>Circuit breaker</strong> — per-endpoint; opens after {@value #CB_FAILURE_THRESHOLD}
 *       consecutive failures to avoid wasting resources on dead endpoints. Auto-transitions to
 *       half-open after {@value #CB_WAIT_DURATION_MINUTES} minutes and probes with {@value
 *       #CB_HALF_OPEN_CALLS} calls.
 *   <li><strong>Async retry</strong> — failed first-attempt deliveries are logged with {@code
 *       next_retry_at} for pickup by the scheduled {@code WebhookRetryTask}. Exponential backoff:
 *       60 s → 300 s → 900 s (3 retries max).
 * </ul>
 */
public class WebhookDeliveryService {

    private static final Logger LOG = LoggerFactory.getLogger(WebhookDeliveryService.class);

    // -- jOOQ table/field references (no codegen required) --------------------
    private static final org.jooq.Table<?> WEBHOOK_ENDPOINT =
            DSL.table(DSL.name("cveilbilling", "webhook_endpoint"));
    private static final org.jooq.Table<?> WEBHOOK_DELIVERY_LOG =
            DSL.table(DSL.name("cveilbilling", "webhook_delivery_log"));

    // endpoint columns
    private static final org.jooq.Field<UUID> EP_ID = DSL.field(DSL.name("id"), SQLDataType.UUID);
    private static final org.jooq.Field<String> EP_TENANT_ID =
            DSL.field(DSL.name("tenant_id"), SQLDataType.VARCHAR);
    private static final org.jooq.Field<String> EP_URL =
            DSL.field(DSL.name("url"), SQLDataType.VARCHAR);
    private static final org.jooq.Field<String> EP_SECRET =
            DSL.field(DSL.name("secret"), SQLDataType.VARCHAR);
    private static final org.jooq.Field<String> EP_EVENTS =
            DSL.field(DSL.name("events"), SQLDataType.VARCHAR);
    private static final org.jooq.Field<Boolean> EP_ENABLED =
            DSL.field(DSL.name("enabled"), SQLDataType.BOOLEAN);
    private static final org.jooq.Field<String> EP_DESCRIPTION =
            DSL.field(DSL.name("description"), SQLDataType.VARCHAR);
    private static final org.jooq.Field<Integer> EP_CONSECUTIVE_FAILURES =
            DSL.field(DSL.name("consecutive_failures"), SQLDataType.INTEGER);
    private static final org.jooq.Field<Instant> EP_DISABLED_AT =
            DSL.field(DSL.name("disabled_at"), SQLDataType.INSTANT);
    private static final org.jooq.Field<String> EP_DISABLED_REASON =
            DSL.field(DSL.name("disabled_reason"), SQLDataType.VARCHAR);
    private static final org.jooq.Field<String> EP_PREVIOUS_SECRET =
            DSL.field(DSL.name("previous_secret"), SQLDataType.VARCHAR);
    private static final org.jooq.Field<Instant> EP_PREVIOUS_SECRET_EXPIRES_AT =
            DSL.field(DSL.name("previous_secret_expires_at"), SQLDataType.INSTANT);

    // delivery log columns
    private static final org.jooq.Field<UUID> DL_ID = DSL.field(DSL.name("id"), SQLDataType.UUID);
    private static final org.jooq.Field<UUID> DL_ENDPOINT_ID =
            DSL.field(DSL.name("endpoint_id"), SQLDataType.UUID);
    private static final org.jooq.Field<String> DL_EVENT_TYPE =
            DSL.field(DSL.name("event_type"), SQLDataType.VARCHAR);
    private static final org.jooq.Field<JSONB> DL_PAYLOAD =
            DSL.field(DSL.name("payload"), SQLDataType.JSONB);
    private static final org.jooq.Field<Integer> DL_STATUS_CODE =
            DSL.field(DSL.name("status_code"), SQLDataType.INTEGER);
    private static final org.jooq.Field<String> DL_RESPONSE_BODY =
            DSL.field(DSL.name("response_body"), SQLDataType.VARCHAR);
    private static final org.jooq.Field<Integer> DL_ATTEMPT =
            DSL.field(DSL.name("attempt"), SQLDataType.INTEGER);
    private static final org.jooq.Field<Instant> DL_DELIVERED_AT =
            DSL.field(DSL.name("delivered_at"), SQLDataType.INSTANT);
    private static final org.jooq.Field<Instant> DL_NEXT_RETRY_AT =
            DSL.field(DSL.name("next_retry_at"), SQLDataType.INSTANT);

    /** Maximum async retry attempts (after the initial delivery). */
    static final int MAX_RETRIES = 3;

    /** Backoff intervals for async retries (attempt 1 → 60s, 2 → 300s, 3 → 900s). */
    static final long[] RETRY_BACKOFF_SECONDS = {60, 300, 900};

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    // -- Circuit breaker tuning -----------------------------------------------
    private static final int CB_FAILURE_THRESHOLD = 5;
    private static final int CB_WAIT_DURATION_MINUTES = 5;
    private static final int CB_HALF_OPEN_CALLS = 2;

    /** Grace period during which the old secret remains valid after rotation. */
    public static final Duration SECRET_ROTATION_GRACE = Duration.ofHours(24);

    private final DSLContext dsl;
    private final HttpClient httpClient;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public WebhookDeliveryService(DSLContext dsl) {
        this.dsl = dsl;
        this.httpClient =
                HttpClient.newBuilder()
                        .connectTimeout(CONNECT_TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();

        CircuitBreakerConfig cbConfig =
                CircuitBreakerConfig.custom()
                        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                        .slidingWindowSize(CB_FAILURE_THRESHOLD)
                        .minimumNumberOfCalls(CB_FAILURE_THRESHOLD)
                        .failureRateThreshold(100) // open only on 100% failure across the window
                        .waitDurationInOpenState(Duration.ofMinutes(CB_WAIT_DURATION_MINUTES))
                        .permittedNumberOfCallsInHalfOpenState(CB_HALF_OPEN_CALLS)
                        .automaticTransitionFromOpenToHalfOpenEnabled(true)
                        .build();
        this.circuitBreakerRegistry = CircuitBreakerRegistry.of(cbConfig);
    }

    // -- SSRF protection ------------------------------------------------------

    /**
     * Validates that a webhook URL is safe to deliver to. Blocks private/loopback/link-local
     * addresses and non-HTTPS schemes to prevent SSRF attacks.
     *
     * @return null if the URL is safe, or an error message describing the violation
     */
    public static String validateWebhookUrl(String url) {
        if (url == null || url.isBlank()) {
            return "URL is required";
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return "Invalid URL format";
        }

        String scheme = uri.getScheme();
        if (scheme == null || !scheme.equalsIgnoreCase("https")) {
            return "Only HTTPS webhook URLs are allowed";
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return "URL must have a valid host";
        }

        // Block obvious dangerous hostnames
        String lowerHost = host.toLowerCase();
        if (lowerHost.equals("localhost")
                || lowerHost.equals("127.0.0.1")
                || lowerHost.equals("[::1]")
                || lowerHost.equals("0.0.0.0")
                || lowerHost.endsWith(".local")
                || lowerHost.endsWith(".internal")
                || lowerHost.equals("metadata.google.internal")
                || lowerHost.equals("169.254.169.254")) {
            return "Webhook URLs must not target private or internal addresses";
        }

        // Resolve DNS and check for private IP ranges
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress addr : addresses) {
                if (addr.isLoopbackAddress()
                        || addr.isLinkLocalAddress()
                        || addr.isSiteLocalAddress()
                        || addr.isAnyLocalAddress()
                        || addr.isMulticastAddress()) {
                    return "Webhook URLs must not resolve to private or internal addresses";
                }
                // Block AWS/GCP/Azure metadata IPs
                String ip = addr.getHostAddress();
                if (ip.startsWith("169.254.") || ip.startsWith("100.100.")) {
                    return "Webhook URLs must not target cloud metadata endpoints";
                }
            }
        } catch (Exception e) {
            return "Cannot resolve webhook URL host: " + host;
        }

        return null; // safe
    }

    // -- Endpoint discovery ---------------------------------------------------

    /** Returns all enabled endpoints for a tenant that subscribe to the given event type. */
    public List<WebhookEndpoint> findMatchingEndpoints(String tenantId, String eventType) {
        return dsl.select(
                        EP_ID,
                        EP_TENANT_ID,
                        EP_URL,
                        EP_SECRET,
                        EP_EVENTS,
                        EP_ENABLED,
                        EP_DESCRIPTION,
                        EP_CONSECUTIVE_FAILURES,
                        EP_DISABLED_AT,
                        EP_DISABLED_REASON,
                        EP_PREVIOUS_SECRET,
                        EP_PREVIOUS_SECRET_EXPIRES_AT)
                .from(WEBHOOK_ENDPOINT)
                .where(EP_TENANT_ID.eq(tenantId).and(EP_ENABLED.isTrue()))
                .fetchStream()
                .filter(
                        r -> {
                            String events = r.get(EP_EVENTS);
                            if (events == null) return false;
                            if ("*".equals(events.trim())) return true;
                            return Arrays.asList(events.split(",")).contains(eventType);
                        })
                .map(this::mapEndpoint)
                .toList();
    }

    // -- Delivery -------------------------------------------------------------

    /**
     * Delivers a webhook payload to the given endpoint. The call is guarded by a per-endpoint
     * Resilience4j circuit breaker — if the endpoint has failed {@value #CB_FAILURE_THRESHOLD}
     * consecutive times the circuit opens and deliveries short-circuit for {@value
     * #CB_WAIT_DURATION_MINUTES} minutes.
     *
     * <p>Failed deliveries are logged with a {@code next_retry_at} timestamp so the {@code
     * WebhookRetryTask} can pick them up for async retry.
     *
     * @return true if delivery succeeded (2xx status)
     */
    public boolean deliver(WebhookEndpoint endpoint, String eventType, String jsonPayload) {
        return deliverAttempt(endpoint, eventType, jsonPayload, 1);
    }

    /**
     * Internal delivery used by both first-attempt and retry paths.
     *
     * @param attempt 1-based attempt number (1 = initial, 2..MAX_RETRIES+1 = retries)
     */
    boolean deliverAttempt(
            WebhookEndpoint endpoint, String eventType, String jsonPayload, int attempt) {

        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(endpoint.id().toString());

        // If circuit is open, skip delivery and log accordingly.
        if (cb.getState() == CircuitBreaker.State.OPEN) {
            LOG.debug("Circuit open for endpoint {} — skipping delivery", endpoint.id());
            return false;
        }

        // SSRF guard — belt-and-suspenders
        String urlError = validateWebhookUrl(endpoint.url());
        if (urlError != null) {
            LOG.warn(
                    "Webhook delivery blocked by SSRF protection: {} (endpoint {})",
                    urlError,
                    endpoint.id());
            logDelivery(
                    endpoint.id(),
                    eventType,
                    jsonPayload,
                    0,
                    "Blocked: " + urlError,
                    attempt,
                    true);
            return false;
        }

        long timestamp = Instant.now().getEpochSecond();
        String signatureHeader = buildSignatureHeader(endpoint, jsonPayload, timestamp);
        int statusCode = 0;
        String responseBody = null;

        try {
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(endpoint.url()))
                            .timeout(REQUEST_TIMEOUT)
                            .header("Content-Type", "application/json")
                            .header("X-Cveil-Event", eventType)
                            .header("X-Cveil-Signature", signatureHeader)
                            .header("X-Cveil-Timestamp", String.valueOf(timestamp))
                            .header("User-Agent", "Cveil-Webhook/1.0")
                            .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                            .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            statusCode = response.statusCode();
            responseBody = truncate(response.body(), 2048);

            boolean success = statusCode >= 200 && statusCode < 300;

            if (success) {
                cb.onSuccess(0, java.util.concurrent.TimeUnit.MILLISECONDS);
                resetConsecutiveFailures(endpoint.id());
                logDelivery(
                        endpoint.id(),
                        eventType,
                        jsonPayload,
                        statusCode,
                        responseBody,
                        attempt,
                        true);
                return true;
            } else {
                cb.onError(
                        0,
                        java.util.concurrent.TimeUnit.MILLISECONDS,
                        new RuntimeException("HTTP " + statusCode));
                recordFailure(endpoint.id(), attempt);
                logDelivery(
                        endpoint.id(),
                        eventType,
                        jsonPayload,
                        statusCode,
                        responseBody,
                        attempt,
                        false);
            }
        } catch (Exception e) {
            LOG.warn("Webhook delivery to {} failed: {}", endpoint.url(), e.getMessage());
            cb.onError(0, java.util.concurrent.TimeUnit.MILLISECONDS, e);
            recordFailure(endpoint.id(), attempt);
            responseBody = e.getMessage();
            logDelivery(
                    endpoint.id(),
                    eventType,
                    jsonPayload,
                    statusCode,
                    responseBody,
                    attempt,
                    false);
        }

        return false;
    }

    /**
     * Builds the {@code X-Cveil-Signature} header value. If the endpoint has a valid previous
     * secret (within the rotation grace period), both signatures are included so receivers can
     * verify against whichever secret they currently hold.
     */
    private String buildSignatureHeader(
            WebhookEndpoint endpoint, String jsonPayload, long timestamp) {
        String currentSig = WebhookSignatureUtil.sign(jsonPayload, endpoint.secret(), timestamp);
        StringBuilder header = new StringBuilder("sha256=").append(currentSig);

        if (endpoint.previousSecret() != null
                && endpoint.previousSecretExpiresAt() != null
                && Instant.now().isBefore(endpoint.previousSecretExpiresAt())) {
            String oldSig =
                    WebhookSignatureUtil.sign(jsonPayload, endpoint.previousSecret(), timestamp);
            header.append(",sha256=").append(oldSig);
        }

        return header.toString();
    }

    // -- Failure tracking & circuit breaker -----------------------------------

    private void recordFailure(UUID endpointId, int attempt) {
        try {
            int failures =
                    dsl.update(WEBHOOK_ENDPOINT)
                            .set(EP_CONSECUTIVE_FAILURES, EP_CONSECUTIVE_FAILURES.plus(1))
                            .where(EP_ID.eq(endpointId))
                            .returning(EP_CONSECUTIVE_FAILURES)
                            .fetchOne(EP_CONSECUTIVE_FAILURES);

            if (failures >= CB_FAILURE_THRESHOLD) {
                dsl.update(WEBHOOK_ENDPOINT)
                        .set(EP_ENABLED, false)
                        .set(EP_DISABLED_AT, Instant.now())
                        .set(
                                EP_DISABLED_REASON,
                                "Circuit breaker tripped after "
                                        + failures
                                        + " consecutive failures")
                        .where(EP_ID.eq(endpointId).and(EP_ENABLED.isTrue()))
                        .execute();
                LOG.warn(
                        "Endpoint {} auto-disabled after {} consecutive failures",
                        endpointId,
                        failures);
            }
        } catch (Exception e) {
            LOG.warn("Failed to record failure for endpoint {}: {}", endpointId, e.getMessage());
        }
    }

    private void resetConsecutiveFailures(UUID endpointId) {
        try {
            dsl.update(WEBHOOK_ENDPOINT)
                    .set(EP_CONSECUTIVE_FAILURES, 0)
                    .where(EP_ID.eq(endpointId))
                    .execute();
        } catch (Exception e) {
            LOG.warn(
                    "Failed to reset failure count for endpoint {}: {}",
                    endpointId,
                    e.getMessage());
        }
    }

    /**
     * Re-enables an endpoint that was auto-disabled by the circuit breaker and resets its failure
     * counter. Used by the test-ping endpoint.
     */
    public void resetCircuitBreaker(UUID endpointId) {
        dsl.update(WEBHOOK_ENDPOINT)
                .set(EP_ENABLED, true)
                .set(EP_CONSECUTIVE_FAILURES, 0)
                .set(EP_DISABLED_AT, (Instant) null)
                .set(EP_DISABLED_REASON, (String) null)
                .where(EP_ID.eq(endpointId))
                .execute();

        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(endpointId.toString());
        cb.reset();
    }

    // -- Delivery logging with retry scheduling -------------------------------

    private void logDelivery(
            UUID endpointId,
            String eventType,
            String jsonPayload,
            int statusCode,
            String responseBody,
            int attempt,
            boolean success) {
        try {
            Instant now = Instant.now();
            Instant nextRetry = null;

            if (!success && attempt <= MAX_RETRIES) {
                int backoffIndex = Math.min(attempt - 1, RETRY_BACKOFF_SECONDS.length - 1);
                nextRetry = now.plusSeconds(RETRY_BACKOFF_SECONDS[backoffIndex]);
            }

            dsl.insertInto(WEBHOOK_DELIVERY_LOG)
                    .set(DL_ID, UUID.randomUUID())
                    .set(DL_ENDPOINT_ID, endpointId)
                    .set(DL_EVENT_TYPE, eventType)
                    .set(DL_PAYLOAD, JSONB.valueOf(jsonPayload))
                    .set(DL_STATUS_CODE, statusCode > 0 ? statusCode : null)
                    .set(DL_RESPONSE_BODY, responseBody)
                    .set(DL_ATTEMPT, attempt)
                    .set(DL_DELIVERED_AT, success ? now : null)
                    .set(DL_NEXT_RETRY_AT, nextRetry)
                    .execute();
        } catch (Exception e) {
            LOG.warn(
                    "Failed to log webhook delivery for endpoint {}: {}",
                    endpointId,
                    e.getMessage());
        }
    }

    // -- Async retry support (called by WebhookRetryTask) ---------------------

    /**
     * Fetches and retries all webhook deliveries whose {@code next_retry_at} has passed. Each entry
     * is re-delivered, and the result determines whether it is marked delivered or scheduled for
     * another retry.
     *
     * @return the number of entries processed
     */
    public int retryPendingDeliveries() {
        var pending =
                dsl.select(DL_ID, DL_ENDPOINT_ID, DL_EVENT_TYPE, DL_PAYLOAD, DL_ATTEMPT)
                        .from(WEBHOOK_DELIVERY_LOG)
                        .where(DL_NEXT_RETRY_AT.le(Instant.now()).and(DL_DELIVERED_AT.isNull()))
                        .orderBy(DL_NEXT_RETRY_AT.asc())
                        .limit(100)
                        .fetch();

        int processed = 0;
        for (var row : pending) {
            UUID logId = row.get(DL_ID);
            UUID endpointId = row.get(DL_ENDPOINT_ID);
            String eventType = row.get(DL_EVENT_TYPE);
            String payload = row.get(DL_PAYLOAD).data();
            int prevAttempt = row.get(DL_ATTEMPT);

            // Clear the retry marker so we don't double-process.
            dsl.update(WEBHOOK_DELIVERY_LOG)
                    .set(DL_NEXT_RETRY_AT, (Instant) null)
                    .where(DL_ID.eq(logId))
                    .execute();

            // Resolve the endpoint (may have been deleted or disabled).
            WebhookEndpoint ep = fetchEndpointById(endpointId);
            if (ep == null) {
                LOG.debug(
                        "Endpoint {} no longer exists — dropping retry for log {}",
                        endpointId,
                        logId);
                processed++;
                continue;
            }

            deliverAttempt(ep, eventType, payload, prevAttempt + 1);
            processed++;
        }
        return processed;
    }

    private WebhookEndpoint fetchEndpointById(UUID endpointId) {
        return dsl.select(
                        EP_ID,
                        EP_TENANT_ID,
                        EP_URL,
                        EP_SECRET,
                        EP_EVENTS,
                        EP_ENABLED,
                        EP_DESCRIPTION,
                        EP_CONSECUTIVE_FAILURES,
                        EP_DISABLED_AT,
                        EP_DISABLED_REASON,
                        EP_PREVIOUS_SECRET,
                        EP_PREVIOUS_SECRET_EXPIRES_AT)
                .from(WEBHOOK_ENDPOINT)
                .where(EP_ID.eq(endpointId))
                .fetchOptional()
                .map(this::mapEndpoint)
                .orElse(null);
    }

    // -- CRUD helpers for WebhookManagementResource ---------------------------

    public WebhookEndpoint createEndpoint(
            String tenantId, String url, String secret, List<String> events, String description) {
        UUID id = UUID.randomUUID();
        String eventsCsv = String.join(",", events);
        dsl.insertInto(WEBHOOK_ENDPOINT)
                .set(EP_ID, id)
                .set(EP_TENANT_ID, tenantId)
                .set(EP_URL, url)
                .set(EP_SECRET, secret)
                .set(EP_EVENTS, eventsCsv)
                .set(EP_ENABLED, true)
                .set(EP_DESCRIPTION, description)
                .execute();
        return new WebhookEndpoint(
                id, tenantId, url, secret, events, true, description, Instant.now(), Instant.now());
    }

    public List<WebhookEndpoint> listEndpoints(String tenantId) {
        return dsl.select(
                        EP_ID,
                        EP_TENANT_ID,
                        EP_URL,
                        EP_SECRET,
                        EP_EVENTS,
                        EP_ENABLED,
                        EP_DESCRIPTION,
                        EP_CONSECUTIVE_FAILURES,
                        EP_DISABLED_AT,
                        EP_DISABLED_REASON,
                        EP_PREVIOUS_SECRET,
                        EP_PREVIOUS_SECRET_EXPIRES_AT)
                .from(WEBHOOK_ENDPOINT)
                .where(EP_TENANT_ID.eq(tenantId))
                .fetch(
                        r -> {
                            WebhookEndpoint ep = mapEndpoint(r);
                            // Mask secrets in list responses
                            return new WebhookEndpoint(
                                    ep.id(),
                                    ep.tenantId(),
                                    ep.url(),
                                    "***",
                                    ep.events(),
                                    ep.enabled(),
                                    ep.description(),
                                    ep.createdAt(),
                                    ep.updatedAt(),
                                    ep.consecutiveFailures(),
                                    ep.disabledAt(),
                                    ep.disabledReason(),
                                    null,
                                    null);
                        });
    }

    public boolean deleteEndpoint(String tenantId, UUID endpointId) {
        // delete delivery logs first (FK constraint)
        dsl.deleteFrom(WEBHOOK_DELIVERY_LOG).where(DL_ENDPOINT_ID.eq(endpointId)).execute();
        int deleted =
                dsl.deleteFrom(WEBHOOK_ENDPOINT)
                        .where(EP_ID.eq(endpointId).and(EP_TENANT_ID.eq(tenantId)))
                        .execute();
        return deleted > 0;
    }

    public boolean updateEndpoint(
            String tenantId,
            UUID endpointId,
            String url,
            List<String> events,
            Boolean enabled,
            String description) {
        var update = dsl.update(WEBHOOK_ENDPOINT);
        var step = update.set(EP_TENANT_ID, tenantId); // no-op anchor for chaining
        if (url != null) step = step.set(EP_URL, url);
        if (events != null) step = step.set(EP_EVENTS, String.join(",", events));
        if (enabled != null) step = step.set(EP_ENABLED, enabled);
        if (description != null) step = step.set(EP_DESCRIPTION, description);
        int updated = step.where(EP_ID.eq(endpointId).and(EP_TENANT_ID.eq(tenantId))).execute();
        return updated > 0;
    }

    public List<Object> getDeliveryLog(UUID endpointId, int limit) {
        return dsl
                .select()
                .from(WEBHOOK_DELIVERY_LOG)
                .where(DL_ENDPOINT_ID.eq(endpointId))
                .orderBy(DSL.field(DSL.name("created_at")).desc())
                .limit(limit)
                .fetchMaps()
                .stream()
                .map(m -> (Object) m)
                .toList();
    }

    /**
     * Rotates the signing secret for an endpoint. The current secret becomes the {@code
     * previous_secret} and remains valid for {@value #SECRET_ROTATION_GRACE} so in-flight
     * deliveries signed with the old key can still be verified by receivers.
     */
    public boolean rotateEndpointSecret(UUID endpointId, String tenantId, String newSecret) {
        // Read current secret before overwriting.
        String currentSecret =
                dsl.select(EP_SECRET)
                        .from(WEBHOOK_ENDPOINT)
                        .where(EP_ID.eq(endpointId).and(EP_TENANT_ID.eq(tenantId)))
                        .fetchOne(EP_SECRET);

        if (currentSecret == null) {
            return false;
        }

        int updated =
                dsl.update(WEBHOOK_ENDPOINT)
                        .set(EP_SECRET, newSecret)
                        .set(EP_PREVIOUS_SECRET, currentSecret)
                        .set(
                                EP_PREVIOUS_SECRET_EXPIRES_AT,
                                Instant.now().plus(SECRET_ROTATION_GRACE))
                        .where(EP_ID.eq(endpointId).and(EP_TENANT_ID.eq(tenantId)))
                        .execute();
        return updated > 0;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private WebhookEndpoint mapEndpoint(org.jooq.Record r) {
        return new WebhookEndpoint(
                r.get(EP_ID),
                r.get(EP_TENANT_ID),
                r.get(EP_URL),
                r.get(EP_SECRET),
                parseEvents(r.get(EP_EVENTS)),
                Boolean.TRUE.equals(r.get(EP_ENABLED)),
                r.get(EP_DESCRIPTION),
                null,
                null,
                r.get(EP_CONSECUTIVE_FAILURES) != null ? r.get(EP_CONSECUTIVE_FAILURES) : 0,
                r.get(EP_DISABLED_AT),
                r.get(EP_DISABLED_REASON),
                r.get(EP_PREVIOUS_SECRET),
                r.get(EP_PREVIOUS_SECRET_EXPIRES_AT));
    }

    private static List<String> parseEvents(String csv) {
        if (csv == null || csv.isBlank()) return Collections.emptyList();
        return Arrays.asList(csv.split(","));
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}
