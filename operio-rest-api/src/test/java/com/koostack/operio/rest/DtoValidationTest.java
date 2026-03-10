package com.koostack.operio.rest;

import static org.junit.jupiter.api.Assertions.*;

import com.koostack.operio.rest.dto.AdminDtos.AssignFieldCategoryRequest;
import com.koostack.operio.rest.dto.AdminDtos.LinkPurposeRegulationRequest;
import com.koostack.operio.rest.dto.AdminDtos.RegisterFieldRequest;
import com.koostack.operio.rest.dto.AdminDtos.RegisterPurposeRequest;
import com.koostack.operio.rest.dto.AdminDtos.RegisterRegulationRequest;
import com.koostack.operio.rest.dto.ConsentDtos.BatchConsentEntryDto;
import com.koostack.operio.rest.dto.ConsentDtos.BatchGrantRequest;
import com.koostack.operio.rest.dto.ConsentDtos.BatchRevokeRequest;
import com.koostack.operio.rest.dto.ConsentDtos.EvaluateRequest;
import com.koostack.operio.rest.dto.ConsentDtos.GrantRequest;
import com.koostack.operio.rest.dto.ConsentDtos.RevokeRequest;
import com.koostack.operio.rest.dto.ConsentScopeDto;
import com.koostack.operio.rest.dto.PlatformDtos.CreateTenantRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for Bean Validation annotations on all request DTOs.
 *
 * <p>Uses the Jakarta Validation API directly (no Quarkus container needed) to verify that every
 * request DTO correctly rejects invalid inputs and accepts valid ones.
 */
@DisplayName("DTO Bean Validation")
class DtoValidationTest {

    private static Validator validator;

    @BeforeAll
    static void initValidator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static <T> Set<String> violationMessages(Set<ConstraintViolation<T>> violations) {
        return violations.stream().map(ConstraintViolation::getMessage).collect(Collectors.toSet());
    }

    private static <T> Set<String> violatedProperties(Set<ConstraintViolation<T>> violations) {
        return violations.stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }

    private static ConsentScopeDto validScope() {
        return new ConsentScopeDto("PII", "email", "User", "marketing");
    }

    private static String stringOfLength(int len) {
        return "x".repeat(len);
    }

    // =======================================================================
    // ConsentScopeDto
    // =======================================================================

    @Nested
    @DisplayName("ConsentScopeDto")
    class ConsentScopeDtoValidation {

        @Test
        void validScope_noViolations() {
            var scope = new ConsentScopeDto("PII", "email", "User", "marketing");
            assertTrue(validator.validate(scope).isEmpty());
        }

        @Test
        void minimalScope_noViolations() {
            // entityName and purposeName are optional
            var scope = new ConsentScopeDto("PII", "email", null, null);
            assertTrue(validator.validate(scope).isEmpty());
        }

        @Test
        void nullCategory_violation() {
            var scope = new ConsentScopeDto(null, "email", null, null);
            var violations = validator.validate(scope);
            assertFalse(violations.isEmpty());
            assertTrue(
                    violationMessages(violations).stream().anyMatch(m -> m.contains("category")));
        }

        @Test
        void blankCategory_violation() {
            var scope = new ConsentScopeDto("  ", "email", null, null);
            var violations = validator.validate(scope);
            assertFalse(violations.isEmpty());
        }

        @Test
        void emptyCategory_violation() {
            var scope = new ConsentScopeDto("", "email", null, null);
            assertFalse(validator.validate(scope).isEmpty());
        }

        @Test
        void nullFieldName_violation() {
            var scope = new ConsentScopeDto("PII", null, null, null);
            var violations = validator.validate(scope);
            assertFalse(violations.isEmpty());
            assertTrue(
                    violationMessages(violations).stream().anyMatch(m -> m.contains("fieldName")));
        }

        @Test
        void blankFieldName_violation() {
            var scope = new ConsentScopeDto("PII", "", null, null);
            assertFalse(validator.validate(scope).isEmpty());
        }

        @Test
        void categoryTooLong_violation() {
            var scope = new ConsentScopeDto(stringOfLength(256), "email", null, null);
            var violations = validator.validate(scope);
            assertFalse(violations.isEmpty());
            assertTrue(violationMessages(violations).stream().anyMatch(m -> m.contains("255")));
        }

        @Test
        void fieldNameTooLong_violation() {
            var scope = new ConsentScopeDto("PII", stringOfLength(256), null, null);
            assertFalse(validator.validate(scope).isEmpty());
        }

        @Test
        void entityNameTooLong_violation() {
            var scope = new ConsentScopeDto("PII", "email", stringOfLength(256), null);
            assertFalse(validator.validate(scope).isEmpty());
        }

        @Test
        void purposeNameTooLong_violation() {
            var scope = new ConsentScopeDto("PII", "email", null, stringOfLength(256));
            assertFalse(validator.validate(scope).isEmpty());
        }

        @Test
        void maxLengthValues_noViolations() {
            var scope =
                    new ConsentScopeDto(
                            stringOfLength(255), stringOfLength(255),
                            stringOfLength(255), stringOfLength(255));
            assertTrue(validator.validate(scope).isEmpty());
        }
    }

    // =======================================================================
    // GrantRequest
    // =======================================================================

    @Nested
    @DisplayName("GrantRequest")
    class GrantRequestValidation {

        @Test
        void valid_noViolations() {
            var req = new GrantRequest("user-1", validScope(), null);
            assertTrue(validator.validate(req).isEmpty());
        }

        @Test
        void validWithExpiration_noViolations() {
            var req = new GrantRequest("user-1", validScope(), Instant.now().plusSeconds(3600));
            assertTrue(validator.validate(req).isEmpty());
        }

        @Test
        void nullSubject_violation() {
            var req = new GrantRequest(null, validScope(), null);
            var violations = validator.validate(req);
            assertFalse(violations.isEmpty());
            assertTrue(violationMessages(violations).stream().anyMatch(m -> m.contains("subject")));
        }

        @Test
        void blankSubject_violation() {
            var req = new GrantRequest("  ", validScope(), null);
            assertFalse(validator.validate(req).isEmpty());
        }

        @Test
        void subjectTooLong_violation() {
            var req = new GrantRequest(stringOfLength(513), validScope(), null);
            assertFalse(validator.validate(req).isEmpty());
        }

        @Test
        void nullScope_violation() {
            var req = new GrantRequest("user-1", null, null);
            var violations = validator.validate(req);
            assertFalse(violations.isEmpty());
            assertTrue(violationMessages(violations).stream().anyMatch(m -> m.contains("scope")));
        }

        @Test
        void invalidNestedScope_cascadesValidation() {
            // scope with blank category should cascade @Valid
            var badScope = new ConsentScopeDto("", "email", null, null);
            var req = new GrantRequest("user-1", badScope, null);
            var violations = validator.validate(req);
            assertFalse(
                    violations.isEmpty(), "Should cascade validation into nested ConsentScopeDto");
        }
    }

    // =======================================================================
    // RevokeRequest
    // =======================================================================

    @Nested
    @DisplayName("RevokeRequest")
    class RevokeRequestValidation {

        @Test
        void valid_noViolations() {
            var req = new RevokeRequest("user-1", validScope());
            assertTrue(validator.validate(req).isEmpty());
        }

        @Test
        void nullSubject_violation() {
            assertFalse(validator.validate(new RevokeRequest(null, validScope())).isEmpty());
        }

        @Test
        void blankSubject_violation() {
            assertFalse(validator.validate(new RevokeRequest("", validScope())).isEmpty());
        }

        @Test
        void nullScope_violation() {
            assertFalse(validator.validate(new RevokeRequest("user-1", null)).isEmpty());
        }

        @Test
        void invalidNestedScope_cascades() {
            var badScope = new ConsentScopeDto(null, null, null, null);
            var req = new RevokeRequest("user-1", badScope);
            var violations = validator.validate(req);
            // Should have at least 2 violations: category + fieldName from nested scope
            assertTrue(violations.size() >= 2);
        }
    }

    // =======================================================================
    // BatchGrantRequest
    // =======================================================================

    @Nested
    @DisplayName("BatchGrantRequest")
    class BatchGrantRequestValidation {

        @Test
        void valid_noViolations() {
            var entries = List.of(new BatchConsentEntryDto(validScope(), null));
            var req = new BatchGrantRequest("user-1", entries);
            assertTrue(validator.validate(req).isEmpty());
        }

        @Test
        void nullSubject_violation() {
            var entries = List.of(new BatchConsentEntryDto(validScope(), null));
            assertFalse(validator.validate(new BatchGrantRequest(null, entries)).isEmpty());
        }

        @Test
        void nullGrants_violation() {
            assertFalse(validator.validate(new BatchGrantRequest("user-1", null)).isEmpty());
        }

        @Test
        void emptyGrants_violation() {
            var req = new BatchGrantRequest("user-1", List.of());
            var violations = validator.validate(req);
            assertFalse(violations.isEmpty());
            assertTrue(violationMessages(violations).stream().anyMatch(m -> m.contains("empty")));
        }

        @Test
        void tooManyGrants_violation() {
            var entries =
                    IntStream.range(0, 1001)
                            .mapToObj(i -> new BatchConsentEntryDto(validScope(), null))
                            .toList();
            var req = new BatchGrantRequest("user-1", entries);
            var violations = validator.validate(req);
            assertFalse(violations.isEmpty());
            assertTrue(violationMessages(violations).stream().anyMatch(m -> m.contains("1000")));
        }

        @Test
        void exactlyMaxGrants_noViolations() {
            var entries =
                    IntStream.range(0, 1000)
                            .mapToObj(i -> new BatchConsentEntryDto(validScope(), null))
                            .toList();
            var req = new BatchGrantRequest("user-1", entries);
            assertTrue(validator.validate(req).isEmpty());
        }

        @Test
        void invalidNestedEntryScope_cascades() {
            var badEntry =
                    new BatchConsentEntryDto(new ConsentScopeDto(null, "email", null, null), null);
            var req = new BatchGrantRequest("user-1", List.of(badEntry));
            assertFalse(validator.validate(req).isEmpty());
        }

        @Test
        void entryWithNullScope_violation() {
            var badEntry = new BatchConsentEntryDto(null, null);
            var req = new BatchGrantRequest("user-1", List.of(badEntry));
            assertFalse(validator.validate(req).isEmpty());
        }
    }

    // =======================================================================
    // BatchRevokeRequest
    // =======================================================================

    @Nested
    @DisplayName("BatchRevokeRequest")
    class BatchRevokeRequestValidation {

        @Test
        void valid_noViolations() {
            var entries = List.of(new BatchConsentEntryDto(validScope(), null));
            var req = new BatchRevokeRequest("user-1", entries);
            assertTrue(validator.validate(req).isEmpty());
        }

        @Test
        void nullRevokes_violation() {
            assertFalse(validator.validate(new BatchRevokeRequest("user-1", null)).isEmpty());
        }

        @Test
        void emptyRevokes_violation() {
            assertFalse(validator.validate(new BatchRevokeRequest("user-1", List.of())).isEmpty());
        }

        @Test
        void tooManyRevokes_violation() {
            var entries =
                    IntStream.range(0, 1001)
                            .mapToObj(i -> new BatchConsentEntryDto(validScope(), null))
                            .toList();
            assertFalse(validator.validate(new BatchRevokeRequest("user-1", entries)).isEmpty());
        }

        @Test
        void nullSubject_violation() {
            var entries = List.of(new BatchConsentEntryDto(validScope(), null));
            assertFalse(validator.validate(new BatchRevokeRequest(null, entries)).isEmpty());
        }
    }

    // =======================================================================
    // EvaluateRequest
    // =======================================================================

    @Nested
    @DisplayName("EvaluateRequest")
    class EvaluateRequestValidation {

        @Test
        void valid_noViolations() {
            var req = new EvaluateRequest(Set.of(validScope()));
            assertTrue(validator.validate(req).isEmpty());
        }

        @Test
        void nullScopes_violation() {
            assertFalse(validator.validate(new EvaluateRequest(null)).isEmpty());
        }

        @Test
        void emptyScopes_violation() {
            var req = new EvaluateRequest(Set.of());
            var violations = validator.validate(req);
            assertFalse(violations.isEmpty());
        }

        @Test
        void invalidNestedScope_cascades() {
            var badScope = new ConsentScopeDto("", "", null, null);
            var req = new EvaluateRequest(Set.of(badScope));
            assertFalse(validator.validate(req).isEmpty());
        }

        @Test
        void multipleViolations_allReported() {
            var req = new EvaluateRequest(null);
            var violations = validator.validate(req);
            assertFalse(violations.isEmpty());
        }
    }

    // =======================================================================
    // RegisterFieldRequest
    // =======================================================================

    @Nested
    @DisplayName("RegisterFieldRequest")
    class RegisterFieldRequestValidation {

        @Test
        void valid_noViolations() {
            var req = new RegisterFieldRequest("email", "User", "User email address", null);
            assertTrue(validator.validate(req).isEmpty());
        }

        @Test
        void minimalValid_noViolations() {
            // entityName and description are optional
            var req = new RegisterFieldRequest("email", null, null, null);
            assertTrue(validator.validate(req).isEmpty());
        }

        @Test
        void nullFieldName_violation() {
            assertFalse(
                    validator.validate(new RegisterFieldRequest(null, null, null, null)).isEmpty());
        }

        @Test
        void blankFieldName_violation() {
            assertFalse(
                    validator.validate(new RegisterFieldRequest("  ", null, null, null)).isEmpty());
        }

        @Test
        void fieldNameTooLong_violation() {
            assertFalse(
                    validator
                            .validate(
                                    new RegisterFieldRequest(stringOfLength(256), null, null, null))
                            .isEmpty());
        }

        @Test
        void entityNameTooLong_violation() {
            assertFalse(
                    validator
                            .validate(
                                    new RegisterFieldRequest(
                                            "email", stringOfLength(256), null, null))
                            .isEmpty());
        }

        @Test
        void descriptionTooLong_violation() {
            assertFalse(
                    validator
                            .validate(
                                    new RegisterFieldRequest(
                                            "email", null, stringOfLength(1001), null))
                            .isEmpty());
        }

        @Test
        void maxLengthDescription_noViolations() {
            assertTrue(
                    validator
                            .validate(
                                    new RegisterFieldRequest(
                                            "email", null, stringOfLength(1000), null))
                            .isEmpty());
        }
    }

    // =======================================================================
    // RegisterPurposeRequest
    // =======================================================================

    @Nested
    @DisplayName("RegisterPurposeRequest")
    class RegisterPurposeRequestValidation {

        @Test
        void valid_noViolations() {
            assertTrue(
                    validator
                            .validate(
                                    new RegisterPurposeRequest("marketing", "For marketing emails"))
                            .isEmpty());
        }

        @Test
        void nullPurposeName_violation() {
            assertFalse(validator.validate(new RegisterPurposeRequest(null, null)).isEmpty());
        }

        @Test
        void blankPurposeName_violation() {
            assertFalse(validator.validate(new RegisterPurposeRequest("  ", null)).isEmpty());
        }

        @Test
        void purposeNameTooLong_violation() {
            assertFalse(
                    validator
                            .validate(new RegisterPurposeRequest(stringOfLength(256), null))
                            .isEmpty());
        }
    }

    // =======================================================================
    // RegisterRegulationRequest
    // =======================================================================

    @Nested
    @DisplayName("RegisterRegulationRequest")
    class RegisterRegulationRequestValidation {

        @Test
        void valid_noViolations() {
            var req =
                    new RegisterRegulationRequest("GDPR", "EU General Data Protection Regulation");
            assertTrue(validator.validate(req).isEmpty());
        }

        @Test
        void minimalValid_noViolations() {
            // description is optional
            var req = new RegisterRegulationRequest("GDPR", null);
            assertTrue(validator.validate(req).isEmpty());
        }

        @Test
        void nullRegulationName_violation() {
            var req = new RegisterRegulationRequest(null, null);
            assertFalse(validator.validate(req).isEmpty());
        }

        @Test
        void blankRegulationName_violation() {
            var req = new RegisterRegulationRequest("", null);
            assertFalse(validator.validate(req).isEmpty());
        }

        @Test
        void regulationNameTooLong_violation() {
            assertFalse(
                    validator
                            .validate(new RegisterRegulationRequest(stringOfLength(256), null))
                            .isEmpty());
        }

        @Test
        void descriptionTooLong_violation() {
            assertFalse(
                    validator
                            .validate(new RegisterRegulationRequest("GDPR", stringOfLength(1001)))
                            .isEmpty());
        }
    }

    // =======================================================================
    // AssignFieldCategoryRequest
    // =======================================================================

    @Nested
    @DisplayName("AssignFieldCategoryRequest")
    class AssignFieldCategoryRequestValidation {

        @Test
        void valid_noViolations() {
            assertTrue(
                    validator
                            .validate(new AssignFieldCategoryRequest("email", "User", "PII", null))
                            .isEmpty());
        }

        @Test
        void validWithoutEntity_noViolations() {
            assertTrue(
                    validator
                            .validate(new AssignFieldCategoryRequest("email", null, "PII", null))
                            .isEmpty());
        }

        @Test
        void nullFieldName_violation() {
            assertFalse(
                    validator
                            .validate(new AssignFieldCategoryRequest(null, null, "PII", null))
                            .isEmpty());
        }

        @Test
        void blankFieldName_violation() {
            assertFalse(
                    validator
                            .validate(new AssignFieldCategoryRequest("", null, "PII", null))
                            .isEmpty());
        }

        @Test
        void nullCategoryName_violation() {
            assertFalse(
                    validator
                            .validate(new AssignFieldCategoryRequest("email", null, null, null))
                            .isEmpty());
        }

        @Test
        void blankCategoryName_violation() {
            assertFalse(
                    validator
                            .validate(new AssignFieldCategoryRequest("email", null, "  ", null))
                            .isEmpty());
        }

        @Test
        void bothRequired_nullBoth_multipleViolations() {
            var violations =
                    validator.validate(new AssignFieldCategoryRequest(null, null, null, null));
            assertTrue(violations.size() >= 2);
        }
    }

    // =======================================================================
    // LinkPurposeRegulationRequest
    // =======================================================================

    @Nested
    @DisplayName("LinkPurposeRegulationRequest")
    class LinkPurposeRegulationRequestValidation {

        @Test
        void valid_noViolations() {
            assertTrue(
                    validator
                            .validate(
                                    new LinkPurposeRegulationRequest(
                                            "marketing", "GDPR", "consent", null, null))
                            .isEmpty());
        }

        @Test
        void nullPurposeName_violation() {
            assertFalse(
                    validator
                            .validate(
                                    new LinkPurposeRegulationRequest(
                                            null, "GDPR", "consent", null, null))
                            .isEmpty());
        }

        @Test
        void nullRegulationName_violation() {
            assertFalse(
                    validator
                            .validate(
                                    new LinkPurposeRegulationRequest(
                                            "marketing", null, "consent", null, null))
                            .isEmpty());
        }

        @Test
        void nullLawfulBasis_noViolation() {
            assertTrue(
                    validator
                            .validate(
                                    new LinkPurposeRegulationRequest(
                                            "marketing", "GDPR", null, null, null))
                            .isEmpty());
        }

        @Test
        void blankLawfulBasis_noViolation() {
            assertTrue(
                    validator
                            .validate(
                                    new LinkPurposeRegulationRequest(
                                            "marketing", "GDPR", "", null, null))
                            .isEmpty());
        }

        @Test
        void allNull_multipleViolations() {
            var violations =
                    validator.validate(
                            new LinkPurposeRegulationRequest(null, null, null, null, null));
            assertTrue(
                    violations.size() >= 2,
                    "Expected at least 2 violations (purposeName, regulationName)");
        }
    }

    // =======================================================================
    // CreateTenantRequest
    // =======================================================================

    @Nested
    @DisplayName("CreateTenantRequest")
    class CreateTenantRequestValidation {

        @Test
        void valid_noViolations() {
            assertTrue(
                    validator
                            .validate(
                                    new CreateTenantRequest("acme_corp", "Acme Corp", "pro", null))
                            .isEmpty());
        }

        @Test
        void validMinimal_noViolations() {
            // displayName is optional
            assertTrue(
                    validator
                            .validate(new CreateTenantRequest("acme", null, "free", null))
                            .isEmpty());
        }

        @Test
        void nullTenantId_violation() {
            assertFalse(
                    validator
                            .validate(new CreateTenantRequest(null, "Acme", "free", null))
                            .isEmpty());
        }

        @Test
        void blankTenantId_violation() {
            assertFalse(
                    validator
                            .validate(new CreateTenantRequest("", "Acme", "free", null))
                            .isEmpty());
        }

        @Test
        void tenantIdTooLong_violation() {
            assertFalse(
                    validator
                            .validate(
                                    new CreateTenantRequest(
                                            stringOfLength(64), "Acme", "free", null))
                            .isEmpty());
        }

        @Test
        void tenantIdMaxLength_noViolations() {
            // 63 chars, starting with letter
            String id = "a" + "x".repeat(62);
            assertTrue(
                    validator.validate(new CreateTenantRequest(id, null, "free", null)).isEmpty());
        }

        @Test
        void tenantIdWithInvalidChars_violation() {
            // contains a hyphen which is not allowed by the pattern
            var violations =
                    validator.validate(new CreateTenantRequest("acme-corp", "Acme", "free", null));
            assertFalse(violations.isEmpty());
            assertTrue(
                    violationMessages(violations).stream()
                            .anyMatch(m -> m.contains("alphanumeric")));
        }

        @Test
        void tenantIdStartsWithNumber_violation() {
            assertFalse(
                    validator
                            .validate(new CreateTenantRequest("1acme", "Acme", "free", null))
                            .isEmpty());
        }

        @Test
        void tenantIdStartsWithUnderscore_violation() {
            assertFalse(
                    validator
                            .validate(new CreateTenantRequest("_acme", "Acme", "free", null))
                            .isEmpty());
        }

        @Test
        void tenantIdWithSpaces_violation() {
            assertFalse(
                    validator
                            .validate(new CreateTenantRequest("acme corp", "Acme", "free", null))
                            .isEmpty());
        }

        @Test
        void tenantIdSingleLetter_noViolations() {
            assertTrue(
                    validator.validate(new CreateTenantRequest("a", null, "free", null)).isEmpty());
        }

        @Test
        void nullPlan_violation() {
            assertFalse(
                    validator
                            .validate(new CreateTenantRequest("acme", "Acme", null, null))
                            .isEmpty());
        }

        @Test
        void blankPlan_violation() {
            assertFalse(
                    validator
                            .validate(new CreateTenantRequest("acme", "Acme", "  ", null))
                            .isEmpty());
        }

        @Test
        void planTooLong_violation() {
            assertFalse(
                    validator
                            .validate(
                                    new CreateTenantRequest(
                                            "acme", "Acme", stringOfLength(51), null))
                            .isEmpty());
        }

        @Test
        void displayNameTooLong_violation() {
            assertFalse(
                    validator
                            .validate(
                                    new CreateTenantRequest(
                                            "acme", stringOfLength(256), "free", null))
                            .isEmpty());
        }
    }

    // =======================================================================
    // BatchConsentEntryDto (standalone validation)
    // =======================================================================

    @Nested
    @DisplayName("BatchConsentEntryDto")
    class BatchConsentEntryDtoValidation {

        @Test
        void valid_noViolations() {
            assertTrue(validator.validate(new BatchConsentEntryDto(validScope(), null)).isEmpty());
        }

        @Test
        void validWithExpiration_noViolations() {
            assertTrue(
                    validator
                            .validate(
                                    new BatchConsentEntryDto(
                                            validScope(), Instant.now().plusSeconds(86400)))
                            .isEmpty());
        }

        @Test
        void nullScope_violation() {
            assertFalse(validator.validate(new BatchConsentEntryDto(null, null)).isEmpty());
        }

        @Test
        void invalidNestedScope_cascades() {
            var badScope = new ConsentScopeDto(null, null, null, null);
            var violations = validator.validate(new BatchConsentEntryDto(badScope, null));
            // Should cascade: category + fieldName from the nested scope
            assertTrue(violations.size() >= 2);
        }
    }

    // =======================================================================
    // Cross-cutting: combined multi-field violations
    // =======================================================================

    @Nested
    @DisplayName("Cross-cutting validation behavior")
    class CrossCuttingValidation {

        @Test
        void grantRequestAllNull_reportsMultipleViolations() {
            var req = new GrantRequest(null, null, null);
            var violations = validator.validate(req);
            assertTrue(violations.size() >= 2, "Expected at least subject + scope violations");
            var properties = violatedProperties(violations);
            assertTrue(properties.contains("subject"));
            assertTrue(properties.contains("scope"));
        }

        @Test
        void evaluateRequestDeeplyCascaded_reportsNestedPath() {
            // Valid subject but one scope has null category
            var badScope = new ConsentScopeDto(null, "email", null, null);
            var req = new EvaluateRequest(Set.of(badScope));
            var violations = validator.validate(req);
            assertFalse(violations.isEmpty());
            // The property path should reference the nested field
            var paths = violatedProperties(violations);
            assertTrue(
                    paths.stream().anyMatch(p -> p.contains("category")),
                    "Expected violation path to reference 'category' but got: " + paths);
        }

        @Test
        void batchGrantDeeplyCascaded_reportsNestedPath() {
            var badScope = new ConsentScopeDto("PII", null, null, null);
            var entry = new BatchConsentEntryDto(badScope, null);
            var req = new BatchGrantRequest("user-1", List.of(entry));
            var violations = validator.validate(req);
            assertFalse(violations.isEmpty());
            var paths = violatedProperties(violations);
            assertTrue(
                    paths.stream().anyMatch(p -> p.contains("fieldName")),
                    "Expected violation path to reference 'fieldName' but got: " + paths);
        }
    }
}
