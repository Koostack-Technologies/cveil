package com.koostack.cveil.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.koostack.cveil.sdk.model.*;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests Jackson serialization/deserialization round-trips for all model records. Ensures records
 * survive JSON encoding and decoding without data loss.
 */
class ModelSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ─── Core Models ──────────────────────────────────────────────

    @Nested
    @DisplayName("MaskResult")
    class MaskResultTests {
        @Test
        void roundTrips() throws Exception {
            var json =
                    """
                    {
                      "data": {"email": "***", "name": "Alice"},
                      "needsFetch": [{"fieldPath": "phone", "policyNames": ["FIELD:User/phone:Marketing"]}]
                    }
                    """;
            var result = mapper.readValue(json, MaskResult.class);
            assertThat(result.data()).containsEntry("email", "***").containsEntry("name", "Alice");
            assertThat(result.needsFetch()).hasSize(1);
            assertThat(result.needsFetch().get(0).fieldPath()).isEqualTo("phone");
            assertThat(result.needsFetch().get(0).policyNames())
                    .containsExactly("FIELD:User/phone:Marketing");

            var backToJson = mapper.writeValueAsString(result);
            var again = mapper.readValue(backToJson, MaskResult.class);
            assertThat(again).isEqualTo(result);
        }

        @Test
        void handlesEmptyNeedsFetch() throws Exception {
            var json =
                    """
                    {"data": {"email": "test@example.com"}, "needsFetch": []}
                    """;
            var result = mapper.readValue(json, MaskResult.class);
            assertThat(result.needsFetch()).isEmpty();
        }
    }

    @Nested
    @DisplayName("EvaluateResult")
    class EvaluateResultTests {
        @Test
        void deserializesSnakeCaseFields() throws Exception {
            var json =
                    """
                    {
                      "field": "email",
                      "masking_type": "REDACT_ALL",
                      "consent_action": "DENY",
                      "needs_fetch": false,
                      "policy_names": ["FIELD:User/email:Marketing"]
                    }
                    """;
            var result = mapper.readValue(json, EvaluateResult.class);
            assertThat(result.field()).isEqualTo("email");
            assertThat(result.maskingType()).isEqualTo("REDACT_ALL");
            assertThat(result.consentAction()).isEqualTo("DENY");
            assertThat(result.needsFetch()).isFalse();
            assertThat(result.policyNames()).containsExactly("FIELD:User/email:Marketing");
        }
    }

    @Nested
    @DisplayName("InvalidationEffect")
    class InvalidationEffectTests {
        @Test
        void deserializesAllEffects() throws Exception {
            var json =
                    """
                    {"effect": "refresh_subgraph", "nodeType": "purpose", "nodeName": "Marketing"}
                    """;
            var result = mapper.readValue(json, InvalidationEffect.class);
            assertThat(result.effect()).isEqualTo("refresh_subgraph");
            assertThat(result.nodeType()).isEqualTo("purpose");
            assertThat(result.nodeName()).isEqualTo("Marketing");
        }

        @Test
        void handlesNullOptionalFields() throws Exception {
            var json = """
                    {"effect": "none"}
                    """;
            var result = mapper.readValue(json, InvalidationEffect.class);
            assertThat(result.effect()).isEqualTo("none");
            assertThat(result.nodeType()).isNull();
            assertThat(result.nodeName()).isNull();
        }
    }

    @Nested
    @DisplayName("SdkStatus")
    class SdkStatusTests {
        @Test
        void roundTrips() throws Exception {
            var json =
                    """
                    {
                      "initialized": true,
                      "connectionState": "Connected",
                      "graphNodes": 42,
                      "cachedDecisions": 10,
                      "decisionCacheCapacity": 10000
                    }
                    """;
            var status = mapper.readValue(json, SdkStatus.class);
            assertThat(status.initialized()).isTrue();
            assertThat(status.connectionState()).isEqualTo("Connected");
            assertThat(status.graphNodes()).isEqualTo(42);
            assertThat(status.cachedDecisions()).isEqualTo(10);
            assertThat(status.decisionCacheCapacity()).isEqualTo(10000);
        }
    }

    @Nested
    @DisplayName("DecisionEntry")
    class DecisionEntryTests {
        @Test
        void serializesWithNonNull() throws Exception {
            var entry = new DecisionEntry("FIELD:User/email:Marketing", "GRANT", null);
            var json = mapper.writeValueAsString(entry);
            assertThat(json).doesNotContain("expiresAt");

            var withExpiry = new DecisionEntry("CAT:PII:Marketing", "REVOKE", 1700000000000L);
            var json2 = mapper.writeValueAsString(withExpiry);
            assertThat(json2).contains("1700000000000");
        }
    }

    // ─── Graph Models ─────────────────────────────────────────────

    @Nested
    @DisplayName("ConsentGraphPage")
    class ConsentGraphPageTests {
        @Test
        void deserializesFullPage() throws Exception {
            var json =
                    """
                    {
                      "nodes": [
                        {"type": "entity", "name": "User", "properties": {}},
                        {"type": "field", "name": "User/email", "properties": {}}
                      ],
                      "edges": [
                        {"sourceType": "field", "sourceName": "User/email", "targetType": "category", "targetName": "PII", "properties": {}}
                      ],
                      "pageInfo": {
                        "nextCursor": "abc123",
                        "hasMore": true,
                        "totalNodes": 100,
                        "totalEdges": 50
                      }
                    }
                    """;
            var page = mapper.readValue(json, ConsentGraphPage.class);
            assertThat(page.nodes()).hasSize(2);
            assertThat(page.edges()).hasSize(1);
            assertThat(page.pageInfo().hasMore()).isTrue();
            assertThat(page.pageInfo().nextCursor()).isEqualTo("abc123");
        }

        @Test
        void handlesEmptyPage() throws Exception {
            var json =
                    """
                    {
                      "nodes": [],
                      "edges": [],
                      "pageInfo": {"nextCursor": null, "hasMore": false, "totalNodes": 0, "totalEdges": 0}
                    }
                    """;
            var page = mapper.readValue(json, ConsentGraphPage.class);
            assertThat(page.nodes()).isEmpty();
            assertThat(page.pageInfo().nextCursor()).isNull();
        }
    }

    // ─── Common Models ────────────────────────────────────────────

    @Nested
    @DisplayName("ApiErrorBody")
    class ApiErrorBodyTests {
        @Test
        void deserializesStructuredError() throws Exception {
            var json =
                    """
                    {
                      "requestId": "req-123",
                      "error": "validation_error",
                      "message": "subjectName is required",
                      "retryable": false,
                      "retryAfter": null
                    }
                    """;
            var body = mapper.readValue(json, ApiErrorBody.class);
            assertThat(body.requestId()).isEqualTo("req-123");
            assertThat(body.error()).isEqualTo("validation_error");
            assertThat(body.message()).isEqualTo("subjectName is required");
            assertThat(body.retryable()).isFalse();
            assertThat(body.retryAfter()).isNull();
        }

        @Test
        void ignoresUnknownFields() throws Exception {
            var json =
                    """
                    {"error": "oops", "message": "bad", "retryable": true, "extra": "ignored"}
                    """;
            var body = mapper.readValue(json, ApiErrorBody.class);
            assertThat(body.error()).isEqualTo("oops");
        }
    }

    // ─── Consent Models ───────────────────────────────────────────

    @Nested
    @DisplayName("ConsentScope")
    class ConsentScopeTests {
        @Test
        void serializesWithNonNull() throws Exception {
            var scope = new ConsentScope(null, "email", "User", "Marketing");
            var json = mapper.writeValueAsString(scope);
            assertThat(json).doesNotContain("category");
            assertThat(json).contains("email").contains("User").contains("Marketing");
        }

        @Test
        void roundTrips() throws Exception {
            var json =
                    """
                    {"category": "PII", "fieldName": "email", "entityName": "User", "purposeName": "Marketing"}
                    """;
            var scope = mapper.readValue(json, ConsentScope.class);
            assertThat(scope.category()).isEqualTo("PII");
            assertThat(scope.fieldName()).isEqualTo("email");
        }
    }

    @Nested
    @DisplayName("BatchConsentResponse")
    class BatchConsentResponseTests {
        @Test
        void deserializesWithErrors() throws Exception {
            var json =
                    """
                    {
                      "succeeded": 1,
                      "failed": 1,
                      "errors": [{"index": 1, "scope": {"purposeName": "Analytics"}, "message": "not found"}]
                    }
                    """;
            var result = mapper.readValue(json, BatchConsentResponse.class);
            assertThat(result.succeeded()).isEqualTo(1);
            assertThat(result.failed()).isEqualTo(1);
            assertThat(result.errors()).hasSize(1);
            assertThat(result.errors().get(0).index()).isEqualTo(1);
            assertThat(result.errors().get(0).message()).isEqualTo("not found");
        }
    }

    @Nested
    @DisplayName("EvaluateConsentResponse")
    class EvaluateConsentResponseTests {
        @Test
        void deserializesFullResponse() throws Exception {
            var json =
                    """
                    {
                      "subject": "user-5",
                      "granted": [{"purposeName": "Marketing", "fieldName": "email", "entityName": "User"}],
                      "denied": [],
                      "reasons": {},
                      "details": {
                        "email": {"effectiveStatus": "GRANTED", "resolvedBy": "FIELD", "expiresAt": null}
                      }
                    }
                    """;
            var result = mapper.readValue(json, EvaluateConsentResponse.class);
            assertThat(result.subject()).isEqualTo("user-5");
            assertThat(result.granted()).hasSize(1);
            assertThat(result.denied()).isEmpty();
            assertThat(result.details()).containsKey("email");
            assertThat(result.details().get("email").effectiveStatus()).isEqualTo("GRANTED");
        }
    }

    // ─── Subject Models ───────────────────────────────────────────

    @Nested
    @DisplayName("SubjectView")
    class SubjectViewTests {
        @Test
        void deserializes() throws Exception {
            var json =
                    """
                    {
                      "subjectId": 1,
                      "subjectName": "alice",
                      "description": "Test user",
                      "createdAt": "2026-01-01T00:00:00Z",
                      "createdBy": "admin",
                      "updatedAt": "2026-01-01T00:00:00Z",
                      "updatedBy": "admin"
                    }
                    """;
            var view = mapper.readValue(json, SubjectView.class);
            assertThat(view.subjectName()).isEqualTo("alice");
            assertThat(view.description()).isEqualTo("Test user");
        }
    }

    @Nested
    @DisplayName("ConsentLedgerEntry")
    class ConsentLedgerEntryTests {
        @Test
        void deserializesAll() throws Exception {
            var json =
                    """
                    {
                      "consentLedgerId": 1,
                      "subjectId": "user-7",
                      "policyId": 10,
                      "purposeName": "Marketing",
                      "policyType": "FIELD",
                      "fieldName": "email",
                      "entityName": "User",
                      "categoryName": null,
                      "action": "GRANT",
                      "actor": "user-7",
                      "ipAddress": null,
                      "userAgent": null,
                      "createdAt": "2026-01-01T00:00:00Z",
                      "expiresAt": null
                    }
                    """;
            var entry = mapper.readValue(json, ConsentLedgerEntry.class);
            assertThat(entry.action()).isEqualTo("GRANT");
            assertThat(entry.purposeName()).isEqualTo("Marketing");
            assertThat(entry.fieldName()).isEqualTo("email");
            assertThat(entry.categoryName()).isNull();
        }

        @Test
        void deserializesAsList() throws Exception {
            var json =
                    """
                    [
                      {"consentLedgerId": 1, "subjectId": "u", "policyId": 1, "purposeName": "M", "policyType": "FIELD", "fieldName": null, "entityName": null, "categoryName": null, "action": "GRANT", "actor": "a", "ipAddress": null, "userAgent": null, "createdAt": "2026-01-01T00:00:00Z", "expiresAt": null}
                    ]
                    """;
            List<ConsentLedgerEntry> entries =
                    mapper.readValue(json, new TypeReference<List<ConsentLedgerEntry>>() {});
            assertThat(entries).hasSize(1);
        }
    }

    @Nested
    @DisplayName("DsarDeletionReceipt")
    class DsarDeletionReceiptTests {
        @Test
        void deserializes() throws Exception {
            var json =
                    """
                    {
                      "subject": "alice",
                      "deletionRequestId": "del-001",
                      "ledgerEntriesRevoked": 5,
                      "cachedKeysDeleted": 3,
                      "status": "PENDING_SHRED",
                      "completedAt": "2026-01-01T00:00:00Z"
                    }
                    """;
            var receipt = mapper.readValue(json, DsarDeletionReceipt.class);
            assertThat(receipt.deletionRequestId()).isEqualTo("del-001");
            assertThat(receipt.status()).isEqualTo("PENDING_SHRED");
        }
    }

    // ─── Governance Models ────────────────────────────────────────

    @Nested
    @DisplayName("PurposeView")
    class PurposeViewTests {
        @Test
        void deserializesWithPoliciesAndRegulations() throws Exception {
            var json =
                    """
                    {
                      "purposeId": 1,
                      "purposeName": "Marketing",
                      "description": "Email marketing",
                      "regulations": [
                        {"regulationName": "GDPR", "lawfulBasis": "consent", "onGranted": "PLAINTEXT", "onDenied": "REDACT_ALL"}
                      ],
                      "policies": [
                        {"policyId": 10, "policyType": "FIELD", "category": null, "field": {"fieldId": 1, "fieldName": "email", "entityName": "User"}}
                      ]
                    }
                    """;
            var view = mapper.readValue(json, PurposeView.class);
            assertThat(view.purposeName()).isEqualTo("Marketing");
            assertThat(view.regulations()).hasSize(1);
            assertThat(view.regulations().get(0).lawfulBasis()).isEqualTo("consent");
            assertThat(view.policies()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("RegulationView")
    class RegulationViewTests {
        @Test
        void deserializes() throws Exception {
            var json =
                    """
                    {
                      "regulationId": 1,
                      "regulationName": "GDPR",
                      "description": null,
                      "activeJurisdictions": [{"jurisdictionId": 1, "jurisdictionName": "EU"}]
                    }
                    """;
            var view = mapper.readValue(json, RegulationView.class);
            assertThat(view.regulationName()).isEqualTo("GDPR");
            assertThat(view.activeJurisdictions()).hasSize(1);
            assertThat(view.activeJurisdictions().get(0).jurisdictionName()).isEqualTo("EU");
        }
    }

    // ─── Catalogue Models ─────────────────────────────────────────

    @Nested
    @DisplayName("FieldView")
    class FieldViewTests {
        @Test
        void deserializesWithEntityAndCategories() throws Exception {
            var json =
                    """
                    {
                      "fieldId": 1,
                      "fieldName": "email",
                      "description": null,
                      "entity": {"entityId": 1, "entityName": "User"},
                      "categories": [{"categoryId": 1, "categoryName": "PII"}]
                    }
                    """;
            var view = mapper.readValue(json, FieldView.class);
            assertThat(view.fieldName()).isEqualTo("email");
            assertThat(view.entity()).containsEntry("entityName", "User");
            assertThat(view.categories()).hasSize(1);
        }
    }

    // ─── Request Serialization ────────────────────────────────────

    @Nested
    @DisplayName("Request serialization")
    class RequestSerializationTests {
        @Test
        void grantRequestOmitsNullExpiry() throws Exception {
            var req =
                    new GrantRequest(
                            "user-1", new ConsentScope(null, "email", "User", "Marketing"), null);
            var json = mapper.writeValueAsString(req);
            assertThat(json).doesNotContain("expiresAt");
            assertThat(json).contains("user-1");
        }

        @Test
        void grantRequestIncludesExpiry() throws Exception {
            var req =
                    new GrantRequest(
                            "user-1",
                            new ConsentScope(null, "email", "User", "Marketing"),
                            "2027-01-01T00:00:00Z");
            var json = mapper.writeValueAsString(req);
            assertThat(json).contains("2027-01-01T00:00:00Z");
        }

        @Test
        void registerSubjectRequestOmitsNullDescription() throws Exception {
            var req = new RegisterSubjectRequest("bob", null);
            var json = mapper.writeValueAsString(req);
            assertThat(json).contains("bob");
            assertThat(json).doesNotContain("description");
        }

        @Test
        void batchGrantRequestSerializesCorrectly() throws Exception {
            var req =
                    new BatchGrantRequest(
                            "user-2",
                            List.of(
                                    new BatchConsentEntry(
                                            new ConsentScope(null, "email", "User", "Marketing"),
                                            null),
                                    new BatchConsentEntry(
                                            new ConsentScope(null, "phone", "User", "Marketing"),
                                            "2027-06-01T00:00:00Z")));
            var json = mapper.writeValueAsString(req);
            var roundTripped = mapper.readValue(json, BatchGrantRequest.class);
            assertThat(roundTripped.subject()).isEqualTo("user-2");
            assertThat(roundTripped.grants()).hasSize(2);
        }

        @Test
        void linkPurposeRegulationRequestSerializesOptionalFields() throws Exception {
            var req =
                    new LinkPurposeRegulationRequest(
                            "Marketing", "GDPR", "consent", "Plaintext", null);
            var json = mapper.writeValueAsString(req);
            assertThat(json).contains("consent");
            assertThat(json).doesNotContain("onDenied");
        }
    }
}
