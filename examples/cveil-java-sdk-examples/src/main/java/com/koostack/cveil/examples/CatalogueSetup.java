package com.koostack.cveil.examples;

import com.koostack.cveil.sdk.CveilClient;
import com.koostack.cveil.sdk.model.*;

import java.util.List;

import static com.koostack.cveil.examples.ExampleConfig.*;

/**
 * Example 01 — Catalogue Setup
 *
 * Demonstrates how to define your data model in cveil by registering
 * entities (e.g. User, Order), fields (e.g. email, phone), and
 * categories (e.g. PII, Financial), then linking fields to categories.
 */
public class CatalogueSetup {

    public static void main(String[] args) throws Exception {
        try (var client = createClient()) {
            run(client);
        }
    }

    public static void run(CveilClient client) throws Exception {
        printHeader("01 — Catalogue Setup");

        try {
            registerEntities(client);
            registerFields(client);
            registerCategories(client);
            linkFieldsToCategories(client);
            verifyCatalogue(client);
        } finally {
            cleanup(client);
        }
    }

    // [START register_entities]
    static void registerEntities(CveilClient client) throws Exception {
        printStep("Registering entities...");

        client.registerEntity(new RegisterEntityRequest("user", "Application user profile"));
        printResult("Entity", "user");

        client.registerEntity(new RegisterEntityRequest("order", "Purchase order record"));
        printResult("Entity", "order");
    }
    // [END register_entities]

    // [START register_fields]
    static void registerFields(CveilClient client) throws Exception {
        printStep("Registering fields...");

        client.registerField(new RegisterFieldRequest("email", "user", "Email address", null));
        client.registerField(new RegisterFieldRequest("phone", "user", "Phone number", null));
        client.registerField(new RegisterFieldRequest("name", "user", "Full name", null));
        client.registerField(new RegisterFieldRequest("address", "user", "Mailing address", null));
        client.registerField(new RegisterFieldRequest("total", "order", "Order total amount", null));
        client.registerField(new RegisterFieldRequest("items", "order", "Purchased items", null));
        printResult("Fields registered", 6);
    }
    // [END register_fields]

    // [START register_categories]
    static void registerCategories(CveilClient client) throws Exception {
        printStep("Registering categories...");

        client.registerCategory(new RegisterCategoryRequest("PII", "Personally Identifiable Information"));
        printResult("Category", "PII");

        client.registerCategory(new RegisterCategoryRequest("Financial", "Financial data"));
        printResult("Category", "Financial");
    }
    // [END register_categories]

    // [START link_fields_to_categories]
    static void linkFieldsToCategories(CveilClient client) throws Exception {
        printStep("Linking fields to categories...");

        client.assignFieldToCategory(new AssignFieldCategoryRequest("email", "user", "PII", null));
        client.assignFieldToCategory(new AssignFieldCategoryRequest("phone", "user", "PII", null));
        client.assignFieldToCategory(new AssignFieldCategoryRequest("name", "user", "PII", null));
        client.assignFieldToCategory(new AssignFieldCategoryRequest("address", "user", "PII", null));
        client.assignFieldToCategory(new AssignFieldCategoryRequest("total", "order", "Financial", null));
        printResult("Links created", 5);
    }
    // [END link_fields_to_categories]

    // [START verify_catalogue]
    static void verifyCatalogue(CveilClient client) throws Exception {
        printStep("Verifying catalogue...");

        List<EntityView> entities = client.listEntities();
        printResult("Entities found", entities.size());

        List<FieldView> fields = client.listFields();
        printResult("Fields found", fields.size());

        List<CategoryView> categories = client.listCategories();
        printResult("Categories found", categories.size());

        for (FieldView field : fields) {
            if (!field.categories().isEmpty()) {
                printResult(field.entity().get("entityName") + "." + field.fieldName(),
                        "categories=" + field.categories().stream()
                                .map(c -> c.get("categoryName"))
                                .toList());
            }
        }
    }
    // [END verify_catalogue]

    static void cleanup(CveilClient client) {
        printStep("Cleaning up catalogue resources...");
        try {
            // Unlink fields from categories first
            safeRun(() -> client.unassignFieldFromCategory("user", "email", "PII"));
            safeRun(() -> client.unassignFieldFromCategory("user", "phone", "PII"));
            safeRun(() -> client.unassignFieldFromCategory("user", "name", "PII"));
            safeRun(() -> client.unassignFieldFromCategory("user", "address", "PII"));
            safeRun(() -> client.unassignFieldFromCategory("order", "total", "Financial"));

            // Remove fields
            safeRun(() -> client.removeField("user", "email"));
            safeRun(() -> client.removeField("user", "phone"));
            safeRun(() -> client.removeField("user", "name"));
            safeRun(() -> client.removeField("user", "address"));
            safeRun(() -> client.removeField("order", "total"));
            safeRun(() -> client.removeField("order", "items"));

            // Remove categories
            safeRun(() -> client.removeCategory("PII"));
            safeRun(() -> client.removeCategory("Financial"));

            // Remove entities
            safeRun(() -> client.removeEntity("user"));
            safeRun(() -> client.removeEntity("order"));

            printCleanup("catalogue resources removed");
        } catch (Exception e) {
            printCleanup("partial cleanup — some resources may remain: " + e.getMessage());
        }
    }

    private static void safeRun(ThrowingRunnable action) {
        try { action.run(); } catch (Exception ignored) {}
    }

    @FunctionalInterface
    interface ThrowingRunnable { void run() throws Exception; }
}
