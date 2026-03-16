package com.koostack.cveil.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.*;

/**
 * Integration tests for the WASM bridge — loads the real WASM module and exercises init, graph
 * ingestion, masking, evaluation, invalidation, and SSE lifecycle through the low-level bridge API.
 *
 * <p>Mirrors the JS SDK's wasm-bridge.test.ts.
 */
class WasmBridgeTest {

    private static final Path WASM_PATH =
            Path.of("../cveil-sdk-js/wasm/cveil_sdk_core.wasm").toAbsolutePath().normalize();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String TEST_CONFIG =
            """
            {
              "apiBaseUrl": "http://localhost:8080",
              "apiToken": "cveil_test_token_12345",
              "defaultJurisdiction": "EU",
              "staleMode": "conservativeMask",
              "disconnectThresholdMs": 60000,
              "decisionCacheMaxSize": 100,
              "decisionCacheTtlMs": 300000,
              "dedupWindowSize": 500
            }
            """;

    private static final String TEST_GRAPH_PAGE =
            """
            {
              "nodes": [
                {"type": "entity", "name": "User", "properties": {}},
                {"type": "field", "name": "User/email", "properties": {}},
                {"type": "field", "name": "User/phone", "properties": {}},
                {"type": "category", "name": "PII", "properties": {}},
                {"type": "purpose", "name": "Marketing", "properties": {}},
                {"type": "regulation", "name": "GDPR", "properties": {}},
                {"type": "jurisdiction", "name": "EU", "properties": {}},
                {"type": "policy", "name": "FIELD:User/email:Marketing", "properties": {"policyType": "FIELD"}},
                {"type": "policy", "name": "CAT:PII:Marketing", "properties": {"policyType": "CATEGORY"}}
              ],
              "edges": [
                {"sourceType": "field", "sourceName": "User/email", "targetType": "category", "targetName": "PII", "properties": {}},
                {"sourceType": "field", "sourceName": "User/phone", "targetType": "category", "targetName": "PII", "properties": {}},
                {"sourceType": "purpose", "sourceName": "Marketing", "targetType": "regulation", "targetName": "GDPR", "properties": {"lawfulBasis": "consent", "onGranted": "PLAINTEXT", "onDenied": "REDACT_ALL"}},
                {"sourceType": "regulation", "sourceName": "GDPR", "targetType": "jurisdiction", "targetName": "EU", "properties": {}},
                {"sourceType": "purpose", "sourceName": "Marketing", "targetType": "policy", "targetName": "FIELD:User/email:Marketing", "properties": {}},
                {"sourceType": "purpose", "sourceName": "Marketing", "targetType": "policy", "targetName": "CAT:PII:Marketing", "properties": {}}
              ],
              "pageInfo": {"nextCursor": null, "hasMore": false, "totalNodes": 9, "totalEdges": 6}
            }
            """;

    private WasmBridge bridge;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(
                WASM_PATH.toFile().exists(),
                "WASM binary not found at " + WASM_PATH + " — skipping integration tests");

        bridge = WasmBridge.load(WASM_PATH);
    }

    @AfterEach
    void tearDown() {
        if (bridge != null) {
            try {
                bridge.shutdown();
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    @DisplayName("loads WASM module successfully")
    void loadsWasmModule() {
        assertThat(bridge).isNotNull();
    }

    @Test
    @DisplayName("initializes with valid config")
    void initializesWithValidConfig() throws Exception {
        var result = MAPPER.readTree(bridge.init(TEST_CONFIG));
        assertThat(result.path("status").asText()).isEqualTo("initialized");
    }

    @Test
    @DisplayName("rejects invalid config")
    void rejectsInvalidConfig() {
        bridge.shutdown();
        assertThatThrownBy(() -> bridge.init("{\"apiBaseUrl\":\"\"}"))
                .isInstanceOf(CveilWasmError.class);
        // Re-init for remaining tests
        bridge.init(TEST_CONFIG);
    }

    @Test
    @DisplayName("ingests graph page")
    void ingestsGraphPage() throws Exception {
        bridge.init(TEST_CONFIG);
        var result = MAPPER.readTree(bridge.ingestGraphPage(TEST_GRAPH_PAGE));
        assertThat(result.path("ingested").asBoolean()).isTrue();
        assertThat(result.path("hasMore").asBoolean()).isFalse();
        assertThat(result.path("totalNodes").asInt()).isGreaterThan(0);
    }

    @Test
    @DisplayName("returns status")
    void returnsStatus() throws Exception {
        bridge.init(TEST_CONFIG);
        bridge.ingestGraphPage(TEST_GRAPH_PAGE);

        var status = MAPPER.readTree(bridge.status());
        assertThat(status.path("initialized").asBoolean()).isTrue();
        assertThat(status.path("graphNodes").asInt()).isGreaterThan(0);
    }

    @Test
    @DisplayName("masks without consent (deny-by-default)")
    void masksDenyByDefault() throws Exception {
        bridge.init(TEST_CONFIG);
        bridge.ingestGraphPage(TEST_GRAPH_PAGE);

        var payload =
                """
                {"email": "alice@example.com", "name": "Alice"}
                """;
        var result = MAPPER.readTree(bridge.mask("user-1", "User", payload, "Marketing"));

        // email is registered → should be masked
        assertThat(result.path("data").path("email").asText()).isNotEqualTo("alice@example.com");
        // name is NOT registered → pass through
        assertThat(result.path("data").path("name").asText()).isEqualTo("Alice");
    }

    @Test
    @DisplayName("masks with granted consent (plaintext)")
    void masksWithGrantedConsent() throws Exception {
        bridge.init(TEST_CONFIG);
        bridge.ingestGraphPage(TEST_GRAPH_PAGE);

        // Ingest a GRANT decision
        var decisions =
                """
                [{"policyName": "FIELD:User/email:Marketing", "action": "GRANT", "expiresAt": null}]
                """;
        bridge.ingestDecisions("user-2", decisions);

        var payload =
                """
                {"email": "bob@example.com", "phone": "555-1234"}
                """;
        var result = MAPPER.readTree(bridge.mask("user-2", "User", payload, "Marketing"));

        // email: GRANTED → PLAINTEXT
        assertThat(result.path("data").path("email").asText()).isEqualTo("bob@example.com");
        // phone: no consent → REDACT_ALL
        assertThat(result.path("data").path("phone").asText()).isEqualTo("***");
    }

    @Test
    @DisplayName("evaluates field consent")
    void evaluatesFieldConsent() throws Exception {
        bridge.init(TEST_CONFIG);
        bridge.ingestGraphPage(TEST_GRAPH_PAGE);

        // Grant email consent for user-2
        bridge.ingestDecisions(
                "user-2",
                """
                [{"policyName": "FIELD:User/email:Marketing", "action": "GRANT", "expiresAt": null}]
                """);

        var fields = """
                ["email", "phone"]
                """;
        var result = MAPPER.readTree(bridge.evaluate("user-2", "User", fields, "Marketing"));

        assertThat(result.size()).isEqualTo(2);
        var hasEmail = false;
        var hasPhone = false;
        for (var item : result) {
            if ("email".equals(item.path("field").asText())) hasEmail = true;
            if ("phone".equals(item.path("field").asText())) hasPhone = true;
        }
        assertThat(hasEmail).isTrue();
        assertThat(hasPhone).isTrue();
    }

    @Test
    @DisplayName("processes invalidation events")
    void processesInvalidation() throws Exception {
        bridge.init(TEST_CONFIG);
        bridge.ingestGraphPage(TEST_GRAPH_PAGE);

        var event =
                """
                {"messageId": "evt-1", "tenantId": "t1", "action": "PURPOSE_CHANGED", "purpose": "Marketing"}
                """;
        var result = MAPPER.readTree(bridge.processInvalidation(event));
        assertThat(result.path("effect").asText()).isEqualTo("refresh_subgraph");
        assertThat(result.path("nodeType").asText()).isEqualTo("purpose");
        assertThat(result.path("nodeName").asText()).isEqualTo("Marketing");
    }

    @Test
    @DisplayName("deduplicates invalidation events")
    void deduplicatesInvalidation() throws Exception {
        bridge.init(TEST_CONFIG);
        bridge.ingestGraphPage(TEST_GRAPH_PAGE);

        var event =
                """
                {"messageId": "evt-dup", "tenantId": "t1", "action": "PURPOSE_CHANGED", "purpose": "Marketing"}
                """;
        bridge.processInvalidation(event);
        var result = MAPPER.readTree(bridge.processInvalidation(event));
        assertThat(result.path("effect").asText()).isEqualTo("duplicate");
    }

    @Test
    @DisplayName("manages SSE lifecycle")
    void managesSseLifecycle() throws Exception {
        bridge.init(TEST_CONFIG);
        bridge.ingestGraphPage(TEST_GRAPH_PAGE);

        var connected = MAPPER.readTree(bridge.sseConnected());
        assertThat(connected.path("connected").asBoolean()).isTrue();

        var status1 = MAPPER.readTree(bridge.status());
        assertThat(status1.path("connectionState").asText()).isEqualTo("Connected");

        var disconnected = MAPPER.readTree(bridge.sseDisconnected());
        assertThat(disconnected.path("connected").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("handles reconnect with flush")
    void handlesReconnectWithFlush() throws Exception {
        bridge.init(TEST_CONFIG);
        bridge.ingestGraphPage(TEST_GRAPH_PAGE);

        bridge.sseConnected();
        var result = MAPPER.readTree(bridge.sseReconnected());
        assertThat(result.path("effect").asText()).isEqualTo("flush_and_reload");

        var status = MAPPER.readTree(bridge.status());
        assertThat(status.path("graphNodes").asInt()).isEqualTo(0); // Flushed
        assertThat(status.path("connectionState").asText()).isEqualTo("Connected");
    }

    @Test
    @DisplayName("shuts down cleanly")
    void shutsDownCleanly() throws Exception {
        bridge.init(TEST_CONFIG);

        var result = MAPPER.readTree(bridge.shutdown());
        assertThat(result.path("status").asText()).isEqualTo("shutdown");

        // Status should fail after shutdown
        assertThatThrownBy(() -> bridge.status()).isInstanceOf(CveilWasmError.class);

        bridge = null; // Prevent double-shutdown in tearDown
    }
}
