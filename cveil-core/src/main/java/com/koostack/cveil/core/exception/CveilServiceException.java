package com.koostack.cveil.core.exception;

/**
 * Transient infrastructure failures — network errors, DB connection pool
 * exhausted, Redis timeout. Retryable.
 *
 * <p>Plugin implementations SHOULD throw this for any transient error that
 * may succeed on retry. Managed plugins MUST map HTTP 5xx and timeout
 * responses to this exception type.
 */
public class CveilServiceException extends CveilException {

    public CveilServiceException(String message) {
        super(message);
    }

    public CveilServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
