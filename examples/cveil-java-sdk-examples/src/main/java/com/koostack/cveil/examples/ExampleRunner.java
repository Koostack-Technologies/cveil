package com.koostack.cveil.examples;

import com.koostack.cveil.sdk.CveilClient;

import static com.koostack.cveil.examples.ExampleConfig.*;

/**
 * Runs all cveil SDK examples in sequence against the configured environment.
 *
 * <p>Each example sets up its own resources, executes, and cleans up.
 * The runner shares a single {@link CveilClient} instance across all examples
 * for efficiency.</p>
 *
 * <p>Usage:</p>
 * <pre>
 *   export CVEIL_API_URL=https://consentveil.koostack.com
 *   export CVEIL_API_TOKEN=cveil_...
 *   export CVEIL_JURISDICTION=EU
 *   ./gradlew :examples:cveil-java-sdk-examples:runAll
 * </pre>
 */
public class ExampleRunner {

    public static void main(String[] args) throws Exception {
        printHeader("cveil Java SDK Examples");
        System.out.printf("  API URL:      %s%n", API_URL);
        System.out.printf("  Jurisdiction: %s%n", JURISDICTION);
        System.out.println();

        int passed = 0;
        int failed = 0;

        try (var client = createClient()) {
            String[] examples = {
                    "01-CatalogueSetup",
                    "02-GovernanceSetup",
                    "03-SubjectManagement",
                    "04-ConsentLifecycle",
                    "05-DataMasking",
                    "06-BatchOperations",
                    "07-DsarCompliance"
            };

            Runnable[] runners = {
                    () -> wrap(() -> CatalogueSetup.run(client)),
                    () -> wrap(() -> GovernanceSetup.run(client)),
                    () -> wrap(() -> SubjectManagement.run(client)),
                    () -> wrap(() -> ConsentLifecycle.run(client)),
                    () -> wrap(() -> DataMasking.run(client)),
                    () -> wrap(() -> BatchOperations.run(client)),
                    () -> wrap(() -> DsarCompliance.run(client))
            };

            for (int i = 0; i < examples.length; i++) {
                try {
                    runners[i].run();
                    passed++;
                } catch (Exception e) {
                    failed++;
                    System.err.printf("  ✗ FAILED: %s — %s%n", examples[i], e.getMessage());
                }
            }
        }

        printHeader("Results");
        System.out.printf("  Passed: %d%n", passed);
        System.out.printf("  Failed: %d%n", failed);

        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void wrap(ThrowingRunnable action) {
        try {
            action.run();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @FunctionalInterface
    interface ThrowingRunnable { void run() throws Exception; }
}
