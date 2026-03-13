package com.koostack.cveil.rest;

import static org.junit.jupiter.api.Assertions.*;

import com.koostack.cveil.core.models.BatchConsentEntry;
import com.koostack.cveil.core.models.BatchConsentResult;
import com.koostack.cveil.core.models.ConsentResponse;
import com.koostack.cveil.core.models.ConsentScope;
import com.koostack.cveil.core.models.DecisionDetail;
import com.koostack.cveil.core.models.TenantInfo;
import com.koostack.cveil.rest.dto.AdminDtos.AssignFieldCategoryRequest;
import com.koostack.cveil.rest.dto.AdminDtos.LinkPurposeRegulationRequest;
import com.koostack.cveil.rest.dto.AdminDtos.RegisterFieldRequest;
import com.koostack.cveil.rest.dto.AdminDtos.RegisterPurposeRequest;
import com.koostack.cveil.rest.dto.AdminDtos.RegisterRegulationRequest;
import com.koostack.cveil.rest.dto.CommonDtos.ErrorResponse;
import com.koostack.cveil.rest.dto.CommonDtos.HealthResponse;
import com.koostack.cveil.rest.dto.CommonDtos.MessageResponse;
import com.koostack.cveil.rest.dto.ConsentDtos.BatchConsentEntryDto;
import com.koostack.cveil.rest.dto.ConsentDtos.BatchConsentResponse;
import com.koostack.cveil.rest.dto.ConsentDtos.BatchGrantRequest;
import com.koostack.cveil.rest.dto.ConsentDtos.BatchItemErrorDto;
import com.koostack.cveil.rest.dto.ConsentDtos.BatchRevokeRequest;
import com.koostack.cveil.rest.dto.ConsentDtos.DecisionDetailDto;
import com.koostack.cveil.rest.dto.ConsentDtos.EvaluateRequest;
import com.koostack.cveil.rest.dto.ConsentDtos.EvaluateResponse;
import com.koostack.cveil.rest.dto.ConsentDtos.GrantRequest;
import com.koostack.cveil.rest.dto.ConsentDtos.RevokeRequest;
import com.koostack.cveil.rest.dto.ConsentScopeDto;
import com.koostack.cveil.rest.dto.PlatformDtos.CreateTenantRequest;
import com.koostack.cveil.rest.dto.PlatformDtos.TenantResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for all REST API DTO types — verifying that {@code fromModel()} and {@code toModel()}
 * mappings correctly round-trip between the REST representation and the framework-agnostic {@code
 * cveil-core} models.
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>{@link ConsentScopeDto} — toModel / fromModel round-trip
 *   <li>{@link ConsentDtos} — grant, revoke, batch, evaluate DTOs
 *   <li>{@link PlatformDtos} — tenant and API key DTOs
 *   <li>{@link AdminDtos} — field, category, entity, purpose, regulation DTOs
 *   <li>{@link CommonDtos} — health, error, and message response DTOs
 * </ul>
 */
@DisplayName("DTO Mapping Tests")
class DtoMappingTest {

    // =======================================================================
    // ConsentScopeDto
    // =======================================================================

    @Nested
    @DisplayName("ConsentScopeDto")
    class ConsentScopeDtoTests {

        @Test
        @DisplayName("toModel() converts all fields correctly")
        void toModel() {
            ConsentScopeDto dto = new ConsentScopeDto("PII", "email", "User", "marketing");
            ConsentScope scope = dto.toModel();

            assertEquals("PII", scope.category());
            assertEquals("email", scope.fieldName());
            assertEquals("User", scope.entityName());
            assertEquals("marketing", scope.purposeName());
        }

        @Test
        @DisplayName("fromModel() converts all fields correctly")
        void fromModel() {
            ConsentScope scope = new ConsentScope("HEALTH", "diagnosis", "Patient", "treatment");
            ConsentScopeDto dto = ConsentScopeDto.fromModel(scope);

            assertEquals("HEALTH", dto.category());
            assertEquals("diagnosis", dto.fieldName());
            assertEquals("Patient", dto.entityName());
            assertEquals("treatment", dto.purposeName());
        }

        @Test
        @DisplayName("round-trip: toModel → fromModel preserves all fields")
        void roundTrip() {
            ConsentScopeDto original = new ConsentScopeDto("PII", "ssn", "Employee", "payroll");
            ConsentScopeDto roundTripped = ConsentScopeDto.fromModel(original.toModel());

            assertEquals(original.category(), roundTripped.category());
            assertEquals(original.fieldName(), roundTripped.fieldName());
            assertEquals(original.entityName(), roundTripped.entityName());
            assertEquals(original.purposeName(), roundTripped.purposeName());
        }

        @Test
        @DisplayName("round-trip: fromModel → toModel preserves all fields")
        void reverseRoundTrip() {
            ConsentScope original =
                    new ConsentScope("FINANCIAL", "account_number", "Account", "audit");
            ConsentScope roundTripped = ConsentScopeDto.fromModel(original).toModel();

            assertEquals(original.category(), roundTripped.category());
            assertEquals(original.fieldName(), roundTripped.fieldName());
            assertEquals(original.entityName(), roundTripped.entityName());
            assertEquals(original.purposeName(), roundTripped.purposeName());
        }

        @Test
        @DisplayName("handles null entityName (entity-less field)")
        void nullEntityName() {
            ConsentScopeDto dto = new ConsentScopeDto("PII", "email", null, "marketing");
            ConsentScope scope = dto.toModel();

            assertEquals("PII", scope.category());
            assertEquals("email", scope.fieldName());
            assertEquals(ConsentScope.WILDCARD, scope.entityName());
            assertEquals("marketing", scope.purposeName());

            ConsentScopeDto backToDto = ConsentScopeDto.fromModel(scope);
            assertEquals(ConsentScope.WILDCARD, backToDto.entityName());
        }

        @Test
        @DisplayName("handles null purposeName")
        void nullPurposeName() {
            ConsentScopeDto dto = new ConsentScopeDto("PII", "email", "User", null);
            ConsentScope scope = dto.toModel();

            assertEquals(ConsentScope.WILDCARD, scope.purposeName());

            ConsentScopeDto backToDto = ConsentScopeDto.fromModel(scope);
            assertEquals(ConsentScope.WILDCARD, backToDto.purposeName());
        }

        @Test
        @DisplayName("handles all-null optional fields")
        void allNullOptionals() {
            ConsentScopeDto dto = new ConsentScopeDto("PII", "email", null, null);
            ConsentScope scope = dto.toModel();

            assertEquals("PII", scope.category());
            assertEquals("email", scope.fieldName());
            assertEquals(ConsentScope.WILDCARD, scope.entityName());
            assertEquals(ConsentScope.WILDCARD, scope.purposeName());
        }

        @Test
        @DisplayName("record equality for identical scopes")
        void recordEquality() {
            ConsentScopeDto a = new ConsentScopeDto("PII", "email", "User", "marketing");
            ConsentScopeDto b = new ConsentScopeDto("PII", "email", "User", "marketing");
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("record inequality for different scopes")
        void recordInequality() {
            ConsentScopeDto a = new ConsentScopeDto("PII", "email", "User", "marketing");
            ConsentScopeDto b = new ConsentScopeDto("PII", "phone", "User", "marketing");
            assertNotEquals(a, b);
        }
    }

    // =======================================================================
    // ConsentDtos — Grant / Revoke (single)
    // =======================================================================

    @Nested
    @DisplayName("ConsentDtos — GrantRequest / RevokeRequest")
    class GrantRevokeTests {

        @Test
        @DisplayName("GrantRequest holds all fields")
        void grantRequestFields() {
            ConsentScopeDto scope = new ConsentScopeDto("PII", "email", "User", "marketing");
            Instant expiresAt = Instant.now().plus(30, ChronoUnit.DAYS);
            GrantRequest req = new GrantRequest("user-123", scope, expiresAt);

            assertEquals("user-123", req.subject());
            assertEquals(scope, req.scope());
            assertEquals(expiresAt, req.expiresAt());
        }

        @Test
        @DisplayName("GrantRequest with null expiresAt (no expiration)")
        void grantRequestNullExpiry() {
            ConsentScopeDto scope = new ConsentScopeDto("PII", "email", "User", null);
            GrantRequest req = new GrantRequest("user-456", scope, null);

            assertNull(req.expiresAt());
        }

        @Test
        @DisplayName("RevokeRequest holds all fields")
        void revokeRequestFields() {
            ConsentScopeDto scope = new ConsentScopeDto("PII", "email", "User", "analytics");
            RevokeRequest req = new RevokeRequest("user-789", scope);

            assertEquals("user-789", req.subject());
            assertEquals(scope, req.scope());
        }
    }

    // =======================================================================
    // ConsentDtos — Batch
    // =======================================================================

    @Nested
    @DisplayName("ConsentDtos — Batch")
    class BatchTests {

        @Test
        @DisplayName("BatchConsentEntryDto.toModel() converts correctly")
        void batchEntryToModel() {
            ConsentScopeDto scope = new ConsentScopeDto("PII", "email", "User", "marketing");
            Instant expiresAt = Instant.now().plus(90, ChronoUnit.DAYS);
            BatchConsentEntryDto dto = new BatchConsentEntryDto(scope, expiresAt);

            BatchConsentEntry model = dto.toModel();

            assertEquals("PII", model.scope().category());
            assertEquals("email", model.scope().fieldName());
            assertEquals("User", model.scope().entityName());
            assertEquals("marketing", model.scope().purposeName());
            assertEquals(expiresAt, model.expiresAt());
        }

        @Test
        @DisplayName("BatchConsentEntryDto.toModel() with null expiresAt")
        void batchEntryToModelNullExpiry() {
            ConsentScopeDto scope = new ConsentScopeDto("PII", "ssn", null, null);
            BatchConsentEntryDto dto = new BatchConsentEntryDto(scope, null);

            BatchConsentEntry model = dto.toModel();
            assertNull(model.expiresAt());
        }

        @Test
        @DisplayName("BatchGrantRequest holds subject and entries")
        void batchGrantRequest() {
            List<BatchConsentEntryDto> entries =
                    List.of(
                            new BatchConsentEntryDto(
                                    new ConsentScopeDto("PII", "email", "User", null), null),
                            new BatchConsentEntryDto(
                                    new ConsentScopeDto("PII", "phone", "User", null),
                                    Instant.now().plus(30, ChronoUnit.DAYS)));
            BatchGrantRequest req = new BatchGrantRequest("user-100", entries);

            assertEquals("user-100", req.subject());
            assertEquals(2, req.grants().size());
        }

        @Test
        @DisplayName("BatchRevokeRequest holds subject and entries")
        void batchRevokeRequest() {
            List<BatchConsentEntryDto> entries =
                    List.of(
                            new BatchConsentEntryDto(
                                    new ConsentScopeDto("PII", "email", "User", null), null));
            BatchRevokeRequest req = new BatchRevokeRequest("user-200", entries);

            assertEquals("user-200", req.subject());
            assertEquals(1, req.revokes().size());
        }

        @Test
        @DisplayName("BatchConsentResponse.fromModel() maps success result")
        void batchResponseFromModelSuccess() {
            BatchConsentResult model = new BatchConsentResult(3, 0, List.of());
            BatchConsentResponse dto = BatchConsentResponse.fromModel(model);

            assertEquals(3, dto.succeeded());
            assertEquals(0, dto.failed());
            assertTrue(dto.errors().isEmpty());
        }

        @Test
        @DisplayName("BatchConsentResponse.fromModel() maps partial failure")
        void batchResponseFromModelPartialFailure() {
            ConsentScope failedScope = new ConsentScope("PII", "email", "User", null);
            BatchConsentResult.BatchItemError itemError =
                    new BatchConsentResult.BatchItemError(1, failedScope, "Field not registered");
            BatchConsentResult model = new BatchConsentResult(2, 1, List.of(itemError));

            BatchConsentResponse dto = BatchConsentResponse.fromModel(model);

            assertEquals(2, dto.succeeded());
            assertEquals(1, dto.failed());
            assertEquals(1, dto.errors().size());

            BatchItemErrorDto errorDto = dto.errors().getFirst();
            assertEquals(1, errorDto.index());
            assertEquals("email", errorDto.scope().fieldName());
            assertEquals("Field not registered", errorDto.message());
        }

        @Test
        @DisplayName("BatchConsentResponse.fromModel() maps all-failure result")
        void batchResponseFromModelAllFailure() {
            ConsentScope scope1 = new ConsentScope("PII", "email", "User", null);
            ConsentScope scope2 = new ConsentScope("PII", "phone", "User", null);
            BatchConsentResult model =
                    new BatchConsentResult(
                            0,
                            2,
                            List.of(
                                    new BatchConsentResult.BatchItemError(0, scope1, "Error 1"),
                                    new BatchConsentResult.BatchItemError(1, scope2, "Error 2")));

            BatchConsentResponse dto = BatchConsentResponse.fromModel(model);

            assertEquals(0, dto.succeeded());
            assertEquals(2, dto.failed());
            assertEquals(2, dto.errors().size());
            assertEquals(0, dto.errors().get(0).index());
            assertEquals(1, dto.errors().get(1).index());
        }
    }

    // =======================================================================
    // ConsentDtos — Evaluate
    // =======================================================================

    @Nested
    @DisplayName("ConsentDtos — Evaluate")
    class EvaluateTests {

        @Test
        @DisplayName("EvaluateRequest holds scopes")
        void evaluateRequest() {
            Set<ConsentScopeDto> scopes =
                    Set.of(
                            new ConsentScopeDto("PII", "email", "User", null),
                            new ConsentScopeDto("PII", "phone", "User", null));
            EvaluateRequest req = new EvaluateRequest(scopes);

            assertEquals(2, req.scopes().size());
        }

        @Test
        @DisplayName("EvaluateResponse.fromModel() maps granted and denied scopes")
        void evaluateResponseFromModel() {
            ConsentScope grantedScope = new ConsentScope("PII", "email", "User", null);
            ConsentScope deniedScope = new ConsentScope("PII", "ssn", "User", null);

            DecisionDetail grantedDetail =
                    new DecisionDetail(grantedScope, "GRANTED", "FIELD", null);
            DecisionDetail deniedDetail =
                    new DecisionDetail(
                            deniedScope,
                            "REVOKED",
                            "FIELD",
                            Instant.now().plus(30, ChronoUnit.DAYS));

            ConsentResponse model =
                    new ConsentResponse(
                            "user-400",
                            Set.of(grantedScope),
                            Set.of(deniedScope),
                            Map.of("ssn", "Consent revoked for SSN"),
                            Map.of(grantedScope, grantedDetail, deniedScope, deniedDetail));

            EvaluateResponse dto = EvaluateResponse.fromModel(model);

            assertEquals("user-400", dto.subject());
            assertEquals(1, dto.granted().size());
            assertEquals(1, dto.denied().size());

            // Check granted contains the email scope
            assertTrue(dto.granted().stream().anyMatch(s -> "email".equals(s.fieldName())));

            // Check denied contains the ssn scope
            assertTrue(dto.denied().stream().anyMatch(s -> "ssn".equals(s.fieldName())));

            // Check reasons
            assertEquals("Consent revoked for SSN", dto.reasons().get("ssn"));

            // Check details (keyed by scope string)
            assertEquals(2, dto.details().size());
        }

        @Test
        @DisplayName("EvaluateResponse.fromModel() maps empty granted/denied")
        void evaluateResponseEmpty() {
            ConsentResponse model =
                    new ConsentResponse("user-500", Set.of(), Set.of(), Map.of(), Map.of());

            EvaluateResponse dto = EvaluateResponse.fromModel(model);

            assertEquals("user-500", dto.subject());
            assertTrue(dto.granted().isEmpty());
            assertTrue(dto.denied().isEmpty());
            assertTrue(dto.reasons().isEmpty());
            assertTrue(dto.details().isEmpty());
        }

        @Test
        @DisplayName("DecisionDetailDto.fromModel() maps all fields")
        void decisionDetailFromModel() {
            Instant expiresAt = Instant.now().plus(60, ChronoUnit.DAYS);
            ConsentScope scope = new ConsentScope("PII", "email", "User", null);
            DecisionDetail model = new DecisionDetail(scope, "GRANTED", "CATEGORY", expiresAt);

            DecisionDetailDto dto = DecisionDetailDto.fromModel(model);

            assertEquals("GRANTED", dto.effectiveStatus());
            assertEquals("CATEGORY", dto.resolvedBy());
            assertEquals(expiresAt, dto.expiresAt());
        }

        @Test
        @DisplayName("DecisionDetailDto.fromModel() handles null expiresAt")
        void decisionDetailNullExpiry() {
            ConsentScope scope = new ConsentScope("PII", "phone", "User", null);
            DecisionDetail model = new DecisionDetail(scope, "REVOKED", "FIELD", null);
            DecisionDetailDto dto = DecisionDetailDto.fromModel(model);

            assertEquals("REVOKED", dto.effectiveStatus());
            assertEquals("FIELD", dto.resolvedBy());
            assertNull(dto.expiresAt());
        }

        @Test
        @DisplayName("DecisionDetailDto.fromModel() handles null effectiveStatus (no consent)")
        void decisionDetailNullStatus() {
            ConsentScope scope = new ConsentScope("PII", "ssn", "User", null);
            DecisionDetail model = new DecisionDetail(scope, null, "NONE", null);
            DecisionDetailDto dto = DecisionDetailDto.fromModel(model);

            assertNull(dto.effectiveStatus());
            assertEquals("NONE", dto.resolvedBy());
        }
    }

    // =======================================================================
    // PlatformDtos — Tenant
    // =======================================================================

    @Nested
    @DisplayName("PlatformDtos — Tenant")
    class TenantDtoTests {

        @Test
        @DisplayName("CreateTenantRequest holds all fields")
        void createTenantRequest() {
            CreateTenantRequest req =
                    new CreateTenantRequest("acme_corp", "Acme Corp", "pro", null);

            assertEquals("acme_corp", req.tenantId());
            assertEquals("Acme Corp", req.displayName());
            assertEquals("pro", req.plan());
        }

        @Test
        @DisplayName("TenantResponse.fromModel() maps all fields")
        void tenantResponseFromModel() {
            Instant createdAt = Instant.now().minus(7, ChronoUnit.DAYS);
            TenantInfo model =
                    new TenantInfo(
                            "acme_corp", "Acme Corporation", "enterprise", "ACTIVE", createdAt);

            TenantResponse dto = TenantResponse.fromModel(model);

            assertEquals("acme_corp", dto.tenantId());
            assertEquals("Acme Corporation", dto.displayName());
            assertEquals("enterprise", dto.plan());
            assertEquals("ACTIVE", dto.status());
            assertEquals(createdAt, dto.createdAt());
        }

        @Test
        @DisplayName("TenantResponse.fromModel() handles free plan defaults")
        void tenantResponseFreePlan() {
            TenantInfo model =
                    new TenantInfo("small_co", "Small Co", "free", "ACTIVE", Instant.now());

            TenantResponse dto = TenantResponse.fromModel(model);

            assertEquals("free", dto.plan());
        }

        @Test
        @DisplayName("TenantResponse preserves SUSPENDED status")
        void tenantResponseSuspended() {
            TenantInfo model =
                    new TenantInfo("bad_co", "Bad Co", "free", "SUSPENDED", Instant.now());

            TenantResponse dto = TenantResponse.fromModel(model);
            assertEquals("SUSPENDED", dto.status());
        }
    }

    // =======================================================================
    // AdminDtos — Field
    // =======================================================================

    @Nested
    @DisplayName("AdminDtos — Field")
    class FieldDtoTests {

        @Test
        @DisplayName("RegisterFieldRequest holds all fields")
        void registerFieldRequest() {
            RegisterFieldRequest req =
                    new RegisterFieldRequest("email", "User", "User email address", null);

            assertEquals("email", req.fieldName());
            assertEquals("User", req.entityName());
            assertEquals("User email address", req.description());
        }

        @Test
        @DisplayName("RegisterFieldRequest with null optionals")
        void registerFieldRequestNullOptionals() {
            RegisterFieldRequest req = new RegisterFieldRequest("email", null, null, null);

            assertNull(req.entityName());
            assertNull(req.description());
        }
    }

    // =======================================================================
    // AdminDtos — Category
    // =======================================================================

    @Nested
    @DisplayName("AdminDtos — Category")
    class CategoryDtoTests {}

    // =======================================================================
    // AdminDtos — Purpose
    // =======================================================================

    @Nested
    @DisplayName("AdminDtos — Purpose")
    class PurposeDtoTests {

        @Test
        @DisplayName("RegisterPurposeRequest holds all fields")
        void registerPurposeRequest() {
            RegisterPurposeRequest req =
                    new RegisterPurposeRequest("marketing", "Email marketing campaigns");

            assertEquals("marketing", req.purposeName());
            assertEquals("Email marketing campaigns", req.description());
        }
    }

    // =======================================================================
    // AdminDtos — Regulation
    // =======================================================================

    @Nested
    @DisplayName("AdminDtos — Regulation")
    class RegulationDtoTests {

        @Test
        @DisplayName("RegisterRegulationRequest holds all fields")
        void registerRegulationRequest() {
            RegisterRegulationRequest req =
                    new RegisterRegulationRequest("GDPR", "EU data protection regulation");

            assertEquals("GDPR", req.regulationName());
            assertEquals("EU data protection regulation", req.description());
        }

        @Test
        @DisplayName("RegisterRegulationRequest with null optionals")
        void registerRegulationRequestNullOptionals() {
            RegisterRegulationRequest req = new RegisterRegulationRequest("CCPA", null);

            assertNull(req.description());
        }
    }

    // =======================================================================
    // AdminDtos — Links
    // =======================================================================

    @Nested
    @DisplayName("AdminDtos — Link DTOs")
    class LinkDtoTests {

        @Test
        @DisplayName("AssignFieldCategoryRequest holds all fields")
        void assignFieldCategoryRequest() {
            AssignFieldCategoryRequest req =
                    new AssignFieldCategoryRequest(
                            "email", "User", "PII", "Personally Identifiable Information");

            assertEquals("email", req.fieldName());
            assertEquals("User", req.entityName());
            assertEquals("PII", req.categoryName());
            assertEquals("Personally Identifiable Information", req.categoryDescription());
        }

        @Test
        @DisplayName("AssignFieldCategoryRequest with null entityName")
        void assignFieldCategoryNullEntity() {
            AssignFieldCategoryRequest req =
                    new AssignFieldCategoryRequest("api_token", null, "CREDENTIALS", null);

            assertNull(req.entityName());
        }

        @Test
        @DisplayName("LinkPurposeRegulationRequest holds all fields")
        void linkPurposeRegulationRequest() {
            LinkPurposeRegulationRequest req =
                    new LinkPurposeRegulationRequest("marketing", "GDPR", "consent", null, null);

            assertEquals("marketing", req.purposeName());
            assertEquals("GDPR", req.regulationName());
            assertEquals("consent", req.lawfulBasis());
        }
    }

    // =======================================================================
    // CommonDtos — Health
    // =======================================================================

    @Nested
    @DisplayName("CommonDtos — HealthResponse")
    class HealthResponseTests {

        @Test
        @DisplayName("HealthResponse.up() has status UP and no errors")
        void upResponse() {
            HealthResponse resp = HealthResponse.up();

            assertEquals("UP", resp.status());
            assertNotNull(resp.errors());
            assertTrue(resp.errors().isEmpty());
        }

        @Test
        @DisplayName("HealthResponse.down(List) has status DOWN and error list")
        void downResponseWithList() {
            HealthResponse resp = HealthResponse.down(List.of("Redis offline", "DB unreachable"));

            assertEquals("DOWN", resp.status());
            assertEquals(2, resp.errors().size());
            assertTrue(resp.errors().contains("Redis offline"));
            assertTrue(resp.errors().contains("DB unreachable"));
        }

        @Test
        @DisplayName("HealthResponse.down(String) has status DOWN and single error")
        void downResponseWithString() {
            HealthResponse resp = HealthResponse.down("Plugin health check failed");

            assertEquals("DOWN", resp.status());
            assertEquals(1, resp.errors().size());
            assertEquals("Plugin health check failed", resp.errors().getFirst());
        }

        @Test
        @DisplayName("status constants are correct")
        void statusConstants() {
            assertEquals("UP", HealthResponse.STATUS_UP);
            assertEquals("DOWN", HealthResponse.STATUS_DOWN);
        }

        @Test
        @DisplayName("down(List) creates an unmodifiable copy")
        void downListIsUnmodifiable() {
            HealthResponse resp = HealthResponse.down(List.of("Error"));
            assertThrows(
                    UnsupportedOperationException.class, () -> resp.errors().add("Another error"));
        }
    }

    // =======================================================================
    // CommonDtos — ErrorResponse
    // =======================================================================

    @Nested
    @DisplayName("CommonDtos — ErrorResponse")
    class ErrorResponseTests {

        @Test
        @DisplayName("ErrorResponse.of() creates non-retryable error")
        void ofNonRetryable() {
            ErrorResponse resp = ErrorResponse.of("validation_error", "Bad input");

            assertNull(resp.requestId());
            assertEquals("validation_error", resp.error());
            assertEquals("Bad input", resp.message());
            assertFalse(resp.retryable());
            assertNull(resp.retryAfter());
        }

        @Test
        @DisplayName("ErrorResponse.retryable(error, message) creates retryable without retryAfter")
        void retryableWithoutRetryAfter() {
            ErrorResponse resp = ErrorResponse.retryable("service_unavailable", "Try later");

            assertNull(resp.requestId());
            assertEquals("service_unavailable", resp.error());
            assertEquals("Try later", resp.message());
            assertTrue(resp.retryable());
            assertNull(resp.retryAfter());
        }

        @Test
        @DisplayName(
                "ErrorResponse.retryable(error, message, retryAfter) creates retryable with"
                        + " retryAfter")
        void retryableWithRetryAfter() {
            ErrorResponse resp = ErrorResponse.retryable("rate_limit_exceeded", "Too fast", 42L);

            assertNull(resp.requestId());
            assertEquals("rate_limit_exceeded", resp.error());
            assertEquals("Too fast", resp.message());
            assertTrue(resp.retryable());
            assertNotNull(resp.retryAfter());
            assertEquals(42L, resp.retryAfter());
        }

        @Test
        @DisplayName("record equality for identical errors")
        void recordEquality() {
            ErrorResponse a = ErrorResponse.of("error", "msg");
            ErrorResponse b = ErrorResponse.of("error", "msg");
            assertEquals(a, b);
        }

        @Test
        @DisplayName("record inequality for different errors")
        void recordInequality() {
            ErrorResponse a = ErrorResponse.of("error_a", "msg");
            ErrorResponse b = ErrorResponse.of("error_b", "msg");
            assertNotEquals(a, b);
        }

        // ---------------------------------------------------------------
        // requestId field & withRequestId()
        // ---------------------------------------------------------------

        @Test
        @DisplayName("factory methods set requestId to null by default")
        void factoryMethodsRequestIdNull() {
            assertNull(ErrorResponse.of("e", "m").requestId());
            assertNull(ErrorResponse.retryable("e", "m").requestId());
            assertNull(ErrorResponse.retryable("e", "m", 10L).requestId());
        }

        @Test
        @DisplayName("withRequestId() returns copy with requestId set")
        void withRequestIdSetsField() {
            ErrorResponse original = ErrorResponse.of("err", "msg");
            ErrorResponse withId = original.withRequestId("req-123");

            assertEquals("req-123", withId.requestId());
            assertEquals("err", withId.error());
            assertEquals("msg", withId.message());
            assertFalse(withId.retryable());
            assertNull(withId.retryAfter());
        }

        @Test
        @DisplayName("withRequestId() preserves retryable and retryAfter")
        void withRequestIdPreservesRetryable() {
            ErrorResponse original =
                    ErrorResponse.retryable("rate_limit_exceeded", "Too fast", 42L);
            ErrorResponse withId = original.withRequestId("req-456");

            assertEquals("req-456", withId.requestId());
            assertEquals("rate_limit_exceeded", withId.error());
            assertEquals("Too fast", withId.message());
            assertTrue(withId.retryable());
            assertEquals(42L, withId.retryAfter());
        }

        @Test
        @DisplayName("withRequestId(null) sets requestId to null")
        void withRequestIdNull() {
            ErrorResponse original = ErrorResponse.of("e", "m").withRequestId("id-1");
            ErrorResponse cleared = original.withRequestId(null);

            assertNull(cleared.requestId());
            assertEquals("e", cleared.error());
        }

        @Test
        @DisplayName("withRequestId() does not mutate the original")
        void withRequestIdImmutable() {
            ErrorResponse original = ErrorResponse.of("e", "m");
            original.withRequestId("id-99");

            assertNull(original.requestId(), "Original should not be mutated");
        }

        @Test
        @DisplayName("record equality considers requestId")
        void equalityIncludesRequestId() {
            ErrorResponse a = ErrorResponse.of("e", "m").withRequestId("id-1");
            ErrorResponse b = ErrorResponse.of("e", "m").withRequestId("id-1");
            ErrorResponse c = ErrorResponse.of("e", "m").withRequestId("id-2");

            assertEquals(a, b);
            assertNotEquals(a, c);
        }

        @Test
        @DisplayName("withRequestId() on already-populated requestId overwrites")
        void withRequestIdOverwrites() {
            ErrorResponse first = ErrorResponse.of("e", "m").withRequestId("old");
            ErrorResponse second = first.withRequestId("new");

            assertEquals("new", second.requestId());
        }

        @Test
        @DisplayName("direct construction with all fields works correctly")
        void directConstruction() {
            ErrorResponse resp =
                    new ErrorResponse("req-direct", "test_error", "Test message", true, 99L);

            assertEquals("req-direct", resp.requestId());
            assertEquals("test_error", resp.error());
            assertEquals("Test message", resp.message());
            assertTrue(resp.retryable());
            assertEquals(99L, resp.retryAfter());
        }
    }

    // =======================================================================
    // CommonDtos — MessageResponse
    // =======================================================================

    @Nested
    @DisplayName("CommonDtos — MessageResponse")
    class MessageResponseTests {

        @Test
        @DisplayName("MessageResponse.of() creates message response")
        void ofCreatesResponse() {
            MessageResponse resp = MessageResponse.of("Tenant 'acme_corp' deleted");

            assertEquals("Tenant 'acme_corp' deleted", resp.message());
        }

        @Test
        @DisplayName("MessageResponse constructor creates message response")
        void constructorCreatesResponse() {
            MessageResponse resp = new MessageResponse("Consent granted");
            assertEquals("Consent granted", resp.message());
        }

        @Test
        @DisplayName("record equality for identical messages")
        void recordEquality() {
            MessageResponse a = MessageResponse.of("Success");
            MessageResponse b = MessageResponse.of("Success");
            assertEquals(a, b);
        }

        @Test
        @DisplayName("record inequality for different messages")
        void recordInequality() {
            MessageResponse a = MessageResponse.of("Success");
            MessageResponse b = MessageResponse.of("Failure");
            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("handles null message without exception")
        void nullMessage() {
            assertDoesNotThrow(() -> MessageResponse.of(null));
        }
    }
}
