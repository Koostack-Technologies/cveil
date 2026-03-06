package com.koostack.operio.rest.integration;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;

import com.koostack.operio.rest.config.OperioConfig;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

/**
 * Integration test verifying that Redis Pub/Sub cache invalidation messages
 * are published after catalogue, governance, and consent mutations.
 *
 * <p>Subscribes to {@code operio:consent-cache-invalidation:{tenantId}} and
 * verifies that correct {@code CacheInvalidationMessage} JSON payloads arrive
 * after each mutation type.
 */
@QuarkusTest
@QuarkusTestResource(OperioTestResourceManager.class)
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class CacheInvalidationIT {

    private static final String TENANT_ID = "cache_inv_it_tenant";
    private static final String BOOTSTRAP_KEY = "test-bootstrap-key-for-integration-tests";
    private static final String CHANNEL = "operio:consent-cache-invalidation:" + TENANT_ID;

    @Inject OperioConfig config;

    private static volatile String apiKey;
    private static volatile IntegrationTestSupport support;

    // Pub/Sub infrastructure
    private static volatile ConcurrentLinkedQueue<String> receivedMessages;
    private static volatile JedisPubSub pubSub;
    private static volatile Thread subscriberThread;
    private static volatile Jedis subscriberJedis;

    // SSE infrastructure
    private static volatile ConcurrentLinkedQueue<String> sseEvents;
    private static volatile CompletableFuture<?> sseFuture;
    private static volatile InputStream sseInputStream;

    // -------------------------------------------------------------------
    // Phase 1 — Setup + Pub/Sub Subscription
    // -------------------------------------------------------------------

    @Nested
    @Order(1)
    @DisplayName("Phase 1 — Setup")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class Setup {

        @Test @Order(1) @DisplayName("Create tenant")
        void createTenant() {
            Response r = given()
                .header("Authorization", "Bearer " + BOOTSTRAP_KEY)
                .contentType(ContentType.JSON)
                .body("""
                    {"tenantId": "%s", "displayName": "Cache Invalidation IT", "plan": "pro"}
                    """.formatted(TENANT_ID))
                .post("/api/v1/platform/tenants");
            assertThat(r.statusCode()).isIn(201, 200, 409);
        }

        @Test @Order(2) @DisplayName("Create API key")
        void createApiKey() {
            Response r = given()
                .header("Authorization", "Bearer " + BOOTSTRAP_KEY)
                .contentType(ContentType.JSON)
                .body("""
                    {"tenantId": "%s", "keyName": "cache-inv-key", "environment": "TEST",
                     "roles": ["admin:read", "admin:write", "consent:read", "consent:write"]}
                    """.formatted(TENANT_ID))
                .post("/api/v1/platform/api-keys");
            assertThat(r.statusCode()).isEqualTo(201);
            apiKey = r.jsonPath().getString("rawKey");
        }

        @Test @Order(3) @DisplayName("Init support")
        void initSupport() {
            support = new IntegrationTestSupport(config, TENANT_ID);
            support.awaitApiKeyUsable(apiKey, Duration.ofSeconds(30));
            support.awaitEventTableReady(Duration.ofSeconds(15));
            support.awaitTenantDiscoverable(TENANT_ID, Duration.ofSeconds(30));
        }

        @Test @Order(4) @DisplayName("Start Pub/Sub subscriber")
        void startSubscriber() throws Exception {
            receivedMessages = new ConcurrentLinkedQueue<>();
            var subscribed = new CountDownLatch(1);

            pubSub = new JedisPubSub() {
                @Override
                public void onSubscribe(String channel, int subscribedChannels) {
                    subscribed.countDown();
                }

                @Override
                public void onMessage(String channel, String message) {
                    receivedMessages.add(message);
                }
            };

            subscriberJedis = support.newJedisConnection();
            subscriberThread = new Thread(() -> {
                try {
                    subscriberJedis.subscribe(pubSub, CHANNEL);
                } catch (Exception e) {
                    // Expected when unsubscribed
                }
            });
            subscriberThread.setDaemon(true);
            subscriberThread.start();

            // Wait for subscription to be active
            assertThat(subscribed.await(5, TimeUnit.SECONDS))
                .as("Pub/Sub subscription should activate within 5s")
                .isTrue();
        }
    }

    // -------------------------------------------------------------------
    // Phase 2 — Catalogue Mutations → Invalidation Messages
    // -------------------------------------------------------------------

    @Nested
    @Order(2)
    @DisplayName("Phase 2 — Catalogue Invalidations")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class CatalogueInvalidations {

        private String auth() { return "Bearer " + apiKey; }

        @Test @Order(1) @DisplayName("Entity registration publishes invalidation")
        void entityRegistration() throws Exception {
            receivedMessages.clear();

            given().header("Authorization", auth()).contentType(ContentType.JSON)
                .body("""
                    {"entityName": "CiEntity", "description": "d"}
                    """)
                .post("/api/v1/catalogue/entities")
                .then().statusCode(anyOf(is(200), is(201)));

            assertInvalidationReceived("ENTITY_CHANGED", 5);
        }

        @Test @Order(2) @DisplayName("Field registration publishes invalidation")
        void fieldRegistration() throws Exception {
            receivedMessages.clear();

            given().header("Authorization", auth()).contentType(ContentType.JSON)
                .body("""
                    {"fieldName": "ci_field", "entityName": "CiEntity", "description": "d"}
                    """)
                .post("/api/v1/catalogue/fields")
                .then().statusCode(anyOf(is(200), is(201)));

            assertInvalidationReceived("FIELD_CHANGED", 5);
        }

        @Test @Order(3) @DisplayName("Category registration publishes invalidation")
        void categoryRegistration() throws Exception {
            receivedMessages.clear();

            given().header("Authorization", auth()).contentType(ContentType.JSON)
                .body("""
                    {"categoryName": "CiCategory", "description": "d"}
                    """)
                .post("/api/v1/catalogue/categories")
                .then().statusCode(anyOf(is(200), is(201)));

            assertInvalidationReceived("CATEGORY_CHANGED", 5);
        }
    }

    // -------------------------------------------------------------------
    // Phase 3 — Governance Mutations → Invalidation Messages
    // -------------------------------------------------------------------

    @Nested
    @Order(3)
    @DisplayName("Phase 3 — Governance Invalidations")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class GovernanceInvalidations {

        private String auth() { return "Bearer " + apiKey; }

        @Test @Order(1) @DisplayName("Purpose registration publishes invalidation")
        void purposeRegistration() throws Exception {
            receivedMessages.clear();

            given().header("Authorization", auth()).contentType(ContentType.JSON)
                .body("""
                    {"purposeName": "ci_purpose", "description": "d"}
                    """)
                .post("/api/v1/governance/purposes")
                .then().statusCode(anyOf(is(200), is(201)));

            assertInvalidationReceived("PURPOSE_CHANGED", 5);
        }

        @Test @Order(2) @DisplayName("Regulation registration publishes invalidation")
        void regulationRegistration() throws Exception {
            receivedMessages.clear();

            given().header("Authorization", auth()).contentType(ContentType.JSON)
                .body("""
                    {"regulationName": "CiRegulation", "description": "d"}
                    """)
                .post("/api/v1/governance/regulations")
                .then().statusCode(anyOf(is(200), is(201)));

            assertInvalidationReceived("REGULATION_CHANGED", 5);
        }
    }

    // -------------------------------------------------------------------
    // Phase 4 — Consent Mutations → Invalidation Messages
    // -------------------------------------------------------------------

    @Nested
    @Order(4)
    @DisplayName("Phase 4 — Consent Invalidations")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class ConsentInvalidations {

        private String auth() { return "Bearer " + apiKey; }

        @Test @Order(1) @DisplayName("Setup consent prerequisites")
        void setupPrereqs() {
            // Assign field to category
            given().header("Authorization", auth()).contentType(ContentType.JSON)
                .body("""
                    {"fieldName": "ci_field", "entityName": "CiEntity", "categoryName": "CiCategory"}
                    """)
                .post("/api/v1/catalogue/fields/categories")
                .then().statusCode(anyOf(is(200), is(201)));
            // Link purpose → regulation
            given().header("Authorization", auth()).contentType(ContentType.JSON)
                .body("""
                    {"purposeName": "ci_purpose", "regulationName": "CiRegulation",
                         "lawfulBasis": "consent", "onGranted": "allow", "onDenied": "deny"}
                    """)
                .post("/api/v1/governance/purposes/regulations")
                .then().statusCode(200);
            // Add policy
            given().header("Authorization", auth()).contentType(ContentType.JSON)
                .body("""
                    {"purposeName": "ci_purpose", "policyType": "field",
                         "fieldName": "ci_field", "entityName": "CiEntity", "categoryName": "CiCategory"}
                    """)
                .post("/api/v1/governance/purposes/policies")
                .then().statusCode(anyOf(is(200), is(201)));
        }

        @Test @Order(2) @DisplayName("Consent grant publishes GRANTED invalidation")
        void consentGrant() throws Exception {
            receivedMessages.clear();

            given().header("Authorization", auth()).contentType(ContentType.JSON)
                .body("""
                    {"subject": "ci-subject-1",
                     "scope": {"category": "CiCategory", "fieldName": "ci_field",
                               "entityName": "CiEntity", "purposeName": "ci_purpose"}}
                    """)
                .post("/api/v1/subjects/consent/grant")
                .then().statusCode(200);

            assertInvalidationReceived("GRANTED", 5);
        }

        @Test @Order(3) @DisplayName("Consent revoke publishes REVOKED invalidation")
        void consentRevoke() throws Exception {
            receivedMessages.clear();

            given().header("Authorization", auth()).contentType(ContentType.JSON)
                .body("""
                    {"subject": "ci-subject-1",
                     "scope": {"category": "CiCategory", "fieldName": "ci_field",
                               "entityName": "CiEntity", "purposeName": "ci_purpose"}}
                    """)
                .post("/api/v1/subjects/consent/revoke")
                .then().statusCode(200);

            assertInvalidationReceived("REVOKED", 5);
        }
    }

    // -------------------------------------------------------------------
    // Phase 5 — SSE Endpoint Auth
    // -------------------------------------------------------------------

    @Nested
    @Order(5)
    @DisplayName("Phase 5 — SSE Endpoint Auth")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class SseAuth {

        @Test @Order(1) @DisplayName("Unauthenticated SSE request returns 401")
        void unauthenticated() {
            given()
                .when()
                    .get("/api/v1/events/invalidations")
                .then()
                    .statusCode(401);
        }

        @Test @Order(2) @DisplayName("Authenticated SSE request accepted (200 or streaming)")
        void authenticated() {
            // SSE endpoint returns 200 with text/event-stream content type
            // RestAssured may not fully support SSE, so just verify it doesn't reject
            int status = given()
                .header("Authorization", "Bearer " + apiKey)
                .when()
                    .get("/api/v1/events/invalidations")
                .statusCode();
            assertThat(status)
                .as("Authenticated SSE request should be accepted")
                .isIn(200, 204);
        }
    }

    // -------------------------------------------------------------------
    // Phase 6 — SSE End-to-End Stream Delivery
    // -------------------------------------------------------------------

    @Nested
    @Order(6)
    @DisplayName("Phase 6 — SSE Stream Delivery")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class SseStreamDelivery {

        private String auth() { return "Bearer " + apiKey; }

        @Test @Order(1) @DisplayName("Connect to SSE stream")
        void connectSseStream() throws Exception {
            sseEvents = new ConcurrentLinkedQueue<>();
            var connected = new CountDownLatch(1);

            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

            int port = io.restassured.RestAssured.port > 0
                ? io.restassured.RestAssured.port : 8081;
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port
                    + "/api/v1/events/invalidations"))
                .header("Authorization", auth())
                .header("Accept", "text/event-stream")
                .GET()
                .build();

            sseFuture = client.sendAsync(request,
                    HttpResponse.BodyHandlers.ofInputStream())
                .thenAcceptAsync(response -> {
                    sseInputStream = response.body();
                    connected.countDown();
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(sseInputStream))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (line.startsWith("data:")) {
                                sseEvents.add(line.substring(5).trim());
                            }
                        }
                    } catch (Exception e) {
                        // Stream closed during cleanup — expected
                    }
                });

            assertThat(connected.await(10, TimeUnit.SECONDS))
                .as("SSE connection should be established within 10s")
                .isTrue();

            // Allow subscription to register internally
            Thread.sleep(500);
        }

        @Test @Order(2) @DisplayName("Catalogue mutation arrives on SSE stream")
        void catalogueMutationOnSse() {
            sseEvents.clear();

            given().header("Authorization", auth()).contentType(ContentType.JSON)
                .body("{\"entityName\": \"SseEntity\", \"description\": \"sse\"}")
                .post("/api/v1/catalogue/entities")
                .then().statusCode(anyOf(is(200), is(201)));

            assertSseEventReceived("ENTITY_CHANGED", 10);
        }

        @Test @Order(3) @DisplayName("Governance mutation arrives on SSE stream")
        void governanceMutationOnSse() {
            sseEvents.clear();

            given().header("Authorization", auth()).contentType(ContentType.JSON)
                .body("{\"purposeName\": \"sse_purpose\", \"description\": \"sse\"}")
                .post("/api/v1/governance/purposes")
                .then().statusCode(anyOf(is(200), is(201)));

            assertSseEventReceived("PURPOSE_CHANGED", 10);
        }

        @Test @Order(4) @DisplayName("Consent grant arrives on SSE stream")
        void consentGrantOnSse() {
            // Setup: SSE-specific catalogue + governance items
            given().header("Authorization", auth()).contentType(ContentType.JSON)
                .body("{\"fieldName\": \"sse_field\", \"entityName\": \"SseEntity\", \"description\": \"f\"}")
                .post("/api/v1/catalogue/fields")
                .then().statusCode(anyOf(is(200), is(201)));
            given().header("Authorization", auth()).contentType(ContentType.JSON)
                .body("{\"categoryName\": \"SseCategory\", \"description\": \"c\"}")
                .post("/api/v1/catalogue/categories")
                .then().statusCode(anyOf(is(200), is(201)));
            given().header("Authorization", auth()).contentType(ContentType.JSON)
                .body("{\"fieldName\": \"sse_field\", \"entityName\": \"SseEntity\", \"categoryName\": \"SseCategory\"}")
                .post("/api/v1/catalogue/fields/categories")
                .then().statusCode(anyOf(is(200), is(201)));
            given().header("Authorization", auth()).contentType(ContentType.JSON)
                .body("{\"purposeName\": \"sse_purpose\", \"regulationName\": \"CiRegulation\","
                    + " \"lawfulBasis\": \"consent\", \"onGranted\": \"allow\", \"onDenied\": \"deny\"}")
                .post("/api/v1/governance/purposes/regulations")
                .then().statusCode(anyOf(is(200), is(201)));
            given().header("Authorization", auth()).contentType(ContentType.JSON)
                .body("{\"purposeName\": \"sse_purpose\", \"policyType\": \"field\","
                    + " \"fieldName\": \"sse_field\", \"entityName\": \"SseEntity\","
                    + " \"categoryName\": \"SseCategory\"}")
                .post("/api/v1/governance/purposes/policies")
                .then().statusCode(anyOf(is(200), is(201)));

            sseEvents.clear();

            given().header("Authorization", auth()).contentType(ContentType.JSON)
                .body("{\"subject\": \"sse-subject\","
                    + " \"scope\": {\"category\": \"SseCategory\","
                    + " \"fieldName\": \"sse_field\","
                    + " \"entityName\": \"SseEntity\","
                    + " \"purposeName\": \"sse_purpose\"}}")
                .post("/api/v1/subjects/consent/grant")
                .then().statusCode(200);

            assertSseEventReceived("GRANTED", 10);
        }

        @Test @Order(5) @DisplayName("SSE event payload contains tenantId and action")
        void ssePayloadStructure() {
            assertThat(sseEvents).isNotEmpty();
            String anyEvent = sseEvents.peek();
            assertThat(anyEvent)
                .as("SSE event should contain tenantId")
                .contains("\"tenantId\"");
            assertThat(anyEvent)
                .as("SSE event should contain action")
                .contains("\"action\"");
        }

        private void assertSseEventReceived(String expectedAction, int timeoutSeconds) {
            org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(timeoutSeconds))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    boolean found = sseEvents.stream()
                        .anyMatch(evt -> evt.contains("\"action\":\"" + expectedAction + "\"")
                            || evt.contains("\"action\": \"" + expectedAction + "\"")
                            || evt.contains(expectedAction));
                    assertThat(found)
                        .as("SSE stream should deliver %s event, got: %s",
                            expectedAction, sseEvents)
                        .isTrue();
                });
        }
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    /**
     * Waits for a message containing the given action string to appear in
     * receivedMessages within the specified timeout.
     */
    private void assertInvalidationReceived(String expectedAction, int timeoutSeconds) {
        org.awaitility.Awaitility.await()
            .atMost(Duration.ofSeconds(timeoutSeconds))
            .pollInterval(Duration.ofMillis(200))
            .untilAsserted(() -> {
                boolean found = receivedMessages.stream()
                    .anyMatch(msg -> msg.contains("\"action\":\"" + expectedAction + "\"")
                        || msg.contains("\"action\": \"" + expectedAction + "\"")
                        || msg.contains(expectedAction));
                assertThat(found)
                    .as("Should receive invalidation message with action=%s, got: %s",
                        expectedAction, receivedMessages)
                    .isTrue();
            });
    }

    // -------------------------------------------------------------------
    // Cleanup
    // -------------------------------------------------------------------

    @AfterAll
    static void cleanup() {
        // Close SSE stream
        if (sseInputStream != null) {
            try { sseInputStream.close(); } catch (Exception ignored) { }
        }
        if (sseFuture != null) {
            sseFuture.cancel(true);
        }
        // Close Pub/Sub
        if (pubSub != null && pubSub.isSubscribed()) {
            pubSub.unsubscribe();
        }
        if (subscriberThread != null) {
            try {
                subscriberThread.join(5000);
            } catch (InterruptedException ignored) {
            }
        }
        if (subscriberJedis != null) {
            subscriberJedis.close();
        }
        if (support != null) support.close();
    }
}
