package com.koostack.cveil.examples;

import com.koostack.cveil.sdk.CveilClient;
import com.koostack.cveil.sdk.model.*;

import java.util.List;

import static com.koostack.cveil.examples.ExampleConfig.*;

/**
 * Example 03 — Subject Management
 *
 * Demonstrates the full lifecycle of data subjects: registration,
 * listing, retrieval, update, and removal.
 */
public class SubjectManagement {

    public static void main(String[] args) throws Exception {
        try (var client = createClient()) {
            run(client);
        }
    }

    public static void run(CveilClient client) throws Exception {
        printHeader("03 — Subject Management");

        try {
            registerSubjects(client);
            listSubjects(client);
            getSubject(client);
            updateSubject(client);
        } finally {
            cleanup(client);
        }
    }

    // [START register_subjects]
    static void registerSubjects(CveilClient client) throws Exception {
        printStep("Registering subjects...");

        client.registerSubject(new RegisterSubjectRequest(
                "alice-example", "Alice — test subject for examples"));
        printResult("Subject", "alice-example");

        client.registerSubject(new RegisterSubjectRequest(
                "bob-example", "Bob — test subject for examples"));
        printResult("Subject", "bob-example");

        // Brief pause for eventual consistency before querying
        Thread.sleep(500);
    }
    // [END register_subjects]

    // [START list_subjects]
    static void listSubjects(CveilClient client) throws Exception {
        printStep("Listing all subjects...");

        List<SubjectView> subjects = client.listSubjects();
        printResult("Total subjects", subjects.size());
        for (SubjectView subject : subjects) {
            printResult(subject.subjectName(),
                    "created=" + subject.createdAt());
        }
    }
    // [END list_subjects]

    // [START get_subject]
    static void getSubject(CveilClient client) throws Exception {
        printStep("Getting subject details...");

        SubjectView alice = client.getSubject("alice-example");
        printResult("Name", alice.subjectName());
        printResult("Description", alice.description());
        printResult("Created by", alice.createdBy());
    }
    // [END get_subject]

    // [START update_subject]
    static void updateSubject(CveilClient client) throws Exception {
        printStep("Updating subject description...");

        client.updateSubject("alice-example",
                new UpdateSubjectRequest("Alice — updated description"));

        SubjectView updated = client.getSubject("alice-example");
        printResult("Updated description", updated.description());
    }
    // [END update_subject]

    static void cleanup(CveilClient client) {
        printStep("Cleaning up subjects...");
        try {
            safeRun(() -> client.removeSubject("alice-example"));
            safeRun(() -> client.removeSubject("bob-example"));
            printCleanup("subjects removed");
        } catch (Exception e) {
            printCleanup("partial cleanup: " + e.getMessage());
        }
    }

    private static void safeRun(ThrowingRunnable action) {
        try { action.run(); } catch (Exception ignored) {}
    }

    @FunctionalInterface
    interface ThrowingRunnable { void run() throws Exception; }
}
