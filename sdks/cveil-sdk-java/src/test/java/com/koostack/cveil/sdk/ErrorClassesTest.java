package com.koostack.cveil.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import com.koostack.cveil.sdk.model.ApiErrorBody;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests for {@link CveilApiError} and {@link CveilWasmError} exception classes. */
class ErrorClassesTest {

    @Nested
    @DisplayName("CveilApiError")
    class CveilApiErrorTests {

        @Test
        void constructsFromStructuredBody() {
            var body =
                    new ApiErrorBody("req-123", "validation_error", "field required", false, null);
            var error = new CveilApiError(400, body, null);

            assertThat(error.getMessage()).isEqualTo("field required");
            assertThat(error.status()).isEqualTo(400);
            assertThat(error.retryable()).isFalse();
            assertThat(error.requestId()).isEqualTo("req-123");
            assertThat(error.retryAfter()).isNull();
            assertThat(error.errorCode()).isEqualTo("validation_error");
        }

        @Test
        void constructsFromStructuredBodyWithRetryAfter() {
            var body = new ApiErrorBody(null, "service_unavailable", "Database is down", true, 5);
            var error = new CveilApiError(503, body, null);

            assertThat(error.getMessage()).isEqualTo("Database is down");
            assertThat(error.status()).isEqualTo(503);
            assertThat(error.retryable()).isTrue();
            assertThat(error.retryAfter()).isEqualTo(5);
            assertThat(error.requestId()).isNull();
        }

        @Test
        void usesFallbackMessageWhenBodyNull() {
            var error = new CveilApiError(404, null, "Not Found");

            assertThat(error.getMessage()).isEqualTo("Not Found");
            assertThat(error.status()).isEqualTo(404);
            assertThat(error.errorCode()).isEqualTo("unknown");
        }

        @Test
        void usesHttpStatusWhenNoBodyOrFallback() {
            var error = new CveilApiError(500, null, null);

            assertThat(error.getMessage()).isEqualTo("HTTP 500");
            assertThat(error.retryable()).isTrue(); // 500 >= 500
        }

        @Test
        void retryableDefaultsTrueFor5xx() {
            var error = new CveilApiError(502, null, null);
            assertThat(error.retryable()).isTrue();
        }

        @Test
        void retryableDefaultsFalseFor4xx() {
            var error = new CveilApiError(422, null, null);
            assertThat(error.retryable()).isFalse();
        }

        @Test
        void isARuntimeException() {
            var error = new CveilApiError(400, null, "test");
            assertThat(error).isInstanceOf(RuntimeException.class);
        }

        @Test
        void bodyMessageTakesPriorityOverFallback() {
            var body = new ApiErrorBody(null, "err", "body message", false, null);
            var error = new CveilApiError(400, body, "fallback message");

            assertThat(error.getMessage()).isEqualTo("body message");
        }

        @Test
        void nullBodyMessageFallsToFallback() {
            var body = new ApiErrorBody(null, "err", null, false, null);
            var error = new CveilApiError(400, body, "fallback message");

            assertThat(error.getMessage()).isEqualTo("fallback message");
        }
    }

    @Nested
    @DisplayName("CveilWasmError")
    class CveilWasmErrorTests {

        @Test
        void constructsWithMessage() {
            var error = new CveilWasmError("WASM failed");
            assertThat(error.getMessage()).isEqualTo("WASM failed");
            assertThat(error).isInstanceOf(RuntimeException.class);
        }

        @Test
        void constructsWithCause() {
            var cause = new RuntimeException("root cause");
            var error = new CveilWasmError("WASM failed", cause);
            assertThat(error.getCause()).isSameAs(cause);
        }
    }
}
