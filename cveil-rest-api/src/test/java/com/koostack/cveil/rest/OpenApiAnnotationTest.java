package com.koostack.cveil.rest;

import static org.junit.jupiter.api.Assertions.*;

import com.koostack.cveil.rest.config.CveilOpenApiConfig;
import com.koostack.cveil.rest.resource.CatalogueResource;
import com.koostack.cveil.rest.resource.ConsentResource;
import com.koostack.cveil.rest.resource.DsarResource;
import com.koostack.cveil.rest.resource.GovernanceResource;
import com.koostack.cveil.rest.resource.HealthResource;
import com.koostack.cveil.rest.resource.PlatformResource;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.microprofile.openapi.annotations.OpenAPIDefinition;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies that OpenAPI annotations are correctly applied to all resource classes, the
 * application-level configuration, and individual endpoints.
 *
 * <p>These are compile-time/reflection-based tests that do not require a running Quarkus container.
 * They ensure that the Swagger UI documentation stays in sync with the actual resource methods.
 */
@DisplayName("OpenAPI Annotation Tests")
class OpenApiAnnotationTest {

    // ===================================================================
    // Application-level OpenAPI definition
    // ===================================================================

    @Nested
    @DisplayName("CveilOpenApiConfig")
    class OpenApiConfigTests {

        @Test
        @DisplayName("class carries @OpenAPIDefinition")
        void hasOpenApiDefinition() {
            assertTrue(
                    CveilOpenApiConfig.class.isAnnotationPresent(OpenAPIDefinition.class),
                    "CveilOpenApiConfig must have @OpenAPIDefinition");
        }

        @Test
        @DisplayName("@OpenAPIDefinition has info with title and version")
        void hasInfoTitleAndVersion() {
            OpenAPIDefinition def = CveilOpenApiConfig.class.getAnnotation(OpenAPIDefinition.class);
            assertNotNull(def.info());
            assertEquals("Cveil Consent Management API", def.info().title());
            assertEquals("1.0.0", def.info().version());
        }

        @Test
        @DisplayName("@OpenAPIDefinition info has contact")
        void hasContact() {
            OpenAPIDefinition def = CveilOpenApiConfig.class.getAnnotation(OpenAPIDefinition.class);
            assertEquals("KooStack", def.info().contact().name());
            assertFalse(def.info().contact().url().isBlank());
        }

        @Test
        @DisplayName("@OpenAPIDefinition info has license")
        void hasLicense() {
            OpenAPIDefinition def = CveilOpenApiConfig.class.getAnnotation(OpenAPIDefinition.class);
            assertEquals("Apache 2.0", def.info().license().name());
            assertFalse(def.info().license().url().isBlank());
        }

        @Test
        @DisplayName("@OpenAPIDefinition has info description mentioning GDPR")
        void hasDescriptionWithRegulations() {
            OpenAPIDefinition def = CveilOpenApiConfig.class.getAnnotation(OpenAPIDefinition.class);
            String desc = def.info().description();
            assertTrue(desc.contains("GDPR"), "Description should mention GDPR");
            assertTrue(desc.contains("CCPA"), "Description should mention CCPA");
        }

        @Test
        @DisplayName("defines all tags")
        void definesAllTags() {
            OpenAPIDefinition def = CveilOpenApiConfig.class.getAnnotation(OpenAPIDefinition.class);
            Tag[] tags = def.tags();
            assertEquals(8, tags.length, "Should define 8 tags");

            Set<String> tagNames = Arrays.stream(tags).map(Tag::name).collect(Collectors.toSet());
            assertTrue(tagNames.contains(CveilOpenApiConfig.TAG_CONSENT));
            assertTrue(tagNames.contains(CveilOpenApiConfig.TAG_CATALOGUE));
            assertTrue(tagNames.contains(CveilOpenApiConfig.TAG_GOVERNANCE));
            assertTrue(tagNames.contains(CveilOpenApiConfig.TAG_ADMIN));
            assertTrue(tagNames.contains(CveilOpenApiConfig.TAG_PLATFORM));
            assertTrue(tagNames.contains(CveilOpenApiConfig.TAG_HEALTH));
        }

        @Test
        @DisplayName("each tag has a non-empty description")
        void tagsHaveDescriptions() {
            OpenAPIDefinition def = CveilOpenApiConfig.class.getAnnotation(OpenAPIDefinition.class);
            for (Tag tag : def.tags()) {
                assertFalse(
                        tag.description().isBlank(),
                        "Tag '" + tag.name() + "' must have a description");
            }
        }

        @Test
        @DisplayName("defines BearerAuth security scheme with HTTP bearer type")
        void definesSecurityScheme() {
            OpenAPIDefinition def = CveilOpenApiConfig.class.getAnnotation(OpenAPIDefinition.class);
            SecurityScheme[] schemes = def.components().securitySchemes();
            assertEquals(1, schemes.length, "Should define exactly one security scheme");

            SecurityScheme scheme = schemes[0];
            assertEquals(CveilOpenApiConfig.SECURITY_SCHEME, scheme.securitySchemeName());
            assertEquals(SecuritySchemeType.HTTP, scheme.type());
            assertEquals("bearer", scheme.scheme());
            assertEquals("API Key", scheme.bearerFormat());
        }

        @Test
        @DisplayName("security scheme has non-empty description mentioning roles")
        void securitySchemeDescribesRoles() {
            OpenAPIDefinition def = CveilOpenApiConfig.class.getAnnotation(OpenAPIDefinition.class);
            SecurityScheme scheme = def.components().securitySchemes()[0];
            String desc = scheme.description();
            assertTrue(desc.contains("consent:read"), "Should mention consent:read role");
            assertTrue(desc.contains("consent:write"), "Should mention consent:write role");
            assertTrue(desc.contains("admin:read"), "Should mention admin:read role");
            assertTrue(desc.contains("admin:write"), "Should mention admin:write role");
            assertTrue(desc.contains("super-admin"), "Should mention super-admin role");
        }

        @Test
        @DisplayName("tag constants are non-null and non-blank")
        void tagConstantsAreValid() {
            assertNotNull(CveilOpenApiConfig.TAG_CONSENT);
            assertNotNull(CveilOpenApiConfig.TAG_CATALOGUE);
            assertNotNull(CveilOpenApiConfig.TAG_GOVERNANCE);
            assertNotNull(CveilOpenApiConfig.TAG_ADMIN);
            assertNotNull(CveilOpenApiConfig.TAG_PLATFORM);
            assertNotNull(CveilOpenApiConfig.TAG_HEALTH);
            assertFalse(CveilOpenApiConfig.TAG_CONSENT.isBlank());
            assertFalse(CveilOpenApiConfig.TAG_CATALOGUE.isBlank());
            assertFalse(CveilOpenApiConfig.TAG_GOVERNANCE.isBlank());
            assertFalse(CveilOpenApiConfig.TAG_ADMIN.isBlank());
            assertFalse(CveilOpenApiConfig.TAG_PLATFORM.isBlank());
            assertFalse(CveilOpenApiConfig.TAG_HEALTH.isBlank());
        }

        @Test
        @DisplayName("SECURITY_SCHEME constant matches annotation")
        void securitySchemeConstantMatchesAnnotation() {
            OpenAPIDefinition def = CveilOpenApiConfig.class.getAnnotation(OpenAPIDefinition.class);
            SecurityScheme scheme = def.components().securitySchemes()[0];
            assertEquals(
                    CveilOpenApiConfig.SECURITY_SCHEME,
                    scheme.securitySchemeName(),
                    "SECURITY_SCHEME constant must match the annotation");
        }
    }

    // ===================================================================
    // ConsentResource annotations
    // ===================================================================

    @Nested
    @DisplayName("ConsentResource")
    class ConsentResourceTests {

        @Test
        @DisplayName("class has @Tag referencing Consent")
        void hasTag() {
            Tag tag = ConsentResource.class.getAnnotation(Tag.class);
            assertNotNull(tag, "ConsentResource must have @Tag");
            assertEquals(CveilOpenApiConfig.TAG_CONSENT, tag.ref());
        }

        @Test
        @DisplayName("class has @SecurityRequirement referencing BearerAuth")
        void hasSecurityRequirement() {
            SecurityRequirement sr = ConsentResource.class.getAnnotation(SecurityRequirement.class);
            assertNotNull(sr, "ConsentResource must have @SecurityRequirement");
            assertEquals(CveilOpenApiConfig.SECURITY_SCHEME, sr.name());
        }

        @Test
        @DisplayName("all public endpoint methods have @Operation")
        void allMethodsHaveOperation() {
            assertAllEndpointMethodsHaveOperation(ConsentResource.class);
        }

        @Test
        @DisplayName("all public endpoint methods have @APIResponses")
        void allMethodsHaveApiResponses() {
            assertAllEndpointMethodsHaveApiResponses(ConsentResource.class);
        }

        @Test
        @DisplayName("grant has operationId=grantConsent")
        void grantOperationId() throws Exception {
            Method m =
                    ConsentResource.class.getMethod(
                            "grant",
                            jakarta.ws.rs.core.SecurityContext.class,
                            com.koostack.cveil.rest.dto.ConsentDtos.GrantRequest.class);
            assertEquals("grantConsent", m.getAnnotation(Operation.class).operationId());
        }

        @Test
        @DisplayName("evaluate has operationId=evaluateConsent")
        void evaluateOperationId() throws Exception {
            Method m =
                    ConsentResource.class.getMethod(
                            "evaluate",
                            jakarta.ws.rs.core.SecurityContext.class,
                            String.class,
                            com.koostack.cveil.rest.dto.ConsentDtos.EvaluateRequest.class);
            assertEquals("evaluateConsent", m.getAnnotation(Operation.class).operationId());
        }

        @Test
        @DisplayName("subjectHistory has @Parameter annotations on path and query params")
        void subjectHistoryHasParameters() throws Exception {
            Method m =
                    ConsentResource.class.getMethod(
                            "subjectHistory",
                            jakarta.ws.rs.core.SecurityContext.class,
                            String.class);
            long paramCount =
                    Arrays.stream(m.getParameters())
                            .filter(p -> p.isAnnotationPresent(Parameter.class))
                            .count();
            // 1 param: subjectId (path)
            assertEquals(1, paramCount, "subjectHistory should have 1 @Parameter annotation");
        }

        @Test
        @DisplayName("every @Operation has a non-blank summary")
        void operationsHaveSummaries() {
            assertAllOperationsHaveSummaries(ConsentResource.class);
        }

        @Test
        @DisplayName("every @Operation has a non-blank description")
        void operationsHaveDescriptions() {
            assertAllOperationsHaveDescriptions(ConsentResource.class);
        }

        @Test
        @DisplayName("consent endpoints have 16 distinct operation IDs")
        void hasCorrectNumberOfOperations() {
            long opCount = countOperationAnnotatedMethods(ConsentResource.class);
            assertEquals(16, opCount, "ConsentResource should have 16 @Operation methods");
        }

        @Test
        @DisplayName("all operation IDs are unique")
        void operationIdsAreUnique() {
            assertOperationIdsAreUnique(ConsentResource.class);
        }
    }

    // ===================================================================
    // CatalogueResource annotations
    // ===================================================================

    @Nested
    @DisplayName("CatalogueResource")
    class CatalogueResourceTests {

        @Test
        @DisplayName("class has @Tag referencing Catalogue")
        void hasTag() {
            Tag tag = CatalogueResource.class.getAnnotation(Tag.class);
            assertNotNull(tag, "CatalogueResource must have @Tag");
            assertEquals(CveilOpenApiConfig.TAG_CATALOGUE, tag.ref());
        }

        @Test
        @DisplayName("class has @SecurityRequirement referencing BearerAuth")
        void hasSecurityRequirement() {
            SecurityRequirement sr =
                    CatalogueResource.class.getAnnotation(SecurityRequirement.class);
            assertNotNull(sr, "CatalogueResource must have @SecurityRequirement");
            assertEquals(CveilOpenApiConfig.SECURITY_SCHEME, sr.name());
        }

        @Test
        @DisplayName("all public endpoint methods have @Operation")
        void allMethodsHaveOperation() {
            assertAllEndpointMethodsHaveOperation(CatalogueResource.class);
        }

        @Test
        @DisplayName("all public endpoint methods have @APIResponses")
        void allMethodsHaveApiResponses() {
            assertAllEndpointMethodsHaveApiResponses(CatalogueResource.class);
        }

        @Test
        @DisplayName("catalogue endpoints have 16 distinct operation IDs")
        void hasCorrectNumberOfOperations() {
            long opCount = countOperationAnnotatedMethods(CatalogueResource.class);
            assertEquals(16, opCount, "CatalogueResource should have 16 @Operation methods");
        }

        @Test
        @DisplayName("all operation IDs are unique")
        void operationIdsAreUnique() {
            assertOperationIdsAreUnique(CatalogueResource.class);
        }

        @Test
        @DisplayName("every @Operation has a non-blank summary")
        void operationsHaveSummaries() {
            assertAllOperationsHaveSummaries(CatalogueResource.class);
        }
    }

    // ===================================================================
    // GovernanceResource annotations
    // ===================================================================

    @Nested
    @DisplayName("GovernanceResource")
    class GovernanceResourceTests {

        @Test
        @DisplayName("class has @Tag referencing Legal")
        void hasTag() {
            Tag tag = GovernanceResource.class.getAnnotation(Tag.class);
            assertNotNull(tag, "GovernanceResource must have @Tag");
            assertEquals(CveilOpenApiConfig.TAG_GOVERNANCE, tag.ref());
        }

        @Test
        @DisplayName("class has @SecurityRequirement referencing BearerAuth")
        void hasSecurityRequirement() {
            SecurityRequirement sr =
                    GovernanceResource.class.getAnnotation(SecurityRequirement.class);
            assertNotNull(sr, "GovernanceResource must have @SecurityRequirement");
            assertEquals(CveilOpenApiConfig.SECURITY_SCHEME, sr.name());
        }

        @Test
        @DisplayName("all public endpoint methods have @Operation")
        void allMethodsHaveOperation() {
            assertAllEndpointMethodsHaveOperation(GovernanceResource.class);
        }

        @Test
        @DisplayName("all public endpoint methods have @APIResponses")
        void allMethodsHaveApiResponses() {
            assertAllEndpointMethodsHaveApiResponses(GovernanceResource.class);
        }

        @Test
        @DisplayName("governance endpoints have 19 distinct operation IDs")
        void hasCorrectNumberOfOperations() {
            long opCount = countOperationAnnotatedMethods(GovernanceResource.class);
            assertEquals(19, opCount, "GovernanceResource should have 19 @Operation methods");
        }

        @Test
        @DisplayName("all operation IDs are unique")
        void operationIdsAreUnique() {
            assertOperationIdsAreUnique(GovernanceResource.class);
        }

        @Test
        @DisplayName("every @Operation has a non-blank summary")
        void operationsHaveSummaries() {
            assertAllOperationsHaveSummaries(GovernanceResource.class);
        }
    }

    // ===================================================================
    // PlatformResource annotations
    // ===================================================================

    @Nested
    @DisplayName("PlatformResource")
    class PlatformResourceTests {

        @Test
        @DisplayName("class has @Tag referencing Platform")
        void hasTag() {
            Tag tag = PlatformResource.class.getAnnotation(Tag.class);
            assertNotNull(tag, "PlatformResource must have @Tag");
            assertEquals(CveilOpenApiConfig.TAG_PLATFORM, tag.ref());
        }

        @Test
        @DisplayName("class has @SecurityRequirement referencing BearerAuth")
        void hasSecurityRequirement() {
            SecurityRequirement sr =
                    PlatformResource.class.getAnnotation(SecurityRequirement.class);
            assertNotNull(sr, "PlatformResource must have @SecurityRequirement");
            assertEquals(CveilOpenApiConfig.SECURITY_SCHEME, sr.name());
        }

        @Test
        @DisplayName("all public endpoint methods have @Operation")
        void allMethodsHaveOperation() {
            assertAllEndpointMethodsHaveOperation(PlatformResource.class);
        }

        @Test
        @DisplayName("all public endpoint methods have @APIResponses")
        void allMethodsHaveApiResponses() {
            assertAllEndpointMethodsHaveApiResponses(PlatformResource.class);
        }

        @Test
        @DisplayName("platform endpoints have 5 distinct operation IDs")
        void hasCorrectNumberOfOperations() {
            long opCount = countOperationAnnotatedMethods(PlatformResource.class);
            assertEquals(5, opCount, "PlatformResource should have 5 @Operation methods");
        }

        @Test
        @DisplayName("all operation IDs are unique")
        void operationIdsAreUnique() {
            assertOperationIdsAreUnique(PlatformResource.class);
        }

        @Test
        @DisplayName("getTenant has @Parameter on path param")
        void getTenantHasParameter() throws Exception {
            Method m =
                    PlatformResource.class.getMethod(
                            "getTenant", jakarta.ws.rs.core.SecurityContext.class, String.class);
            long paramCount =
                    Arrays.stream(m.getParameters())
                            .filter(p -> p.isAnnotationPresent(Parameter.class))
                            .count();
            assertTrue(paramCount >= 1, "getTenant should have at least 1 @Parameter");
        }
    }

    // ===================================================================
    // HealthResource annotations
    // ===================================================================

    @Nested
    @DisplayName("HealthResource")
    class HealthResourceTests {

        @Test
        @DisplayName("class has @Tag referencing Health")
        void hasTag() {
            Tag tag = HealthResource.class.getAnnotation(Tag.class);
            assertNotNull(tag, "HealthResource must have @Tag");
            assertEquals(CveilOpenApiConfig.TAG_HEALTH, tag.ref());
        }

        @Test
        @DisplayName("class does NOT have @SecurityRequirement (public endpoint)")
        void noSecurityRequirement() {
            SecurityRequirement sr = HealthResource.class.getAnnotation(SecurityRequirement.class);
            assertNull(sr, "HealthResource should not have @SecurityRequirement (public)");
        }

        @Test
        @DisplayName("health() has @Operation with operationId=healthCheck")
        void healthHasOperation() throws Exception {
            Method m = HealthResource.class.getMethod("health");
            Operation op = m.getAnnotation(Operation.class);
            assertNotNull(op, "health() must have @Operation");
            assertEquals("healthCheck", op.operationId());
        }

        @Test
        @DisplayName("health() has @APIResponses with 200 and 503")
        void healthHasApiResponses() throws Exception {
            Method m = HealthResource.class.getMethod("health");
            APIResponses responses = m.getAnnotation(APIResponses.class);
            assertNotNull(responses, "health() must have @APIResponses");

            Set<String> codes =
                    Arrays.stream(responses.value())
                            .map(APIResponse::responseCode)
                            .collect(Collectors.toSet());
            assertTrue(codes.contains("200"), "Should have 200 response");
            assertTrue(codes.contains("503"), "Should have 503 response");
        }

        @Test
        @DisplayName("health() @Operation has non-blank summary")
        void healthHasSummary() throws Exception {
            Method m = HealthResource.class.getMethod("health");
            Operation op = m.getAnnotation(Operation.class);
            assertFalse(op.summary().isBlank(), "health @Operation must have a summary");
        }
    }

    // ===================================================================
    // Cross-cutting: all operationIds globally unique
    // ===================================================================

    @Nested
    @DisplayName("Cross-cutting")
    class CrossCuttingTests {

        @Test
        @DisplayName("all operationIds across all resources are globally unique")
        void operationIdsGloballyUnique() {
            Set<String> allIds = new java.util.HashSet<>();
            for (Class<?> clazz :
                    new Class<?>[] {
                        ConsentResource.class,
                        CatalogueResource.class,
                        GovernanceResource.class,
                        PlatformResource.class,
                        HealthResource.class,
                        DsarResource.class,
                    }) {
                for (Method m : clazz.getDeclaredMethods()) {
                    Operation op = m.getAnnotation(Operation.class);
                    if (op != null && !op.operationId().isBlank()) {
                        assertTrue(
                                allIds.add(op.operationId()),
                                "Duplicate operationId: "
                                        + op.operationId()
                                        + " in "
                                        + clazz.getSimpleName()
                                        + "."
                                        + m.getName());
                    }
                }
            }
            // 16 consent + 16 catalogue + 19 governance + 5 platform + 1 health + 7 dsar = 64
            assertEquals(64, allIds.size(), "Expected 64 globally unique operationIds");
        }

        @Test
        @DisplayName("authenticated resources have @SecurityRequirement, public ones do not")
        void securityRequirementPresenceIsCorrect() {
            assertNotNull(ConsentResource.class.getAnnotation(SecurityRequirement.class));
            assertNotNull(CatalogueResource.class.getAnnotation(SecurityRequirement.class));
            assertNotNull(GovernanceResource.class.getAnnotation(SecurityRequirement.class));
            assertNotNull(PlatformResource.class.getAnnotation(SecurityRequirement.class));
            assertNull(HealthResource.class.getAnnotation(SecurityRequirement.class));
        }

        @Test
        @DisplayName("all resource classes have @Tag")
        void allResourcesHaveTag() {
            assertNotNull(ConsentResource.class.getAnnotation(Tag.class));
            assertNotNull(CatalogueResource.class.getAnnotation(Tag.class));
            assertNotNull(GovernanceResource.class.getAnnotation(Tag.class));
            assertNotNull(PlatformResource.class.getAnnotation(Tag.class));
            assertNotNull(HealthResource.class.getAnnotation(Tag.class));
        }
    }

    // ===================================================================
    // Schema annotation tests
    // ===================================================================

    @Nested
    @DisplayName("Schema annotations on DTOs")
    class SchemaAnnotationTests {

        @Test
        @DisplayName("ErrorResponse has @Schema")
        void errorResponseHasSchema() {
            assertTrue(
                    com.koostack.cveil.rest.dto.CommonDtos.ErrorResponse.class.isAnnotationPresent(
                            org.eclipse.microprofile.openapi.annotations.media.Schema.class),
                    "ErrorResponse must have @Schema");
        }

        @Test
        @DisplayName("HealthResponse has @Schema")
        void healthResponseHasSchema() {
            assertTrue(
                    com.koostack.cveil.rest.dto.CommonDtos.HealthResponse.class.isAnnotationPresent(
                            org.eclipse.microprofile.openapi.annotations.media.Schema.class),
                    "HealthResponse must have @Schema");
        }

        @Test
        @DisplayName("MessageResponse has @Schema")
        void messageResponseHasSchema() {
            assertTrue(
                    com.koostack.cveil.rest.dto.CommonDtos.MessageResponse.class
                            .isAnnotationPresent(
                                    org.eclipse.microprofile.openapi.annotations.media.Schema
                                            .class),
                    "MessageResponse must have @Schema");
        }

        @Test
        @DisplayName("ConsentScopeDto has @Schema")
        void consentScopeDtoHasSchema() {
            assertTrue(
                    com.koostack.cveil.rest.dto.ConsentScopeDto.class.isAnnotationPresent(
                            org.eclipse.microprofile.openapi.annotations.media.Schema.class),
                    "ConsentScopeDto must have @Schema");
        }

        @Test
        @DisplayName("ErrorResponse fields have @Schema (via declared fields)")
        void errorResponseFieldsHaveSchema() {
            // @Schema targets FIELD/METHOD/PARAMETER/TYPE — Java propagates
            // record-component annotations to the backing field when the
            // annotation's @Target includes FIELD.
            for (var field :
                    com.koostack.cveil.rest.dto.CommonDtos.ErrorResponse.class
                            .getDeclaredFields()) {
                // Skip synthetic/static fields (e.g. $assertionsEnabled)
                if (field.isSynthetic()
                        || java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                assertTrue(
                        field.isAnnotationPresent(
                                org.eclipse.microprofile.openapi.annotations.media.Schema.class),
                        "ErrorResponse." + field.getName() + " must have @Schema");
            }
        }

        @Test
        @DisplayName("ConsentScopeDto fields have @Schema (via declared fields)")
        void consentScopeDtoFieldsHaveSchema() {
            for (var field :
                    com.koostack.cveil.rest.dto.ConsentScopeDto.class.getDeclaredFields()) {
                if (field.isSynthetic()
                        || java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                assertTrue(
                        field.isAnnotationPresent(
                                org.eclipse.microprofile.openapi.annotations.media.Schema.class),
                        "ConsentScopeDto." + field.getName() + " must have @Schema");
            }
        }
    }

    // ===================================================================
    // Assertion helpers
    // ===================================================================

    /**
     * Verifies every public method with a JAX-RS HTTP method annotation (@GET, @POST, @DELETE,
     * etc.) also has @Operation.
     */
    private void assertAllEndpointMethodsHaveOperation(Class<?> resourceClass) {
        for (Method m : resourceClass.getDeclaredMethods()) {
            if (isJaxRsEndpoint(m)) {
                assertNotNull(
                        m.getAnnotation(Operation.class),
                        resourceClass.getSimpleName()
                                + "."
                                + m.getName()
                                + "() must have @Operation");
            }
        }
    }

    /** Verifies every JAX-RS endpoint method has @APIResponses. */
    private void assertAllEndpointMethodsHaveApiResponses(Class<?> resourceClass) {
        for (Method m : resourceClass.getDeclaredMethods()) {
            if (isJaxRsEndpoint(m)) {
                assertNotNull(
                        m.getAnnotation(APIResponses.class),
                        resourceClass.getSimpleName()
                                + "."
                                + m.getName()
                                + "() must have @APIResponses");
            }
        }
    }

    /** Verifies every @Operation has a non-blank summary. */
    private void assertAllOperationsHaveSummaries(Class<?> resourceClass) {
        for (Method m : resourceClass.getDeclaredMethods()) {
            Operation op = m.getAnnotation(Operation.class);
            if (op != null) {
                assertFalse(
                        op.summary().isBlank(),
                        resourceClass.getSimpleName()
                                + "."
                                + m.getName()
                                + "() @Operation must have a non-blank summary");
            }
        }
    }

    /** Verifies every @Operation has a non-blank description. */
    private void assertAllOperationsHaveDescriptions(Class<?> resourceClass) {
        for (Method m : resourceClass.getDeclaredMethods()) {
            Operation op = m.getAnnotation(Operation.class);
            if (op != null) {
                assertFalse(
                        op.description().isBlank(),
                        resourceClass.getSimpleName()
                                + "."
                                + m.getName()
                                + "() @Operation must have a non-blank description");
            }
        }
    }

    /** Verifies all operation IDs within a single resource class are unique. */
    private void assertOperationIdsAreUnique(Class<?> resourceClass) {
        Set<String> ids = new java.util.HashSet<>();
        for (Method m : resourceClass.getDeclaredMethods()) {
            Operation op = m.getAnnotation(Operation.class);
            if (op != null && !op.operationId().isBlank()) {
                assertTrue(
                        ids.add(op.operationId()),
                        "Duplicate operationId '"
                                + op.operationId()
                                + "' in "
                                + resourceClass.getSimpleName());
            }
        }
    }

    private long countOperationAnnotatedMethods(Class<?> resourceClass) {
        return Arrays.stream(resourceClass.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Operation.class))
                .count();
    }

    private boolean isJaxRsEndpoint(Method m) {
        return (m.isAnnotationPresent(jakarta.ws.rs.GET.class)
                || m.isAnnotationPresent(jakarta.ws.rs.POST.class)
                || m.isAnnotationPresent(jakarta.ws.rs.PUT.class)
                || m.isAnnotationPresent(jakarta.ws.rs.DELETE.class)
                || m.isAnnotationPresent(jakarta.ws.rs.PATCH.class));
    }
}
