package com.koostack.cveil.sdk;

/** Exception thrown when a WASM operation fails. */
public class CveilWasmError extends RuntimeException {

    public CveilWasmError(String message) {
        super(message);
    }

    public CveilWasmError(String message, Throwable cause) {
        super(message, cause);
    }
}
