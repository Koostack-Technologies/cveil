package com.koostack.cveil.sdk;

import java.nio.file.Path;

/**
 * Options for creating a {@link CveilClient}.
 *
 * @param config SDK configuration (required)
 * @param wasmPath Path to the WASM file (null → looks in standard locations)
 * @param autoConnect Whether to auto-connect SSE on initialization (default: true)
 * @param graphPageSize Graph page size for initial load (default: 100)
 */
public record CveilClientOptions(
        CveilConfig config, Path wasmPath, Boolean autoConnect, Integer graphPageSize) {

    public CveilClientOptions(CveilConfig config) {
        this(config, null, null, null);
    }

    public CveilClientOptions(CveilConfig config, Path wasmPath) {
        this(config, wasmPath, null, null);
    }

    /** Whether to auto-connect SSE, defaulting to true. */
    public boolean shouldAutoConnect() {
        return autoConnect == null || autoConnect;
    }

    /** Graph page size, defaulting to 100. */
    public int effectiveGraphPageSize() {
        return graphPageSize != null ? graphPageSize : 100;
    }
}
