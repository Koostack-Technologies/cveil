package com.koostack.cveil.examples;

import com.koostack.cveil.sdk.CveilClient;
import com.koostack.cveil.sdk.CveilClientOptions;
import com.koostack.cveil.sdk.CveilConfig;

import java.io.IOException;
import java.util.concurrent.Callable;

/**
 * Shared configuration for all cveil SDK examples.
 * Reads connection settings from environment variables so examples
 * can be pointed at any cveil environment.
 *
 * <p>Required environment variables:</p>
 * <ul>
 *   <li>{@code CVEIL_API_URL}   — Base URL (default: {@code http://localhost:8080})</li>
 *   <li>{@code CVEIL_API_TOKEN} — Tenant API token (no default — required)</li>
 * </ul>
 *
 * <p>Optional:</p>
 * <ul>
 *   <li>{@code CVEIL_JURISDICTION} — Default jurisdiction (default: {@code EU})</li>
 * </ul>
 */
public final class ExampleConfig {

    private ExampleConfig() {}

    public static final String API_URL = env("CVEIL_API_URL", "http://localhost:8080");
    public static final String API_TOKEN = requireEnv("CVEIL_API_TOKEN");
    public static final String JURISDICTION = env("CVEIL_JURISDICTION", "EU");

    // [START create_client]
    /**
     * Creates a configured CveilClient from environment variables.
     */
    public static CveilClient createClient() throws IOException {
        return CveilClient.create(new CveilClientOptions(
                new CveilConfig(API_URL, API_TOKEN, JURISDICTION)
        ));
    }
    // [END create_client]

    /** Prints a section header for readable console output. */
    public static void printHeader(String title) {
        System.out.println();
        System.out.println("═".repeat(60));
        System.out.printf("  %s%n", title);
        System.out.println("═".repeat(60));
    }

    /** Prints a step within a section. */
    public static void printStep(String message) {
        System.out.printf("  → %s%n", message);
    }

    /** Prints a success result. */
    public static void printResult(String label, Object value) {
        System.out.printf("    ✓ %s: %s%n", label, value);
    }

    /** Prints a cleanup action. */
    public static void printCleanup(String message) {
        System.out.printf("  ✗ cleanup: %s%n", message);
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }

    private static String requireEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Required environment variable '" + key + "' is not set. "
                            + "Export it before running examples: export " + key + "=<value>");
        }
        return value;
    }

    /**
     * Retries an action that may fail due to eventual consistency
     * (e.g., governance projections not yet visible). Waits between
     * attempts with exponential backoff.
     */
    public static <T> T retry(String description, Callable<T> action) throws Exception {
        int maxAttempts = 10;
        long delayMs = 1000;
        Exception lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.call();
            } catch (Exception e) {
                lastError = e;
                if (attempt < maxAttempts) {
                    Thread.sleep(delayMs);
                    delayMs = Math.min(delayMs * 2, 5000);
                }
            }
        }
        throw lastError;
    }

    /** Void variant of retry. */
    public static void retryVoid(String description, ThrowingRunnable action) throws Exception {
        retry(description, () -> { action.run(); return null; });
    }

    @FunctionalInterface
    public interface ThrowingRunnable { void run() throws Exception; }
}
