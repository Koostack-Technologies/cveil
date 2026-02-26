package com.koostack.operio.rest;

import static org.junit.jupiter.api.Assertions.*;

import com.koostack.operio.core.exception.OperioAuthException;
import com.koostack.operio.core.exception.OperioConfigurationException;
import com.koostack.operio.core.exception.OperioException;
import com.koostack.operio.core.exception.OperioRateLimitException;
import com.koostack.operio.core.exception.OperioServiceException;
import com.koostack.operio.rest.dto.CommonDtos.ErrorResponse;
import com.koostack.operio.rest.filter.RequestTracingFilter;
import com.koostack.operio.rest.resource.OperioExceptionMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import jakarta.validation.metadata.ConstraintDescriptor;
import jakarta.ws.rs.core.Response;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * Unit tests for {@link OperioExceptionMapper} — the JAX-RS exception mapper
 * that translates Operio core exceptions and common Java exceptions into
 * consistent JSON error responses with appropriate HTTP status codes.
 *
 * <p>Covers the full exception mapping table:
 * <ul>
 *   <li>{@link OperioAuthException}          → 403 Forbidden</li>
 *   <li>{@link OperioRateLimitException}     → 429 Too Many Requests</li>
 *   <li>{@link OperioConfigurationException} → 500 Internal Server Error</li>
 *   <li>{@link OperioServiceException}       → 503 Service Unavailable</li>
 *   <li>{@link OperioException}              → 500 Internal Server Error (catch-all)</li>
 *   <li>{@link IllegalArgumentException}     → 400 Bad Request</li>
 *   <li>{@link UnsupportedOperationException} → 501 Not Implemented</li>
 *   <li>Generic {@link Exception}            → 500 Internal Server Error</li>
 * </ul>
 *
 * <p>Also verifies:
 * <ul>
 *   <li>Error response body shape (error code, message, retryable flag)</li>
 *   <li>Retry-After header on rate limit exceptions</li>
 *   <li>retryAfter field on retryable errors</li>
 *   <li>Content-Type is application/json on all error responses</li>
 * </ul>
 */
@DisplayName("OperioExceptionMapper")
class OperioExceptionMapperTest {

    private OperioExceptionMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new OperioExceptionMapper();
    }

    @AfterEach
    void tearDown() {
        // Ensure MDC is clean after every test to prevent cross-contamination
        MDC.clear();
    }

    // -----------------------------------------------------------------------
    // Helper: extract ErrorResponse from Response entity
    // -----------------------------------------------------------------------

    private ErrorResponse extractError(Response response) {
        Object entity = response.getEntity();
        assertNotNull(entity, "Response entity should not be null");
        assertInstanceOf(
            ErrorResponse.class,
            entity,
            "Expected ErrorResponse but got: " + entity.getClass().getName()
        );
        return (ErrorResponse) entity;
    }

    // -----------------------------------------------------------------------
    // OperioAuthException → 403 Forbidden
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("OperioAuthException → 403")
    class AuthException {

        @Test
        @DisplayName("maps to 403 Forbidden")
        void statusCode403() {
            OperioAuthException ex = new OperioAuthException(
                "Access denied: requires super-admin role"
            );
            try (Response response = mapper.toResponse(ex)) {
                assertEquals(403, response.getStatus());
            }
        }

        @Test
        @DisplayName("error code is 'forbidden'")
        void errorCodeForbidden() {
            OperioAuthException ex = new OperioAuthException("Access denied");
            try (Response response = mapper.toResponse(ex)) {
                ErrorResponse error = extractError(response);
                assertEquals("forbidden", error.error());
            }
        }

        @Test
        @DisplayName("message preserves exception message")
        void messagePreserved() {
            String msg =
                "Access denied: this endpoint requires the 'super-admin' role";
            OperioAuthException ex = new OperioAuthException(msg);
            try (Response response = mapper.toResponse(ex)) {
                ErrorResponse error = extractError(response);
                assertEquals(msg, error.message());
            }
        }

        @Test
        @DisplayName("retryable is false for auth errors")
        void notRetryable() {
            OperioAuthException ex = new OperioAuthException("Forbidden");
            try (Response response = mapper.toResponse(ex)) {
                ErrorResponse error = extractError(response);
                assertFalse(error.retryable());
            }
        }

        @Test
        @DisplayName("retryAfter is null for auth errors")
        void noRetryAfter() {
            OperioAuthException ex = new OperioAuthException("Forbidden");
            try (Response response = mapper.toResponse(ex)) {
                ErrorResponse error = extractError(response);
                assertNull(error.retryAfter());
            }
        }

        @Test
        @DisplayName("Content-Type is application/json")
        void contentTypeJson() {
            OperioAuthException ex = new OperioAuthException("Forbidden");
            try (Response response = mapper.toResponse(ex)) {
                String contentType = response.getMediaType().toString();
                assertTrue(
                    contentType.contains("application/json"),
                    "Expected application/json but got: " + contentType
                );
            }
        }
    }

    // -----------------------------------------------------------------------
    // OperioRateLimitException → 429 Too Many Requests
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("OperioRateLimitException → 429")
    class RateLimitException {

        @Test
        @DisplayName("maps to 429 Too Many Requests")
        void statusCode429() {
            OperioRateLimitException ex = new OperioRateLimitException(
                "Burst rate limit exceeded",
                Duration.ofSeconds(42)
            );
            try (Response response = mapper.toResponse(ex)) {
                assertEquals(429, response.getStatus());
            }
        }

        @Test
        @DisplayName("error code is 'rate_limit_exceeded'")
        void errorCodeRateLimit() {
            OperioRateLimitException ex = new OperioRateLimitException(
                "Rate limit exceeded",
                Duration.ofSeconds(30)
            );
            try (Response response = mapper.toResponse(ex)) {
                ErrorResponse error = extractError(response);
                assertEquals("rate_limit_exceeded", error.error());
            }
        }

        @Test
        @DisplayName("message preserves exception message")
        void messagePreserved() {
            String msg = "Monthly evaluation quota exceeded (10000/month)";
            OperioRateLimitException ex = new OperioRateLimitException(
                msg,
                Duration.ofSeconds(60)
            );
            try (Response response = mapper.toResponse(ex)) {
                ErrorResponse error = extractError(response);
                assertEquals(msg, error.message());
            }
        }

        @Test
        @DisplayName("retryable is true for rate limit errors")
        void isRetryable() {
            OperioRateLimitException ex = new OperioRateLimitException(
                "Rate limit",
                Duration.ofSeconds(10)
            );
            try (Response response = mapper.toResponse(ex)) {
                ErrorResponse error = extractError(response);
                assertTrue(error.retryable());
            }
        }

        @Test
        @DisplayName("retryAfter matches the exception's retryAfter duration")
        void retryAfterFromException() {
            OperioRateLimitException ex = new OperioRateLimitException(
                "Rate limit",
                Duration.ofSeconds(42)
            );
            try (Response response = mapper.toResponse(ex)) {
                ErrorResponse error = extractError(response);
                assertNotNull(error.retryAfter());
                assertEquals(42L, error.retryAfter());
            }
        }

        @Test
        @DisplayName("Retry-After response header is set")
        void retryAfterHeader() {
            OperioRateLimitException ex = new OperioRateLimitException(
                "Rate limit",
                Duration.ofSeconds(42)
            );
            try (Response response = mapper.toResponse(ex)) {
                String retryAfterHeader = response.getHeaderString(
                    "Retry-After"
                );
                assertNotNull(
                    retryAfterHeader,
                    "Retry-After header should be set"
                );
                assertEquals("42", retryAfterHeader);
            }
        }

        @Test
        @DisplayName(
            "defaults to 60s retryAfter when exception retryAfter is null"
        )
        void defaultRetryAfterWhenNull() {
            OperioRateLimitException ex = new OperioRateLimitException(
                "Rate limit",
                null
            );
            try (Response response = mapper.toResponse(ex)) {
                ErrorResponse error = extractError(response);
                assertNotNull(error.retryAfter());
                assertEquals(60L, error.retryAfter());

                String retryAfterHeader = response.getHeaderString(
                    "Retry-After"
                );
                assertEquals("60", retryAfterHeader);
            }
        }

        @Test
        @DisplayName("Content-Type is application/json")
        void contentTypeJson() {
            OperioRateLimitException ex = new OperioRateLimitException(
                "Rate limit",
                Duration.ofSeconds(10)
            );
            try (Response response = mapper.toResponse(ex)) {
                String contentType = response.getMediaType().toString();
                assertTrue(
                    contentType.contains("application/json"),
                    "Expected application/json but got: " + contentType
                );
            }
        }
    }

    // -----------------------------------------------------------------------
    // OperioServiceException → 503 Service Unavailable
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("OperioServiceException → 503")
    class ServiceException {

        @Test
        @DisplayName("maps to 503 Service Unavailable")
        void statusCode503() {
            OperioServiceException ex = new OperioServiceException(
                "Redis connection lost"
            );
            try (Response response = mapper.toResponse(ex)) {
                assertEquals(503, response.getStatus());
            }
        }

        @Test
        @DisplayName("error code is 'service_unavailable'")
        void errorCodeServiceUnavailable() {
            OperioServiceException ex = new OperioServiceException(
                "Database unreachable"
            );
            try (Response response = mapper.toResponse(ex)) {
                ErrorResponse error = extractError(response);
                assertEquals("service_unavailable", error.error());
            }
        }

        @Test
        @DisplayName("message preserves exception message")
        void messagePreserved() {
            String msg = "Consent decision service is temporarily unavailable";
            OperioServiceException ex = new OperioServiceException(msg);
            try (Response response = mapper.toResponse(ex)) {
                ErrorResponse error = extractError(response);
                assertEquals(msg, error.message());
            }
        }

        @Test
        @DisplayName("retryable is true for service errors")
        void isRetryable() {
            OperioServiceException ex = new OperioServiceException(
                "Service down"
            );
            try (Response response = mapper.toResponse(ex)) {
                ErrorResponse error = extractError(response);
                assertTrue(error.retryable());
            }
        }

        @Test
        @DisplayName("retryAfter is null (no specific backoff)")
        void noSpecificRetryAfter() {
            OperioServiceException ex = new OperioServiceException(
                "Service down"
            );
            try (Response response = mapper.toResponse(ex)) {
                ErrorResponse error = extractError(response);
                assertNull(error.retryAfter());
            }
        }
    }

    // -----------------------------------------------------------------------
    // OperioConfigurationException → 500 Internal Server Error
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("OperioConfigurationException → 500")
    class ConfigurationException {

        @Test
        @DisplayName("maps to 500 Internal Server Error")
        void statusCode500() {
            OperioConfigurationException ex = new OperioConfigurationException(
                "Missing required configuration: operio.db.url"
            );
            try (Response response = mapper.toResponse(ex)) {
                assertEquals(500, response.getStatus());
            }
        }

        @Test
        @DisplayName("error code is 'configuration_error'")
        void errorCodeConfiguration() {
            OperioConfigurationException ex = new OperioConfigurationException(
                "Bad config"
            );
            try (Response response = mapper.toResponse(ex)) {
                ErrorResponse error = extractError(response);
                assertEquals("configuration_error", error.error());
            }
        }

        @Test
        @DisplayName("message preserves exception message")
        void messagePreserved() {
            String msg = "Plugin 'consent-decision-redis' failed health check";
            OperioConfigurationException ex = new OperioConfigurationException(
                msg
            );
            try (Response response = mapper.toResponse(ex)) {
                ErrorResponse error = extractError(response);
                assertEquals(msg, error.message());
            }
        }

        @Test
        @DisplayName("retryable is false for configuration errors")
        void notRetryable() {
            OperioConfigurationException ex = new OperioConfigurationException(
                "Bad config"
            );
            try (Response response = mapper.toResponse(ex)) {
                ErrorResponse error = extractError(response);
                assertFalse(error.retryable());
            }
        }

        @Test
        @DisplayName(
            "OperioAuthException takes precedence over OperioConfigurationException"
        )
        void authTakesPrecedenceOverConfig() {
            // OperioAuthException extends OperioConfigurationException, so the mapper
            // must check OperioAuthException first. Verify this polymorphism behavior.
            OperioAuthException ex = new OperioAuthException(
                "Forbidden - wrong role"
            );
            try (Response response = mapper.toResponse(ex)) {
                // Should be 403, NOT 500 (configuration)
                assertEquals(403, response.getStatus());
                ErrorResponse error = extractError(response);
                assertEquals("forbidden", error.error());
            }
        }
    }

    // -----------------------------------------------------------------------
    // OperioException (catch-all) → 500 Internal Server Error
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("OperioException (generic catch-all) → 500")
    class GenericOperioException {

        @Test
        @DisplayName("maps to 500 Internal Server Error")
        void statusCode500() {
            OperioException ex = new OperioException("Something went wrong") {};
            try (Response response = mapper.toResponse(ex)) {
                assertEquals(500, response.getStatus());
            }
        }

        @Test
        @DisplayName("error code is 'internal_error'")
        void errorCodeInternal() {
            OperioException ex = new OperioException(
                "Unexpected internal failure"
            ) {};
            try (Response response = mapper.toResponse(ex)) {
                ErrorResponse error = extractError(response);
                assertEquals("internal_error", error.error());
            }
        }

        @Test
        @DisplayName("message preserves exception message")
        void messagePreserved() {
            String msg = "Unexpected SPI contract violation";
            OperioException ex = new OperioException(msg) {};
            try (Response response = mapper.toResponse(ex)) {
                ErrorResponse error = extractError(response);
                assertEquals(msg, error.message());
            }
        }

        @Test
        @DisplayName("retryable is false for generic operio errors")
        void notRetryable() {
            OperioException ex = new OperioException("Error") {};
            try (Response response = mapper.toResponse(ex)) {
                ErrorResponse error = extractError(response);
                assertFalse(error.retryable());
            }
        }
    }

    // -----------------------------------------------------------------------
    // IllegalArgumentException → 400 Bad Request
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("IllegalArgumentException → 400")
    class IllegalArgument {

        @Test
        @DisplayName("maps to 400 Bad Request")
        void statusCode400() {
            IllegalArgumentException ex = new IllegalArgumentException(
                "Query parameter 'tenantId' is required"
            );
            try (Response response = mapper.toResponse(ex)) {
                assertEquals(400, response.getStatus());
            }
        }

        @Test
        @DisplayName("error code is 'validation_error'")
        void errorCodeValidation() {
            IllegalArgumentException ex = new IllegalArgumentException(
                "Invalid input"
            );
            try (Response response = mapper.toResponse(ex)) {
                ErrorResponse error = extractError(response);
                assertEquals("validation_error", error.error());
            }
        }

        @Test
        @DisplayName("message preserves exception message")
        void messagePreserved() {
            String msg = "Subject identifier must not be blank";
            IllegalArgumentException ex = new IllegalArgumentException(msg);
            try (Response response = mapper.toResponse(ex)) {
                ErrorResponse error = extractError(response);
                assertEquals(msg, error.message());
            }
        }

        @Test
        @DisplayName("retryable is false for validation errors")
        void notRetryable() {
            IllegalArgumentException ex = new IllegalArgumentException(
                "Bad input"
            );
            try (Response response = mapper.toResponse(ex)) {
                ErrorResponse error = extractError(response);
                assertFalse(error.retryable());
            }
        }

        @Test
        @DisplayName("retryAfter is null for validation errors")
        void noRetryAfter() {
            IllegalArgumentException ex = new IllegalArgumentException(
                "Bad input"
            );
            try (Response response = mapper.toResponse(ex)) {
                ErrorResponse error = extractError(response);
                assertNull(error.retryAfter());
            }
        }
    }

    // -----------------------------------------------------------------------
    // UnsupportedOperationException → 501 Not Implemented
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("UnsupportedOperationException → 501")
    class UnsupportedOperation {

        @Test
        @DisplayName("maps to 501 Not Implemented")
        void statusCode501() {
            UnsupportedOperationException ex =
                new UnsupportedOperationException(
                    "Consent history is not supported in embedded mode"
                );
            try (Response response = mapper.toResponse(ex)) {
                assertEquals(501, response.getStatus());
            }
        }

        @Test
        @DisplayName("error code is 'not_implemented'")
        void errorCodeNotImplemented() {
            UnsupportedOperationException ex =
                new UnsupportedOperationException("Not yet");
            try (Response response = mapper.toResponse(ex)) {
                ErrorResponse error = extractError(response);
                assertEquals("not_implemented", error.error());
            }
        }

        @Test
        @DisplayName("message preserves exception message")
        void messagePreserved() {
            String msg = "Batch consent is not supported by this plugin";
            UnsupportedOperationException ex =
                new UnsupportedOperationException(msg);
            try (Response response = mapper.toResponse(ex)) {
                ErrorResponse error = extractError(response);
                assertEquals(msg, error.message());
            }
        }

        @Test
        @DisplayName("retryable is false for unsupported operations")
        void notRetryable() {
            UnsupportedOperationException ex =
                new UnsupportedOperationException("N/A");
            try (Response response = mapper.toResponse(ex)) {
                ErrorResponse error = extractError(response);
                assertFalse(error.retryable());
            }
        }
    }

    // -----------------------------------------------------------------------
    // Generic Exception → 500 Internal Server Error
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Generic Exception → 500")
    class GenericException {

        @Test
        @DisplayName("maps to 500 Internal Server Error")
        void statusCode500() {
            Exception ex = new RuntimeException(
                "NullPointerException in some service"
            );
            try (Response response = mapper.toResponse(ex)) {
                assertEquals(500, response.getStatus());
            }
        }

        @Test
        @DisplayName("error code is 'internal_error'")
        void errorCodeInternal() {
            Exception ex = new NullPointerException("oops");
            try (Response response = mapper.toResponse(ex)) {
                ErrorResponse error = extractError(response);
                assertEquals("internal_error", error.error());
            }
        }

        @Test
        @DisplayName("message is generic (does not leak internal details)")
        void genericMessage() {
            Exception ex = new RuntimeException("Sensitive stack trace info");
            try (Response response = mapper.toResponse(ex)) {
                ErrorResponse error = extractError(response);
                // The mapper should return a generic message, not the exception message
                assertNotNull(error.message());
                assertFalse(
                    error.message().contains("Sensitive stack trace"),
                    "Internal error message should not leak exception details"
                );
                assertTrue(
                    error.message().contains("unexpected error"),
                    "Expected generic message but got: " + error.message()
                );
            }
        }

        @Test
        @DisplayName("retryable is false for unexpected errors")
        void notRetryable() {
            Exception ex = new RuntimeException("Oops");
            try (Response response = mapper.toResponse(ex)) {
                ErrorResponse error = extractError(response);
                assertFalse(error.retryable());
            }
        }

        @Test
        @DisplayName("Content-Type is application/json")
        void contentTypeJson() {
            Exception ex = new RuntimeException("Error");
            try (Response response = mapper.toResponse(ex)) {
                String contentType = response.getMediaType().toString();
                assertTrue(
                    contentType.contains("application/json"),
                    "Expected application/json but got: " + contentType
                );
            }
        }
    }

    // -----------------------------------------------------------------------
    // Subclass priority — most specific exception wins
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Subclass priority — most specific exception matched first")
    class SubclassPriority {

        @Test
        @DisplayName(
            "OperioAuthException (extends ConfigurationException) → 403 not 500"
        )
        void authBeforeConfiguration() {
            // OperioAuthException extends OperioConfigurationException
            // The mapper should match OperioAuthException first
            Exception ex = new OperioAuthException(
                "Auth error masquerading as config"
            );
            try (Response response = mapper.toResponse(ex)) {
                assertEquals(403, response.getStatus());
            }
        }

        @Test
        @DisplayName(
            "OperioRateLimitException (extends ServiceException) → 429 not 503"
        )
        void rateLimitBeforeService() {
            // OperioRateLimitException extends OperioServiceException
            // The mapper should match OperioRateLimitException first
            Exception ex = new OperioRateLimitException(
                "Rate limit hit",
                Duration.ofSeconds(10)
            );
            try (Response response = mapper.toResponse(ex)) {
                assertEquals(429, response.getStatus());
            }
        }

        @Test
        @DisplayName(
            "OperioServiceException matched before generic OperioException"
        )
        void serviceBeforeGenericOperio() {
            OperioServiceException ex = new OperioServiceException(
                "Service issue"
            );
            try (Response response = mapper.toResponse(ex)) {
                assertEquals(503, response.getStatus());
                ErrorResponse error = extractError(response);
                assertEquals("service_unavailable", error.error());
            }
        }

        @Test
        @DisplayName(
            "OperioConfigurationException matched before generic OperioException"
        )
        void configBeforeGenericOperio() {
            OperioConfigurationException ex = new OperioConfigurationException(
                "Config issue"
            );
            try (Response response = mapper.toResponse(ex)) {
                assertEquals(500, response.getStatus());
                ErrorResponse error = extractError(response);
                assertEquals("configuration_error", error.error());
            }
        }
    }

    // -----------------------------------------------------------------------
    // ErrorResponse shape
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("ErrorResponse shape verification")
    class ErrorResponseShape {

        @Test
        @DisplayName("non-retryable error has all expected fields")
        void nonRetryableShape() {
            IllegalArgumentException ex = new IllegalArgumentException(
                "Bad data"
            );
            try (Response response = mapper.toResponse(ex)) {
                ErrorResponse error = extractError(response);
                assertNotNull(error.error(), "error field should not be null");
                assertNotNull(
                    error.message(),
                    "message field should not be null"
                );
                assertFalse(error.retryable(), "retryable should be false");
                assertNull(
                    error.retryAfter(),
                    "retryAfter should be null for non-retryable"
                );
            }
        }

        @Test
        @DisplayName("retryable error with retryAfter has all expected fields")
        void retryableWithRetryAfterShape() {
            OperioRateLimitException ex = new OperioRateLimitException(
                "Rate limited",
                Duration.ofSeconds(30)
            );
            try (Response response = mapper.toResponse(ex)) {
                ErrorResponse error = extractError(response);
                assertNotNull(error.error());
                assertNotNull(error.message());
                assertTrue(error.retryable());
                assertNotNull(error.retryAfter());
                assertTrue(
                    error.retryAfter() > 0,
                    "retryAfter should be positive"
                );
            }
        }

        @Test
        @DisplayName("retryable error without specific retryAfter")
        void retryableWithoutRetryAfterShape() {
            OperioServiceException ex = new OperioServiceException(
                "Temporary failure"
            );
            try (Response response = mapper.toResponse(ex)) {
                ErrorResponse error = extractError(response);
                assertNotNull(error.error());
                assertNotNull(error.message());
                assertTrue(error.retryable());
                // Service errors are retryable but don't have a specific retryAfter
                assertNull(error.retryAfter());
            }
        }
    }

    // -----------------------------------------------------------------------
    // Null message handling
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Null exception message handling")
    class NullMessage {

        @Test
        @DisplayName(
            "IllegalArgumentException with null message does not crash"
        )
        void illegalArgumentNullMessage() {
            IllegalArgumentException ex = new IllegalArgumentException(
                (String) null
            );
            assertDoesNotThrow(() -> {
                try (Response response = mapper.toResponse(ex)) {
                    assertEquals(400, response.getStatus());
                }
            });
        }

        @Test
        @DisplayName("RuntimeException with null message does not crash")
        void runtimeNullMessage() {
            RuntimeException ex = new RuntimeException((String) null);
            assertDoesNotThrow(() -> {
                try (Response response = mapper.toResponse(ex)) {
                    assertEquals(500, response.getStatus());
                }
            });
        }

        @Test
        @DisplayName("OperioServiceException with null message does not crash")
        void serviceExceptionNullMessage() {
            OperioServiceException ex = new OperioServiceException(null);
            assertDoesNotThrow(() -> {
                try (Response response = mapper.toResponse(ex)) {
                    assertEquals(503, response.getStatus());
                }
            });
        }
    }

    // -----------------------------------------------------------------------
    // Exception with cause
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Exception with cause chain")
    class ExceptionWithCause {

        @Test
        @DisplayName("OperioServiceException with cause maps correctly")
        void serviceWithCause() {
            Exception rootCause = new java.sql.SQLException(
                "Connection refused"
            );
            OperioServiceException ex = new OperioServiceException(
                "DB unavailable",
                rootCause
            );
            try (Response response = mapper.toResponse(ex)) {
                assertEquals(503, response.getStatus());
                ErrorResponse error = extractError(response);
                assertEquals("service_unavailable", error.error());
                assertEquals("DB unavailable", error.message());
            }
        }

        @Test
        @DisplayName("OperioAuthException with cause maps correctly")
        void authWithCause() {
            Exception rootCause = new RuntimeException(
                "Token expired at 2024-01-01"
            );
            OperioAuthException ex = new OperioAuthException(
                "Token expired",
                rootCause
            );
            try (Response response = mapper.toResponse(ex)) {
                assertEquals(403, response.getStatus());
                ErrorResponse error = extractError(response);
                assertEquals("forbidden", error.error());
                assertEquals("Token expired", error.message());
            }
        }
    }

    // -----------------------------------------------------------------------
    // ConstraintViolationException → 400 Bad Request
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("ConstraintViolationException → 400 Bad Request")
    class ConstraintViolationTests {

        /**
         * Creates a minimal stub {@link ConstraintViolation} with a given
         * property path and message.  Only {@code getPropertyPath()} and
         * {@code getMessage()} are used by the mapper; everything else
         * returns {@code null}.
         */
        @SuppressWarnings("unchecked")
        private ConstraintViolation<Object> stubViolation(
            String propertyPath,
            String message
        ) {
            return new ConstraintViolation<>() {
                @Override
                public String getMessage() {
                    return message;
                }

                @Override
                public String getMessageTemplate() {
                    return null;
                }

                @Override
                public Object getRootBean() {
                    return null;
                }

                @Override
                public Class<Object> getRootBeanClass() {
                    return null;
                }

                @Override
                public Object getLeafBean() {
                    return null;
                }

                @Override
                public Object[] getExecutableParameters() {
                    return null;
                }

                @Override
                public Object getExecutableReturnValue() {
                    return null;
                }

                @Override
                public Path getPropertyPath() {
                    return new Path() {
                        @Override
                        public java.util.Iterator<Node> iterator() {
                            return java.util.Collections.emptyIterator();
                        }

                        @Override
                        public String toString() {
                            return propertyPath;
                        }
                    };
                }

                @Override
                public Object getInvalidValue() {
                    return null;
                }

                @Override
                public ConstraintDescriptor<?> getConstraintDescriptor() {
                    return null;
                }

                @Override
                public <U> U unwrap(Class<U> type) {
                    return null;
                }
            };
        }

        private ConstraintViolationException exceptionWith(
            ConstraintViolation<?>... violations
        ) {
            Set<ConstraintViolation<?>> set = new HashSet<>();
            for (var v : violations) set.add(v);
            return new ConstraintViolationException(set);
        }

        @Test
        @DisplayName("status code is 400")
        void statusCode400() {
            var ex = exceptionWith(
                stubViolation("grant.request.subject", "must not be blank")
            );
            try (Response response = mapper.toResponse(ex)) {
                assertEquals(400, response.getStatus());
            }
        }

        @Test
        @DisplayName("error code is 'validation_error'")
        void errorCodeValidation() {
            var ex = exceptionWith(
                stubViolation("grant.request.subject", "must not be blank")
            );
            try (Response response = mapper.toResponse(ex)) {
                ErrorResponse error = extractError(response);
                assertEquals("validation_error", error.error());
            }
        }

        @Test
        @DisplayName("not retryable")
        void notRetryable() {
            var ex = exceptionWith(
                stubViolation("grant.request.subject", "must not be blank")
            );
            try (Response response = mapper.toResponse(ex)) {
                ErrorResponse error = extractError(response);
                assertFalse(error.retryable());
                assertNull(error.retryAfter());
            }
        }

        @Test
        @DisplayName("message includes the violation detail")
        void messageContainsViolation() {
            var ex = exceptionWith(
                stubViolation("grant.request.subject", "must not be blank")
            );
            try (Response response = mapper.toResponse(ex)) {
                ErrorResponse error = extractError(response);
                assertTrue(
                    error.message().contains("subject"),
                    "Expected 'subject' in message but got: " + error.message()
                );
                assertTrue(
                    error.message().contains("must not be blank"),
                    "Expected 'must not be blank' in message but got: " +
                        error.message()
                );
            }
        }

        @Test
        @DisplayName("multiple violations are joined with semicolons")
        void multipleViolationsJoined() {
            var ex = exceptionWith(
                stubViolation("grant.request.subject", "must not be blank"),
                stubViolation("grant.request.scope", "must not be null")
            );
            try (Response response = mapper.toResponse(ex)) {
                ErrorResponse error = extractError(response);
                assertTrue(
                    error.message().contains(";"),
                    "Expected semicolon-separated violations but got: " +
                        error.message()
                );
                assertTrue(error.message().contains("subject"));
                assertTrue(error.message().contains("scope"));
            }
        }

        @Test
        @DisplayName("deeply nested path extracts leaf segments")
        void deeplyNestedPath() {
            // Path like "evaluate.request.scopes[0].category"
            var ex = exceptionWith(
                stubViolation(
                    "evaluate.request.scopes[0].category",
                    "must not be blank"
                )
            );
            try (Response response = mapper.toResponse(ex)) {
                ErrorResponse error = extractError(response);
                // Should extract "scopes[0].category" (everything after
                // method + param name segments)
                assertTrue(
                    error.message().contains("category"),
                    "Expected 'category' in message but got: " + error.message()
                );
            }
        }

        @Test
        @DisplayName("content type is application/json")
        void contentTypeJson() {
            var ex = exceptionWith(
                stubViolation("grant.request.subject", "must not be blank")
            );
            try (Response response = mapper.toResponse(ex)) {
                assertTrue(
                    response
                        .getMediaType()
                        .toString()
                        .contains("application/json")
                );
            }
        }

        @Test
        @DisplayName("takes precedence over generic Exception mapping")
        void precedenceOverGeneric() {
            // ConstraintViolationException extends RuntimeException;
            // verify it's caught by the specific handler, not the generic one
            var ex = exceptionWith(
                stubViolation("field.param.name", "invalid")
            );
            try (Response response = mapper.toResponse(ex)) {
                assertEquals(400, response.getStatus());
                ErrorResponse error = extractError(response);
                assertEquals("validation_error", error.error());
                // Generic handler would return 500 + "internal_error"
                assertNotEquals("internal_error", error.error());
            }
        }

        @Test
        @DisplayName("short property path uses last segment")
        void shortPropertyPath() {
            // Only one segment — fallback to using last segment
            var ex = exceptionWith(
                stubViolation("subject", "must not be blank")
            );
            try (Response response = mapper.toResponse(ex)) {
                ErrorResponse error = extractError(response);
                assertTrue(
                    error.message().contains("subject"),
                    "Expected 'subject' in message but got: " + error.message()
                );
            }
        }

        @Test
        @DisplayName("two-segment property path uses last segment")
        void twoSegmentPropertyPath() {
            var ex = exceptionWith(
                stubViolation("param.subject", "must not be blank")
            );
            try (Response response = mapper.toResponse(ex)) {
                ErrorResponse error = extractError(response);
                assertTrue(
                    error.message().contains("subject"),
                    "Expected 'subject' in message but got: " + error.message()
                );
            }
        }
    }

    // -----------------------------------------------------------------------
    // Request-ID injection (Task 2.1)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Request-ID injection from MDC")
    class RequestIdInjection {

        private static final String TEST_REQUEST_ID =
            "rqid-550e8400-e29b-41d4-a716-446655440000";

        @Test
        @DisplayName(
            "requestId is populated from MDC for OperioAuthException (403)"
        )
        void authExceptionIncludesRequestId() {
            MDC.put(RequestTracingFilter.MDC_REQUEST_ID, TEST_REQUEST_ID);
            OperioAuthException ex = new OperioAuthException("Forbidden");
            try (Response response = mapper.toResponse(ex)) {
                ErrorResponse error = extractError(response);
                assertEquals(TEST_REQUEST_ID, error.requestId());
            }
        }

        @Test
        @DisplayName(
            "requestId is populated from MDC for OperioRateLimitException (429)"
        )
        void rateLimitExceptionIncludesRequestId() {
            MDC.put(RequestTracingFilter.MDC_REQUEST_ID, TEST_REQUEST_ID);
            OperioRateLimitException ex = new OperioRateLimitException(
                "Rate limited",
                Duration.ofSeconds(30)
            );
            try (Response response = mapper.toResponse(ex)) {
                ErrorResponse error = extractError(response);
                assertEquals(TEST_REQUEST_ID, error.requestId());
            }
        }

        @Test
        @DisplayName(
            "requestId is populated from MDC for OperioServiceException (503)"
        )
        void serviceExceptionIncludesRequestId() {
            MDC.put(RequestTracingFilter.MDC_REQUEST_ID, TEST_REQUEST_ID);
            OperioServiceException ex = new OperioServiceException(
                "Service down"
            );
            try (Response response = mapper.toResponse(ex)) {
                ErrorResponse error = extractError(response);
                assertEquals(TEST_REQUEST_ID, error.requestId());
            }
        }

        @Test
        @DisplayName(
            "requestId is populated from MDC for OperioConfigurationException (500)"
        )
        void configExceptionIncludesRequestId() {
            MDC.put(RequestTracingFilter.MDC_REQUEST_ID, TEST_REQUEST_ID);
            OperioConfigurationException ex = new OperioConfigurationException(
                "Bad config"
            );
            try (Response response = mapper.toResponse(ex)) {
                ErrorResponse error = extractError(response);
                assertEquals(TEST_REQUEST_ID, error.requestId());
            }
        }

        @Test
        @DisplayName(
            "requestId is populated from MDC for generic OperioException (500)"
        )
        void genericOperioExceptionIncludesRequestId() {
            MDC.put(RequestTracingFilter.MDC_REQUEST_ID, TEST_REQUEST_ID);
            OperioException ex = new OperioException("Something failed") {};
            try (Response response = mapper.toResponse(ex)) {
                ErrorResponse error = extractError(response);
                assertEquals(TEST_REQUEST_ID, error.requestId());
            }
        }

        @Test
        @DisplayName(
            "requestId is populated from MDC for IllegalArgumentException (400)"
        )
        void illegalArgumentIncludesRequestId() {
            MDC.put(RequestTracingFilter.MDC_REQUEST_ID, TEST_REQUEST_ID);
            IllegalArgumentException ex = new IllegalArgumentException(
                "Bad arg"
            );
            try (Response response = mapper.toResponse(ex)) {
                ErrorResponse error = extractError(response);
                assertEquals(TEST_REQUEST_ID, error.requestId());
            }
        }

        @Test
        @DisplayName(
            "requestId is populated from MDC for UnsupportedOperationException (501)"
        )
        void unsupportedIncludesRequestId() {
            MDC.put(RequestTracingFilter.MDC_REQUEST_ID, TEST_REQUEST_ID);
            UnsupportedOperationException ex =
                new UnsupportedOperationException("Not yet");
            try (Response response = mapper.toResponse(ex)) {
                ErrorResponse error = extractError(response);
                assertEquals(TEST_REQUEST_ID, error.requestId());
            }
        }

        @Test
        @DisplayName(
            "requestId is populated from MDC for unexpected Exception (500)"
        )
        void unexpectedExceptionIncludesRequestId() {
            MDC.put(RequestTracingFilter.MDC_REQUEST_ID, TEST_REQUEST_ID);
            Exception ex = new RuntimeException("Boom");
            try (Response response = mapper.toResponse(ex)) {
                ErrorResponse error = extractError(response);
                assertEquals(TEST_REQUEST_ID, error.requestId());
            }
        }

        @Test
        @DisplayName(
            "requestId is populated from MDC for ConstraintViolationException (400)"
        )
        void constraintViolationIncludesRequestId() {
            MDC.put(RequestTracingFilter.MDC_REQUEST_ID, TEST_REQUEST_ID);

            // Self-contained violation stub — only getMessage() and
            // getPropertyPath().toString() are used by the mapper.
            @SuppressWarnings("unchecked")
            ConstraintViolation<Object> violation =
                new ConstraintViolation<>() {
                    @Override
                    public String getMessage() {
                        return "must not be blank";
                    }

                    @Override
                    public String getMessageTemplate() {
                        return null;
                    }

                    @Override
                    public Object getRootBean() {
                        return null;
                    }

                    @Override
                    public Class<Object> getRootBeanClass() {
                        return null;
                    }

                    @Override
                    public Object getLeafBean() {
                        return null;
                    }

                    @Override
                    public Object[] getExecutableParameters() {
                        return null;
                    }

                    @Override
                    public Object getExecutableReturnValue() {
                        return null;
                    }

                    @Override
                    public Path getPropertyPath() {
                        return new Path() {
                            @Override
                            public java.util.Iterator<Node> iterator() {
                                return java.util.Collections.emptyIterator();
                            }

                            @Override
                            public String toString() {
                                return "grant.request.subject";
                            }
                        };
                    }

                    @Override
                    public Object getInvalidValue() {
                        return null;
                    }

                    @Override
                    public jakarta.validation.metadata.ConstraintDescriptor<
                        ?
                    > getConstraintDescriptor() {
                        return null;
                    }

                    @Override
                    public <U> U unwrap(Class<U> type) {
                        return null;
                    }
                };

            Set<ConstraintViolation<?>> violations = Set.of(violation);
            ConstraintViolationException ex = new ConstraintViolationException(
                violations
            );
            try (Response response = mapper.toResponse(ex)) {
                ErrorResponse error = extractError(response);
                assertEquals(TEST_REQUEST_ID, error.requestId());
            }
        }

        @Test
        @DisplayName("requestId is null when MDC has no requestId key")
        void nullWhenMdcEmpty() {
            // MDC is clear (no requestId set)
            OperioAuthException ex = new OperioAuthException("Forbidden");
            try (Response response = mapper.toResponse(ex)) {
                ErrorResponse error = extractError(response);
                assertNull(
                    error.requestId(),
                    "requestId should be null when MDC has no value"
                );
            }
        }

        @Test
        @DisplayName(
            "requestId is null when MDC requestId was explicitly cleared"
        )
        void nullWhenMdcCleared() {
            MDC.put(RequestTracingFilter.MDC_REQUEST_ID, "some-id");
            MDC.remove(RequestTracingFilter.MDC_REQUEST_ID);

            IllegalArgumentException ex = new IllegalArgumentException("Bad");
            try (Response response = mapper.toResponse(ex)) {
                ErrorResponse error = extractError(response);
                assertNull(error.requestId());
            }
        }

        @Test
        @DisplayName("different request IDs produce different error responses")
        void differentRequestIds() {
            String id1 = "request-id-alpha";
            String id2 = "request-id-beta";

            MDC.put(RequestTracingFilter.MDC_REQUEST_ID, id1);
            OperioAuthException ex1 = new OperioAuthException("Denied");
            try (Response r1 = mapper.toResponse(ex1)) {
                ErrorResponse err1 = extractError(r1);
                assertEquals(id1, err1.requestId());
            }

            MDC.put(RequestTracingFilter.MDC_REQUEST_ID, id2);
            OperioAuthException ex2 = new OperioAuthException("Denied");
            try (Response r2 = mapper.toResponse(ex2)) {
                ErrorResponse err2 = extractError(r2);
                assertEquals(id2, err2.requestId());
            }
        }

        @Test
        @DisplayName("requestId does not affect other error response fields")
        void otherFieldsUnchanged() {
            MDC.put(RequestTracingFilter.MDC_REQUEST_ID, TEST_REQUEST_ID);
            OperioAuthException ex = new OperioAuthException("Access denied");
            try (Response response = mapper.toResponse(ex)) {
                ErrorResponse error = extractError(response);
                assertEquals(TEST_REQUEST_ID, error.requestId());
                assertEquals("forbidden", error.error());
                assertEquals("Access denied", error.message());
                assertFalse(error.retryable());
                assertNull(error.retryAfter());
                assertEquals(403, response.getStatus());
            }
        }

        @Test
        @DisplayName("retryable error preserves retryAfter alongside requestId")
        void retryablePreservesRetryAfter() {
            MDC.put(RequestTracingFilter.MDC_REQUEST_ID, TEST_REQUEST_ID);
            OperioRateLimitException ex = new OperioRateLimitException(
                "Too fast",
                Duration.ofSeconds(45)
            );
            try (Response response = mapper.toResponse(ex)) {
                ErrorResponse error = extractError(response);
                assertEquals(TEST_REQUEST_ID, error.requestId());
                assertEquals("rate_limit_exceeded", error.error());
                assertTrue(error.retryable());
                assertNotNull(error.retryAfter());
                assertEquals(45L, error.retryAfter());
                assertEquals(429, response.getStatus());
            }
        }

        @Test
        @DisplayName("client-provided UUID is preserved as requestId")
        void clientProvidedUuid() {
            String clientUuid = "c9bf9e57-1685-4c89-bafb-ff5af830be8a";
            MDC.put(RequestTracingFilter.MDC_REQUEST_ID, clientUuid);
            RuntimeException ex = new RuntimeException("error");
            try (Response response = mapper.toResponse(ex)) {
                ErrorResponse error = extractError(response);
                assertEquals(clientUuid, error.requestId());
            }
        }
    }
}
