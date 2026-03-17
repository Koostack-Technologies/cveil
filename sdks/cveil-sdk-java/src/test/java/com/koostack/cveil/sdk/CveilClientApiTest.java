package com.koostack.cveil.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.koostack.cveil.sdk.model.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.*;

/**
 * Tests for {@link CveilClient} API methods (consent, subject, governance, catalogue). Routes all
 * HTTP through MockWebServer with a null bridge (no WASM). Mirrors the JS SDK's api-client.test.ts.
 */
class CveilClientApiTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MockWebServer server;
    private CveilClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        var baseUrl = server.url("/").toString();
        // Remove trailing slash
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        var config = new CveilConfig(baseUrl, "cveil_test_token_12345", "EU");
        var options = new CveilClientOptions(config, Path.of("/dev/null"), false, 100);

        // Use the package-private constructor with null bridge — tests focus on HTTP behavior
        client = new CveilClient(options, null);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (server != null) {
            server.shutdown();
        }
    }

    // ─── Error Handling ──────────────────────────────────────────

    @Nested
    @DisplayName("error handling")
    class ErrorHandlingTests {

        @Test
        void throwsCveilApiErrorOn400WithStructuredBody() {
            server.enqueue(
                    new MockResponse()
                            .setResponseCode(400)
                            .addHeader("Content-Type", "application/json")
                            .setBody(
                                    """
                                    {
                                      "requestId": "req-123",
                                      "error": "validation_error",
                                      "message": "subjectName is required",
                                      "retryable": false,
                                      "retryAfter": null
                                    }
                                    """));

            assertThatThrownBy(() -> client.registerSubject(new RegisterSubjectRequest("", null)))
                    .isInstanceOf(CveilApiError.class)
                    .satisfies(
                            e -> {
                                var apiError = (CveilApiError) e;
                                assertThat(apiError.status()).isEqualTo(400);
                                assertThat(apiError.getMessage())
                                        .isEqualTo("subjectName is required");
                                assertThat(apiError.retryable()).isFalse();
                                assertThat(apiError.requestId()).isEqualTo("req-123");
                                assertThat(apiError.errorCode()).isEqualTo("validation_error");
                            });
        }

        @Test
        void throwsCveilApiErrorOn503WithRetryable() {
            server.enqueue(
                    new MockResponse()
                            .setResponseCode(503)
                            .addHeader("Content-Type", "application/json")
                            .setBody(
                                    """
                                    {
                                      "error": "service_unavailable",
                                      "message": "Database is down",
                                      "retryable": true,
                                      "retryAfter": 5
                                    }
                                    """));

            assertThatThrownBy(() -> client.listSubjects())
                    .isInstanceOf(CveilApiError.class)
                    .satisfies(
                            e -> {
                                var apiError = (CveilApiError) e;
                                assertThat(apiError.status()).isEqualTo(503);
                                assertThat(apiError.retryable()).isTrue();
                                assertThat(apiError.retryAfter()).isEqualTo(5);
                            });
        }
    }

    // ─── Consent Operations ──────────────────────────────────────

    @Nested
    @DisplayName("consent operations")
    class ConsentOperationTests {

        @Test
        void grantSendsCorrectRequest() throws Exception {
            server.enqueue(
                    jsonResponse(
                            """
                    {"message": "ok"}
                    """));

            var result =
                    client.grant(
                            new GrantRequest(
                                    "user-1",
                                    new ConsentScope(null, "email", "User", "Marketing"),
                                    null));
            assertThat(result.message()).isEqualTo("ok");

            var req = server.takeRequest();
            assertThat(req.getMethod()).isEqualTo("POST");
            assertThat(req.getPath()).isEqualTo("/api/v1/subjects/consent/grant");
            var body = MAPPER.readTree(req.getBody().readUtf8());
            assertThat(body.path("scope").path("purposeName").asText()).isEqualTo("Marketing");
        }

        @Test
        void revokeSendsCorrectRequest() throws Exception {
            server.enqueue(
                    jsonResponse(
                            """
                    {"message": "ok"}
                    """));

            var result =
                    client.revoke(
                            new RevokeRequest(
                                    "user-1",
                                    new ConsentScope(null, "email", "User", "Marketing")));
            assertThat(result.message()).isEqualTo("ok");

            var req = server.takeRequest();
            assertThat(req.getMethod()).isEqualTo("POST");
            assertThat(req.getPath()).isEqualTo("/api/v1/subjects/consent/revoke");
        }

        @Test
        void grantAllHandlesPartialFailures() throws Exception {
            server.enqueue(
                    jsonResponse(
                            """
                            {
                              "succeeded": 1,
                              "failed": 1,
                              "errors": [{"index": 1, "scope": {"purposeName": "Analytics"}, "message": "purpose not found"}]
                            }
                            """));

            var result =
                    client.grantAll(
                            new BatchGrantRequest(
                                    "user-2",
                                    List.of(
                                            new BatchConsentEntry(
                                                    new ConsentScope(
                                                            null, "email", "User", "Marketing"),
                                                    null),
                                            new BatchConsentEntry(
                                                    new ConsentScope(
                                                            null, "phone", "User", "Analytics"),
                                                    null))));

            assertThat(result.succeeded()).isEqualTo(1);
            assertThat(result.failed()).isEqualTo(1);
            assertThat(result.errors()).hasSize(1);
            assertThat(result.errors().get(0).index()).isEqualTo(1);
        }

        @Test
        void revokeAllSendsBatchRequest() throws Exception {
            server.enqueue(
                    jsonResponse(
                            """
                            {"succeeded": 2, "failed": 0, "errors": []}
                            """));

            var result =
                    client.revokeAll(
                            new BatchRevokeRequest(
                                    "user-3",
                                    List.of(
                                            new BatchConsentEntry(
                                                    new ConsentScope(
                                                            null, "email", "User", "Marketing"),
                                                    null),
                                            new BatchConsentEntry(
                                                    new ConsentScope(
                                                            null, "phone", "User", "Marketing"),
                                                    null))));
            assertThat(result.succeeded()).isEqualTo(2);
            assertThat(result.failed()).isEqualTo(0);
        }

        @Test
        void evaluateConsentSendsScopesToBackend() throws Exception {
            server.enqueue(
                    jsonResponse(
                            """
                            {
                              "subject": "user-5",
                              "granted": [{"purposeName": "Marketing", "fieldName": "email", "entityName": "User"}],
                              "denied": [],
                              "reasons": {},
                              "details": {}
                            }
                            """));

            var result =
                    client.evaluateConsent(
                            "user-5",
                            new EvaluateConsentRequest(
                                    List.of(new ConsentScope(null, "email", "User", "Marketing"))));
            assertThat(result.subject()).isEqualTo("user-5");
            assertThat(result.granted()).hasSize(1);
        }

        @Test
        void getSubjectSummaryReturnsConsentView() throws Exception {
            server.enqueue(
                    jsonResponse(
                            """
                            {"subjectId": "user-6", "entries": []}
                            """));

            var result = client.getSubjectSummary("user-6");
            assertThat(result.subjectId()).isEqualTo("user-6");

            var req = server.takeRequest();
            assertThat(req.getPath()).isEqualTo("/api/v1/subjects/user-6/summary");
        }

        @Test
        void getSubjectHistoryReturnsLedgerEntries() throws Exception {
            server.enqueue(
                    jsonResponse(
                            """
                            [{
                              "consentLedgerId": 1, "subjectId": "user-7", "policyId": 10,
                              "purposeName": "Marketing", "policyType": "FIELD",
                              "fieldName": "email", "entityName": "User", "categoryName": null,
                              "action": "GRANT", "actor": "user-7",
                              "ipAddress": null, "userAgent": null,
                              "createdAt": "2026-01-01T00:00:00Z", "expiresAt": null
                            }]
                            """));

            var result = client.getSubjectHistory("user-7");
            assertThat(result).hasSize(1);
            assertThat(result.get(0).action()).isEqualTo("GRANT");
        }
    }

    // ─── Subject Operations ──────────────────────────────────────

    @Nested
    @DisplayName("subject operations")
    class SubjectOperationTests {

        @Test
        void listSubjects() throws Exception {
            server.enqueue(
                    jsonResponse(
                            """
                            [{"subjectId": 1, "subjectName": "alice", "description": null,
                              "createdAt": "2026-01-01T00:00:00Z", "createdBy": "admin",
                              "updatedAt": "2026-01-01T00:00:00Z", "updatedBy": "admin"}]
                            """));

            var result = client.listSubjects();
            assertThat(result).hasSize(1);
            assertThat(result.get(0).subjectName()).isEqualTo("alice");
        }

        @Test
        void getSubject() throws Exception {
            server.enqueue(
                    jsonResponse(
                            """
                            {"subjectId": 1, "subjectName": "alice", "description": "Test user",
                             "createdAt": "2026-01-01T00:00:00Z", "createdBy": "admin",
                             "updatedAt": "2026-01-01T00:00:00Z", "updatedBy": "admin"}
                            """));

            var result = client.getSubject("alice");
            assertThat(result.subjectName()).isEqualTo("alice");
            assertThat(result.description()).isEqualTo("Test user");

            var req = server.takeRequest();
            assertThat(req.getPath()).isEqualTo("/api/v1/subjects/alice");
        }

        @Test
        void registerSubject() throws Exception {
            server.enqueue(
                    jsonResponse(
                            """
                    {"message": "ok"}
                    """));

            var result = client.registerSubject(new RegisterSubjectRequest("bob", "Another user"));
            assertThat(result.message()).isEqualTo("ok");

            var req = server.takeRequest();
            assertThat(req.getMethod()).isEqualTo("POST");
            var body = MAPPER.readTree(req.getBody().readUtf8());
            assertThat(body.path("subjectName").asText()).isEqualTo("bob");
        }

        @Test
        void updateSubject() throws Exception {
            server.enqueue(
                    jsonResponse(
                            """
                    {"message": "ok"}
                    """));

            var result =
                    client.updateSubject("bob", new UpdateSubjectRequest("Updated description"));
            assertThat(result.message()).isEqualTo("ok");

            var req = server.takeRequest();
            assertThat(req.getMethod()).isEqualTo("PUT");
            assertThat(req.getPath()).isEqualTo("/api/v1/subjects/bob");
        }

        @Test
        void removeSubject() throws Exception {
            server.enqueue(
                    jsonResponse(
                            """
                    {"message": "ok"}
                    """));

            var result = client.removeSubject("bob");
            assertThat(result.message()).isEqualTo("ok");

            var req = server.takeRequest();
            assertThat(req.getMethod()).isEqualTo("DELETE");
            assertThat(req.getPath()).isEqualTo("/api/v1/subjects/bob");
        }

        @Test
        void exportSubjectData() throws Exception {
            server.enqueue(
                    jsonResponse(
                            """
                            {
                              "subject": "alice",
                              "consent": {"subjectId": "alice", "entries": []},
                              "cachedKeyCount": 3,
                              "exportedAt": "2026-01-01T00:00:00Z"
                            }
                            """));

            var result = client.exportSubjectData("alice");
            assertThat(result.subject()).isEqualTo("alice");
            assertThat(result.cachedKeyCount()).isEqualTo(3);
        }

        @Test
        void deleteSubjectData() throws Exception {
            server.enqueue(
                    jsonResponse(
                            """
                            {
                              "subject": "alice",
                              "deletionRequestId": "del-001",
                              "ledgerEntriesRevoked": 5,
                              "cachedKeysDeleted": 3,
                              "status": "PENDING_SHRED",
                              "completedAt": "2026-01-01T00:00:00Z"
                            }
                            """));

            var result = client.deleteSubjectData("alice");
            assertThat(result.deletionRequestId()).isEqualTo("del-001");
            assertThat(result.status()).isEqualTo("PENDING_SHRED");

            var req = server.takeRequest();
            assertThat(req.getMethod()).isEqualTo("DELETE");
            assertThat(req.getPath()).contains("/subjects/alice/audit");
            assertThat(req.getPath()).contains("confirm=DELETE_alice");
        }

        @Test
        void shredDsarKey() throws Exception {
            server.enqueue(
                    jsonResponse(
                            """
                    {"message": "ok"}
                    """));

            var result = client.shredDsarKey("alice");
            assertThat(result.message()).isEqualTo("ok");

            var req = server.takeRequest();
            assertThat(req.getMethod()).isEqualTo("DELETE");
            assertThat(req.getPath()).contains("/dsar-key");
        }
    }

    // ─── Governance Operations ───────────────────────────────────

    @Nested
    @DisplayName("governance operations")
    class GovernanceOperationTests {

        @Test
        void listPurposes() throws Exception {
            // Enqueue for listPurposes
            server.enqueue(
                    jsonResponse(
                            """
                            [{"purposeId": 1, "purposeName": "Marketing", "description": null, "regulations": [], "policies": []}]
                            """));

            var result = client.listPurposes();
            assertThat(result).hasSize(1);
            assertThat(result.get(0).purposeName()).isEqualTo("Marketing");
        }

        @Test
        void getPurpose() throws Exception {
            server.enqueue(
                    jsonResponse(
                            """
                            {"purposeId": 1, "purposeName": "Marketing", "description": "Email marketing", "regulations": [], "policies": []}
                            """));

            var result = client.getPurpose("Marketing");
            assertThat(result.purposeName()).isEqualTo("Marketing");
        }

        @Test
        void registerPurposeReloadsGraph() throws Exception {
            // 1st: the POST
            server.enqueue(
                    jsonResponse(
                            """
                    {"message": "ok"}
                    """));
            // 2nd: the graph reload
            server.enqueue(emptyGraphResponse());

            var result =
                    client.registerPurpose(
                            new RegisterPurposeRequest("Analytics", "Data analytics"));
            assertThat(result.message()).isEqualTo("ok");

            var req = server.takeRequest();
            assertThat(req.getMethod()).isEqualTo("POST");
            assertThat(req.getPath()).isEqualTo("/api/v1/governance/purposes");
        }

        @Test
        void removePurpose() throws Exception {
            server.enqueue(
                    jsonResponse(
                            """
                    {"message": "ok"}
                    """));
            server.enqueue(emptyGraphResponse());

            var result = client.removePurpose("Analytics");
            assertThat(result.message()).isEqualTo("ok");
        }

        @Test
        void addPurposePolicy() throws Exception {
            server.enqueue(
                    jsonResponse(
                            """
                    {"message": "ok"}
                    """));
            server.enqueue(emptyGraphResponse());

            var result =
                    client.addPurposePolicy(
                            new AddPurposePolicyRequest(
                                    "Marketing", "FIELD", "email", "User", null));
            assertThat(result.message()).isEqualTo("ok");
        }

        @Test
        void removePurposePolicy() throws Exception {
            server.enqueue(
                    jsonResponse(
                            """
                    {"message": "ok"}
                    """));
            server.enqueue(emptyGraphResponse());

            var result =
                    client.removePurposePolicy(
                            new AddPurposePolicyRequest(
                                    "Marketing", "FIELD", "email", "User", null));
            assertThat(result.message()).isEqualTo("ok");
        }

        @Test
        void linkPurposeToRegulation() throws Exception {
            server.enqueue(
                    jsonResponse(
                            """
                    {"message": "ok"}
                    """));
            server.enqueue(emptyGraphResponse());

            var result =
                    client.linkPurposeToRegulation(
                            new LinkPurposeRegulationRequest(
                                    "Marketing", "GDPR", "consent", "Plaintext", "RedactAll"));
            assertThat(result.message()).isEqualTo("ok");
        }

        @Test
        void unlinkPurposeFromRegulation() throws Exception {
            server.enqueue(
                    jsonResponse(
                            """
                    {"message": "ok"}
                    """));
            server.enqueue(emptyGraphResponse());

            var result = client.unlinkPurposeFromRegulation("Marketing", "GDPR");
            assertThat(result.message()).isEqualTo("ok");

            var req = server.takeRequest();
            assertThat(req.getPath()).contains("/purposes/Marketing/regulations/GDPR");
        }

        @Test
        void listRegulations() throws Exception {
            server.enqueue(
                    jsonResponse(
                            """
                            [{"regulationId": 1, "regulationName": "GDPR", "description": null, "activeJurisdictions": []}]
                            """));

            var result = client.listRegulations();
            assertThat(result.get(0).regulationName()).isEqualTo("GDPR");
        }

        @Test
        void registerRegulation() throws Exception {
            server.enqueue(
                    jsonResponse(
                            """
                    {"message": "ok"}
                    """));
            server.enqueue(emptyGraphResponse());

            var result =
                    client.registerRegulation(
                            new RegisterRegulationRequest(
                                    "CCPA", "California Consumer Privacy Act"));
            assertThat(result.message()).isEqualTo("ok");
        }

        @Test
        void removeRegulation() throws Exception {
            server.enqueue(
                    jsonResponse(
                            """
                    {"message": "ok"}
                    """));
            server.enqueue(emptyGraphResponse());

            var result = client.removeRegulation("CCPA");
            assertThat(result.message()).isEqualTo("ok");
        }

        @Test
        void listJurisdictions() throws Exception {
            server.enqueue(
                    jsonResponse(
                            """
                            [{"jurisdictionId": 1, "jurisdictionName": "EU", "description": null}]
                            """));

            var result = client.listJurisdictions();
            assertThat(result.get(0).jurisdictionName()).isEqualTo("EU");
        }

        @Test
        void registerJurisdiction() throws Exception {
            server.enqueue(
                    jsonResponse(
                            """
                    {"message": "ok"}
                    """));
            server.enqueue(emptyGraphResponse());

            var result =
                    client.registerJurisdiction(
                            new RegisterJurisdictionRequest("California", "US - California"));
            assertThat(result.message()).isEqualTo("ok");
        }

        @Test
        void updateJurisdiction() throws Exception {
            server.enqueue(
                    jsonResponse(
                            """
                    {"message": "ok"}
                    """));
            server.enqueue(emptyGraphResponse());

            var result =
                    client.updateJurisdiction(
                            "California", new UpdateJurisdictionRequest("Updated"));
            assertThat(result.message()).isEqualTo("ok");
        }

        @Test
        void removeJurisdiction() throws Exception {
            server.enqueue(
                    jsonResponse(
                            """
                    {"message": "ok"}
                    """));
            server.enqueue(emptyGraphResponse());

            var result = client.removeJurisdiction("California");
            assertThat(result.message()).isEqualTo("ok");
        }

        @Test
        void addJurisdictionToRegulation() throws Exception {
            server.enqueue(
                    jsonResponse(
                            """
                    {"message": "ok"}
                    """));
            server.enqueue(emptyGraphResponse());

            var result =
                    client.addJurisdictionToRegulation(
                            new AddJurisdictionToRegulationRequest("GDPR", "EU"));
            assertThat(result.message()).isEqualTo("ok");
        }

        @Test
        void removeJurisdictionFromRegulation() throws Exception {
            server.enqueue(
                    jsonResponse(
                            """
                    {"message": "ok"}
                    """));
            server.enqueue(emptyGraphResponse());

            var result = client.removeJurisdictionFromRegulation("GDPR", "EU");
            assertThat(result.message()).isEqualTo("ok");

            var req = server.takeRequest();
            assertThat(req.getPath()).contains("/regulations/GDPR/jurisdictions/EU");
        }
    }

    // ─── Catalogue Operations ────────────────────────────────────

    @Nested
    @DisplayName("catalogue operations")
    class CatalogueOperationTests {

        @Test
        void listFields() throws Exception {
            server.enqueue(
                    jsonResponse(
                            """
                            [{"fieldId": 1, "fieldName": "email", "description": null,
                              "entity": {"entityId": 1, "entityName": "User"}, "categories": []}]
                            """));

            var result = client.listFields();
            assertThat(result.get(0).fieldName()).isEqualTo("email");
        }

        @Test
        void registerFieldReloadsGraph() throws Exception {
            server.enqueue(
                    jsonResponse(
                            """
                    {"message": "ok"}
                    """));
            server.enqueue(emptyGraphResponse());

            var result =
                    client.registerField(
                            new RegisterFieldRequest(
                                    "ssn", "User", "Social Security Number", null));
            assertThat(result.message()).isEqualTo("ok");
        }

        @Test
        void removeField() throws Exception {
            server.enqueue(
                    jsonResponse(
                            """
                    {"message": "ok"}
                    """));
            server.enqueue(emptyGraphResponse());

            var result = client.removeField("User", "ssn");
            assertThat(result.message()).isEqualTo("ok");

            var req = server.takeRequest();
            assertThat(req.getPath()).contains("/catalogue/fields/User/ssn");
        }

        @Test
        void listEntities() throws Exception {
            server.enqueue(
                    jsonResponse(
                            """
                            [{"entityId": 1, "entityName": "User", "description": null}]
                            """));

            var result = client.listEntities();
            assertThat(result.get(0).entityName()).isEqualTo("User");
        }

        @Test
        void getEntity() throws Exception {
            server.enqueue(
                    jsonResponse(
                            """
                            {"entityId": 1, "entityName": "User", "description": "A user"}
                            """));

            var result = client.getEntity("User");
            assertThat(result.entityName()).isEqualTo("User");
        }

        @Test
        void registerEntity() throws Exception {
            server.enqueue(
                    jsonResponse(
                            """
                    {"message": "ok"}
                    """));
            server.enqueue(emptyGraphResponse());

            var result =
                    client.registerEntity(
                            new RegisterEntityRequest("Patient", "Healthcare patient"));
            assertThat(result.message()).isEqualTo("ok");
        }

        @Test
        void updateEntity() throws Exception {
            server.enqueue(
                    jsonResponse(
                            """
                    {"message": "ok"}
                    """));

            var result = client.updateEntity("Patient", new UpdateEntityRequest("Updated"));
            assertThat(result.message()).isEqualTo("ok");
        }

        @Test
        void removeEntity() throws Exception {
            server.enqueue(
                    jsonResponse(
                            """
                    {"message": "ok"}
                    """));
            server.enqueue(emptyGraphResponse());

            var result = client.removeEntity("Patient");
            assertThat(result.message()).isEqualTo("ok");
        }

        @Test
        void listCategories() throws Exception {
            server.enqueue(
                    jsonResponse(
                            """
                            [{"categoryId": 1, "categoryName": "PII", "description": null}]
                            """));

            var result = client.listCategories();
            assertThat(result.get(0).categoryName()).isEqualTo("PII");
        }

        @Test
        void getCategory() throws Exception {
            server.enqueue(
                    jsonResponse(
                            """
                            {"categoryId": 1, "categoryName": "PII", "description": "Personal info"}
                            """));

            var result = client.getCategory("PII");
            assertThat(result.categoryName()).isEqualTo("PII");
        }

        @Test
        void registerCategory() throws Exception {
            server.enqueue(
                    jsonResponse(
                            """
                    {"message": "ok"}
                    """));
            server.enqueue(emptyGraphResponse());

            var result =
                    client.registerCategory(new RegisterCategoryRequest("HEALTH", "Health data"));
            assertThat(result.message()).isEqualTo("ok");
        }

        @Test
        void updateCategory() throws Exception {
            server.enqueue(
                    jsonResponse(
                            """
                    {"message": "ok"}
                    """));

            var result = client.updateCategory("HEALTH", new UpdateCategoryRequest("Updated"));
            assertThat(result.message()).isEqualTo("ok");
        }

        @Test
        void removeCategory() throws Exception {
            server.enqueue(
                    jsonResponse(
                            """
                    {"message": "ok"}
                    """));
            server.enqueue(emptyGraphResponse());

            var result = client.removeCategory("HEALTH");
            assertThat(result.message()).isEqualTo("ok");
        }

        @Test
        void assignFieldToCategory() throws Exception {
            server.enqueue(
                    jsonResponse(
                            """
                    {"message": "ok"}
                    """));
            server.enqueue(emptyGraphResponse());

            var result =
                    client.assignFieldToCategory(
                            new AssignFieldCategoryRequest("email", "User", "PII", null));
            assertThat(result.message()).isEqualTo("ok");
        }

        @Test
        void unassignFieldFromCategory() throws Exception {
            server.enqueue(
                    jsonResponse(
                            """
                    {"message": "ok"}
                    """));
            server.enqueue(emptyGraphResponse());

            var result = client.unassignFieldFromCategory("User", "email", "PII");
            assertThat(result.message()).isEqualTo("ok");

            var req = server.takeRequest();
            assertThat(req.getPath()).contains("/fields/User/email/categories/PII");
        }
    }

    // ─── Auth Headers ────────────────────────────────────────────

    @Nested
    @DisplayName("auth headers")
    class AuthHeaderTests {

        @Test
        void sendsBearerTokenOnEveryRequest() throws Exception {
            server.enqueue(
                    jsonResponse(
                            """
                            [{"subjectId": 1, "subjectName": "x", "description": null,
                              "createdAt": "2026-01-01T00:00:00Z", "createdBy": "a",
                              "updatedAt": "2026-01-01T00:00:00Z", "updatedBy": "a"}]
                            """));

            client.listSubjects();

            var req = server.takeRequest();
            assertThat(req.getHeader("Authorization")).isEqualTo("Bearer cveil_test_token_12345");
            assertThat(req.getHeader("Accept")).isEqualTo("application/json");
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────

    private static MockResponse jsonResponse(String body) {
        return new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(body);
    }

    private static MockResponse emptyGraphResponse() {
        return jsonResponse(
                """
                {"nodes": [], "edges": [], "pageInfo": {"nextCursor": null, "hasMore": false, "totalNodes": 0, "totalEdges": 0}}
                """);
    }
}
