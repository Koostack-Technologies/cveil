package com.koostack.cveil.sdk;

import com.koostack.cveil.sdk.model.ApiErrorBody;

/** Exception thrown when an API request fails. */
public class CveilApiError extends RuntimeException {

    private final int status;
    private final boolean retryable;
    private final String requestId;
    private final Integer retryAfter;
    private final String errorCode;

    public CveilApiError(int status, ApiErrorBody body, String fallbackMessage) {
        super(
                body != null && body.message() != null
                        ? body.message()
                        : fallbackMessage != null ? fallbackMessage : "HTTP " + status);
        this.status = status;
        this.retryable = body != null ? body.retryable() : status >= 500;
        this.requestId = body != null ? body.requestId() : null;
        this.retryAfter = body != null ? body.retryAfter() : null;
        this.errorCode = body != null && body.error() != null ? body.error() : "unknown";
    }

    public int status() {
        return status;
    }

    public boolean retryable() {
        return retryable;
    }

    public String requestId() {
        return requestId;
    }

    public Integer retryAfter() {
        return retryAfter;
    }

    public String errorCode() {
        return errorCode;
    }
}
