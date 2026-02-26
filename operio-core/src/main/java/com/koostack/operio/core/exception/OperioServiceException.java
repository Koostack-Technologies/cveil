package com.koostack.operio.core.exception;

/**
 * Transient infrastructure failures — network errors, DB connection pool
 * exhausted, Redis timeout. Retryable.
 *
 * <p>Plugin implementations SHOULD throw this for any transient error that
 * may succeed on retry. Managed plugins MUST map HTTP 5xx and timeout
 * responses to this exception type.
 */
public class OperioServiceException extends OperioException {

    public OperioServiceException(String message) {
        super(message);
    }

    public OperioServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
