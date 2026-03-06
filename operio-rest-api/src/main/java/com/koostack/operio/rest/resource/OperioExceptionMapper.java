package com.koostack.operio.rest.resource;

import com.koostack.operio.core.exception.OperioAuthException;
import com.koostack.operio.core.exception.OperioConfigurationException;
import com.koostack.operio.core.exception.OperioException;
import com.koostack.operio.core.exception.OperioRateLimitException;
import com.koostack.operio.core.exception.OperioServiceException;
import com.koostack.operio.rest.dto.CommonDtos.ErrorResponse;
import com.koostack.operio.rest.filter.RequestTracingFilter;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * JAX-RS exception mapper that translates Operio core exceptions and common Java exceptions into
 * consistent JSON error responses with appropriate HTTP status codes.
 *
 * <p>Exception mapping table:
 *
 * <ul>
 *   <li>ConstraintViolationException -> 400 Bad Request
 *   <li>OperioAuthException -> 403 Forbidden
 *   <li>OperioRateLimitException -> 429 Too Many Requests
 *   <li>OperioConfigurationException -> 500 Internal Server Error
 *   <li>OperioServiceException -> 503 Service Unavailable
 *   <li>IllegalArgumentException -> 400 Bad Request
 *   <li>IllegalStateException -> 409 Conflict
 *   <li>UnsupportedOperationException -> 501 Not Implemented
 *   <li>All other exceptions -> 500 Internal Server Error
 * </ul>
 *
 * <p>All error responses follow the standard ErrorResponse shape: { "error": "...", "message":
 * "...", "retryable": bool, "retryAfter": long|null }
 */
@Provider
public class OperioExceptionMapper implements ExceptionMapper<Exception> {

    private static final Logger LOG = LoggerFactory.getLogger(OperioExceptionMapper.class);

    @Override
    public Response toResponse(Exception exception) {
        Response response = mapException(exception);
        return injectRequestId(response);
    }

    // -------------------------------------------------------------------
    // Request-ID injection
    // -------------------------------------------------------------------

    /**
     * Injects the current request's correlation ID into the error response body so clients can
     * reference it in support tickets and logs.
     *
     * <p>The request ID is read from the SLF4J {@link MDC}, which is populated by {@link
     * RequestTracingFilter} before any resource method or exception mapper runs. If the MDC value
     * is absent (should never happen in practice), the response is returned unmodified.
     *
     * @param response the error response built by one of the {@code mapXxx} methods
     * @return a new response with the {@code requestId} field populated in the {@link
     *     ErrorResponse} entity, or the original response if no request ID is available
     */
    private Response injectRequestId(Response response) {
        String requestId = MDC.get(RequestTracingFilter.MDC_REQUEST_ID);
        if (requestId != null && response.getEntity() instanceof ErrorResponse er) {
            return Response.fromResponse(response).entity(er.withRequestId(requestId)).build();
        }
        return response;
    }

    // -------------------------------------------------------------------
    // Exception dispatch
    // -------------------------------------------------------------------

    private Response mapException(Exception exception) {
        if (exception instanceof ConstraintViolationException e) {
            return mapConstraintViolation(e);
        }
        if (exception instanceof OperioAuthException e) {
            return mapAuthException(e);
        }
        if (exception instanceof OperioRateLimitException e) {
            return mapRateLimitException(e);
        }
        if (exception instanceof OperioServiceException e) {
            return mapServiceException(e);
        }
        if (exception instanceof OperioConfigurationException e) {
            return mapConfigurationException(e);
        }
        if (exception instanceof OperioException e) {
            // Catch-all for any future OperioException subtypes
            return mapGenericOperioException(e);
        }
        if (exception instanceof IllegalArgumentException e) {
            return mapIllegalArgument(e);
        }
        if (exception instanceof IllegalStateException e) {
            return mapIllegalState(e);
        }
        if (exception instanceof UnsupportedOperationException e) {
            return mapUnsupported(e);
        }
        return mapUnexpected(exception);
    }

    // -------------------------------------------------------------------
    // Bean Validation handler
    // -------------------------------------------------------------------

    private Response mapConstraintViolation(ConstraintViolationException e) {
        // Build a human-readable summary of all violations, one per field.
        // Format: "fieldName: message; fieldName2: message2"
        String details =
                e.getConstraintViolations().stream()
                        .map(OperioExceptionMapper::formatViolation)
                        .sorted()
                        .collect(Collectors.joining("; "));

        LOG.warn(
                "Validation error ({} violations): {}",
                e.getConstraintViolations().size(),
                details);

        return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON)
                .entity(ErrorResponse.of("validation_error", details))
                .build();
    }

    /**
     * Formats a single constraint violation into a "field: message" string.
     *
     * <p>The property path from Bean Validation includes the method name and parameter index (e.g.
     * {@code grant.request.subject}). This method extracts only the meaningful leaf portion of the
     * path to produce cleaner messages like {@code "subject: must not be blank"}.
     */
    private static String formatViolation(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath().toString();

        // Extract the leaf property name.  The full path for a JAX-RS
        // body parameter looks like "grant.request.scope.category" —
        // we want "scope.category" (the DTO-relative path).  For simple
        // fields like "grant.request.subject" we want just "subject".
        // Strategy: find the first segment that matches a DTO field by
        // skipping method name + parameter name (first two segments).
        String[] segments = path.split("\\.");
        String leaf;
        if (segments.length > 2) {
            // Skip method name and parameter name, keep the rest
            StringBuilder sb = new StringBuilder();
            for (int i = 2; i < segments.length; i++) {
                if (sb.length() > 0) sb.append('.');
                sb.append(segments[i]);
            }
            leaf = sb.toString();
        } else if (segments.length > 0) {
            // Fallback: use the last segment
            leaf = segments[segments.length - 1];
        } else {
            leaf = path;
        }

        return leaf + ": " + violation.getMessage();
    }

    // -------------------------------------------------------------------
    // Operio exception handlers
    // -------------------------------------------------------------------

    private Response mapAuthException(OperioAuthException e) {
        LOG.warn("Auth error: {}", e.getMessage());
        return Response.status(Response.Status.FORBIDDEN)
                .type(MediaType.APPLICATION_JSON)
                .entity(ErrorResponse.of("forbidden", e.getMessage()))
                .build();
    }

    private Response mapRateLimitException(OperioRateLimitException e) {
        LOG.warn("Rate limit exceeded: {}", e.getMessage());

        // Use the retryAfter hint from the exception if available, else default to 60s
        long retryAfterSeconds = e.retryAfter() != null ? e.retryAfter().toSeconds() : 60L;

        ErrorResponse body =
                ErrorResponse.retryable("rate_limit_exceeded", e.getMessage(), retryAfterSeconds);
        return Response.status(429)
                .type(MediaType.APPLICATION_JSON)
                .header("Retry-After", String.valueOf(retryAfterSeconds))
                .entity(body)
                .build();
    }

    private Response mapServiceException(OperioServiceException e) {
        LOG.error("Service error: {}", e.getMessage(), e);
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .type(MediaType.APPLICATION_JSON)
                .entity(ErrorResponse.retryable("service_unavailable", e.getMessage()))
                .build();
    }

    private Response mapConfigurationException(OperioConfigurationException e) {
        LOG.error("Configuration error: {}", e.getMessage(), e);
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .type(MediaType.APPLICATION_JSON)
                .entity(ErrorResponse.of("configuration_error", e.getMessage()))
                .build();
    }

    private Response mapGenericOperioException(OperioException e) {
        LOG.error("Operio error: {}", e.getMessage(), e);
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .type(MediaType.APPLICATION_JSON)
                .entity(ErrorResponse.of("internal_error", e.getMessage()))
                .build();
    }

    // -------------------------------------------------------------------
    // Standard Java exception handlers
    // -------------------------------------------------------------------

    private Response mapIllegalArgument(IllegalArgumentException e) {
        LOG.warn("Validation error: {}", e.getMessage());
        return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON)
                .entity(ErrorResponse.of("validation_error", e.getMessage()))
                .build();
    }

    private Response mapIllegalState(IllegalStateException e) {
        LOG.warn("Conflict: {}", e.getMessage());
        return Response.status(Response.Status.CONFLICT)
                .type(MediaType.APPLICATION_JSON)
                .entity(ErrorResponse.of("conflict", e.getMessage()))
                .build();
    }

    private Response mapUnsupported(UnsupportedOperationException e) {
        LOG.warn("Not implemented: {}", e.getMessage());
        return Response.status(501)
                .type(MediaType.APPLICATION_JSON)
                .entity(ErrorResponse.of("not_implemented", e.getMessage()))
                .build();
    }

    private Response mapUnexpected(Exception e) {
        LOG.error("Unexpected error: {}", e.getMessage(), e);
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .type(MediaType.APPLICATION_JSON)
                .entity(
                        ErrorResponse.of(
                                "internal_error",
                                "An unexpected error occurred. Please contact support if the issue"
                                        + " persists."))
                .build();
    }
}
