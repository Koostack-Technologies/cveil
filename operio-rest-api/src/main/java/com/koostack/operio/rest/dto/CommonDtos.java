package com.koostack.operio.rest.dto;

import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Common DTOs shared across multiple resource endpoints.
 *
 * <p>Contains general-purpose response structures that are not specific to
 * any single domain (consent, admin, platform) but are used by health
 * checks, error responses, and other cross-cutting concerns.
 *
 * <p>All types are Java records — immutable, serializable by Jackson out
 * of the box, and cheap to allocate.
 */
public final class CommonDtos {

    private CommonDtos() {
        // Utility class — no instances
    }

    // ===================================================================
    // Health
    // ===================================================================

    /**
     * Response body for {@code GET /api/v1/health}.
     *
     * <p>Reports the overall readiness of the Operio REST API. When all
     * configured plugins are ready, {@code status} is {@code "UP"} and
     * {@code errors} is empty. When one or more plugins report failures,
     * {@code status} is {@code "DOWN"} and {@code errors} lists the
     * failing plugin names or diagnostic messages.
     *
     * @param status the overall health status ({@code "UP"} or
     *               {@code "DOWN"})
     * @param errors a list of error descriptions for failing components,
     *               empty when healthy
     */
    @Schema(
        description = "Health check response indicating the readiness of all Operio plugins"
    )
    public record HealthResponse(
        @Schema(
            description = "Overall health status",
            enumeration = { "UP", "DOWN" },
            example = "UP"
        )
        String status,
        @Schema(
            description = "Error descriptions for failing components (empty when healthy)",
            example = "[]"
        )
        List<String> errors
    ) {
        /** Status value indicating all components are healthy. */
        public static final String STATUS_UP = "UP";

        /** Status value indicating one or more components have failed. */
        public static final String STATUS_DOWN = "DOWN";

        /**
         * Creates a healthy response with no errors.
         *
         * @return a {@code HealthResponse} with status {@code "UP"} and
         *         an empty error list
         */
        public static HealthResponse up() {
            return new HealthResponse(STATUS_UP, List.of());
        }

        /**
         * Creates an unhealthy response with the given error messages.
         *
         * @param errors the list of error descriptions — must not be
         *               {@code null} or empty
         * @return a {@code HealthResponse} with status {@code "DOWN"}
         */
        public static HealthResponse down(List<String> errors) {
            return new HealthResponse(STATUS_DOWN, List.copyOf(errors));
        }

        /**
         * Creates an unhealthy response with a single error message.
         *
         * @param error the error description — must not be {@code null}
         * @return a {@code HealthResponse} with status {@code "DOWN"}
         */
        public static HealthResponse down(String error) {
            return new HealthResponse(STATUS_DOWN, List.of(error));
        }
    }

    // ===================================================================
    // Error
    // ===================================================================

    /**
     * Standard error response body returned by the
     * {@link com.koostack.operio.rest.resource.OperioExceptionMapper}
     * for all error conditions.
     *
     * <p>Follows a consistent JSON shape across all error responses:
     * <pre>{@code
     * {
     *   "requestId": "550e8400-e29b-41d4-a716-446655440000",
     *   "error": "rate_limit_exceeded",
     *   "message": "Burst rate limit exceeded (100 requests/min)",
     *   "retryable": true,
     *   "retryAfter": 42
     * }
     * }</pre>
     *
     * <p>The {@code requestId} field correlates the error with the
     * {@code X-Request-Id} header echoed in every HTTP response. It is
     * populated by the
     * {@link com.koostack.operio.rest.resource.OperioExceptionMapper}
     * from the SLF4J MDC and is {@code null} only when the request
     * tracing filter has not yet run (should never happen in practice).
     *
     * <p>The {@code retryAfter} field is only present (non-null) for
     * retryable errors where a specific backoff period is known (e.g.
     * rate limiting). For non-retryable errors it is {@code null} and
     * omitted from the JSON output if Jackson is configured with
     * {@code NON_NULL} inclusion.
     *
     * @param requestId  the request correlation ID from the
     *                   {@code X-Request-Id} header (or auto-generated
     *                   UUID), or {@code null} if unavailable
     * @param error      a machine-readable error code (e.g.
     *                   {@code "rate_limit_exceeded"},
     *                   {@code "unauthorized"},
     *                   {@code "validation_error"},
     *                   {@code "internal_error"})
     * @param message    a human-readable error description suitable for
     *                   developer-facing diagnostics (not end-user display)
     * @param retryable  {@code true} if the client should retry after a
     *                   backoff period, {@code false} if the error is
     *                   permanent (requires configuration or input change)
     * @param retryAfter the number of seconds the client should wait before
     *                   retrying, or {@code null} if not applicable
     */
    @Schema(
        description = "Standard error response returned for all error conditions"
    )
    public record ErrorResponse(
        @Schema(
            description = "Request correlation ID from the X-Request-Id header (or auto-generated UUID)",
            example = "550e8400-e29b-41d4-a716-446655440000",
            nullable = true
        )
        String requestId,
        @Schema(
            description = "Machine-readable error code",
            example = "validation_error",
            enumeration = {
                "validation_error",
                "forbidden",
                "rate_limit_exceeded",
                "service_unavailable",
                "configuration_error",
                "internal_error",
                "not_implemented",
            }
        )
        String error,
        @Schema(
            description = "Human-readable error description for developer diagnostics",
            example = "subject: must not be blank"
        )
        String message,
        @Schema(
            description = "Whether the client should retry after a backoff period",
            example = "false"
        )
        boolean retryable,
        @Schema(
            description = "Seconds the client should wait before retrying (null if not applicable)",
            example = "60",
            nullable = true
        )
        Long retryAfter
    ) {
        /**
         * Creates a non-retryable error response with no request ID.
         *
         * <p>The request ID should be injected later via
         * {@link #withRequestId(String)} in the exception mapper.
         *
         * @param error   the machine-readable error code
         * @param message the human-readable error description
         * @return an {@code ErrorResponse} with {@code retryable=false}
         *         and no {@code retryAfter}
         */
        public static ErrorResponse of(String error, String message) {
            return new ErrorResponse(null, error, message, false, null);
        }

        /**
         * Creates a retryable error response without a specific
         * {@code retryAfter} duration.
         *
         * @param error   the machine-readable error code
         * @param message the human-readable error description
         * @return an {@code ErrorResponse} with {@code retryable=true}
         *         and no {@code retryAfter}
         */
        public static ErrorResponse retryable(String error, String message) {
            return new ErrorResponse(null, error, message, true, null);
        }

        /**
         * Creates a retryable error response with a specific
         * {@code retryAfter} duration.
         *
         * @param error      the machine-readable error code
         * @param message    the human-readable error description
         * @param retryAfter the number of seconds to wait before retrying
         * @return an {@code ErrorResponse} with {@code retryable=true}
         *         and the given {@code retryAfter}
         */
        public static ErrorResponse retryable(
            String error,
            String message,
            long retryAfter
        ) {
            return new ErrorResponse(null, error, message, true, retryAfter);
        }

        /**
         * Returns a copy of this error response with the given request ID
         * injected.
         *
         * <p>This is the canonical way for the exception mapper to attach
         * the {@code X-Request-Id} correlation identifier to every error
         * response. The pattern keeps factory methods simple (no request ID
         * parameter) while ensuring the ID is always present in the final
         * JSON payload.
         *
         * @param requestId the request correlation ID, or {@code null}
         * @return a new {@code ErrorResponse} with the request ID set
         */
        public ErrorResponse withRequestId(String requestId) {
            return new ErrorResponse(
                requestId,
                this.error,
                this.message,
                this.retryable,
                this.retryAfter
            );
        }
    }

    // ===================================================================
    // Generic message
    // ===================================================================

    /**
     * Simple message response used for operations that don't return a
     * domain object — typically delete or link operations that succeed
     * with a confirmation message.
     *
     * <pre>{@code
     * { "message": "Tenant 'acme_corp' deleted successfully" }
     * }</pre>
     *
     * @param message a human-readable confirmation or status message
     */
    @Schema(
        description = "Simple confirmation message for operations that do not return a domain object"
    )
    public record MessageResponse(
        @Schema(
            description = "Human-readable confirmation or status message",
            example = "Consent granted"
        )
        String message
    ) {
        /**
         * Creates a message response.
         *
         * @param message the confirmation message
         * @return a {@code MessageResponse} with the given message
         */
        public static MessageResponse of(String message) {
            return new MessageResponse(message);
        }
    }
}
