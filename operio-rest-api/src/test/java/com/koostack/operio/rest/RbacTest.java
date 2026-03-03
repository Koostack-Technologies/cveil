package com.koostack.operio.rest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.koostack.operio.core.models.BatchConsentResult;
import com.koostack.operio.core.models.ConsentResponse;
import com.koostack.operio.core.models.TenantInfo;
import com.koostack.operio.core.TenantService;
import com.koostack.operio.core.exception.OperioAuthException;
import com.koostack.operio.core.PlatformService;
import com.koostack.operio.core.platform.core.CoreCommandService;
import com.koostack.operio.core.platform.core.CoreQueryService;
import com.koostack.operio.core.platform.iam.IamCommandService;
import com.koostack.operio.core.platform.iam.IamQueryService;
import com.koostack.operio.rest.auth.Roles;
import com.koostack.operio.rest.dto.AdminDtos.AssignFieldCategoryRequest;
import com.koostack.operio.rest.dto.AdminDtos.LinkPurposeRegulationRequest;
import com.koostack.operio.rest.dto.AdminDtos.RegisterFieldRequest;
import com.koostack.operio.rest.dto.AdminDtos.RegisterPurposeRequest;
import com.koostack.operio.rest.dto.AdminDtos.RegisterRegulationRequest;
import com.koostack.operio.core.models.SubjectConsentView;
import com.koostack.operio.rest.dto.ConsentDtos.BatchConsentEntryDto;
import com.koostack.operio.rest.dto.ConsentDtos.BatchGrantRequest;
import com.koostack.operio.rest.dto.ConsentDtos.BatchRevokeRequest;
import com.koostack.operio.rest.dto.ConsentDtos.EvaluateRequest;
import com.koostack.operio.rest.dto.ConsentDtos.GrantRequest;
import com.koostack.operio.rest.dto.ConsentDtos.RevokeRequest;
import com.koostack.operio.rest.dto.ConsentScopeDto;
import com.koostack.operio.rest.dto.PlatformDtos.CreateApiKeyRequest;
import com.koostack.operio.rest.dto.PlatformDtos.CreateTenantRequest;
import com.koostack.operio.rest.lifecycle.BootstrapKeyLifecycleService;
import com.koostack.operio.rest.resource.CatalogueResource;
import com.koostack.operio.rest.resource.ConsentResource;
import com.koostack.operio.rest.resource.LegalResource;
import com.koostack.operio.rest.resource.PlatformResource;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.security.Principal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Comprehensive RBAC unit tests for the Operio REST API.
 *
 * <p>Covers:
 * <ul>
 *   <li>{@link Roles} utility class — requireRole, requireAnyRole,
 *       super-admin bypass, null context handling</li>
 *   <li>{@link ConsentResource} — consent:read for evaluate/summary/history,
 *       consent:write for grant/revoke/batch operations</li>
 *   <li>{@link CatalogueResource} — admin:read for listing fields,
 *       admin:write for register/remove/category endpoints</li>
 *   <li>{@link LegalResource} — admin:read for listing purposes/regulations,
 *       admin:write for register/link endpoints</li>
 *   <li>{@link PlatformResource} — super-admin requirement for all endpoints</li>
 * </ul>
 */
@DisplayName("RBAC (Role-Based Access Control)")
class RbacTest {

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    /**
     * Creates a SecurityContext backed by the given set of roles.
     */
    private static SecurityContext securityContextWith(Set<String> roles) {
        return new SecurityContext() {
            @Override
            public Principal getUserPrincipal() {
                return () -> "test-subject";
            }

            @Override
            public boolean isUserInRole(String role) {
                return role != null && roles.contains(role);
            }

            @Override
            public boolean isSecure() {
                return true;
            }

            @Override
            public String getAuthenticationScheme() {
                return "BEARER";
            }
        };
    }

    /**
     * Creates a SecurityContext with a single role.
     */
    private static SecurityContext securityContextWith(String role) {
        return securityContextWith(Set.of(role));
    }

    /**
     * Creates a SecurityContext with no roles.
     */
    private static SecurityContext emptySecurityContext() {
        return securityContextWith(Set.of());
    }

    // -------------------------------------------------------------------
    // Roles utility class tests
    // -------------------------------------------------------------------

    @Nested
    @DisplayName("Roles utility")
    class RolesUtility {

        @Nested
        @DisplayName("requireRole()")
        class RequireRole {

            @Test
            @DisplayName("null SecurityContext → OperioAuthException")
            void nullContextThrows() {
                OperioAuthException ex = assertThrows(
                    OperioAuthException.class,
                    () -> Roles.requireRole(null, Roles.CONSENT_READ)
                );
                assertTrue(
                    ex.getMessage().contains("consent:read"),
                    "Error message should mention the required role"
                );
            }

            @Test
            @DisplayName("empty roles → OperioAuthException")
            void noRolesThrows() {
                SecurityContext ctx = emptySecurityContext();
                assertThrows(OperioAuthException.class, () ->
                    Roles.requireRole(ctx, Roles.CONSENT_WRITE)
                );
            }

            @Test
            @DisplayName("wrong role → OperioAuthException")
            void wrongRoleThrows() {
                SecurityContext ctx = securityContextWith(Roles.ADMIN_READ);
                assertThrows(OperioAuthException.class, () ->
                    Roles.requireRole(ctx, Roles.CONSENT_READ)
                );
            }

            @Test
            @DisplayName("matching role → no exception")
            void matchingRolePasses() {
                SecurityContext ctx = securityContextWith(Roles.CONSENT_READ);
                assertDoesNotThrow(() ->
                    Roles.requireRole(ctx, Roles.CONSENT_READ)
                );
            }

            @Test
            @DisplayName("super-admin bypasses any role check")
            void superAdminBypassesAll() {
                SecurityContext ctx = securityContextWith(Roles.SUPER_ADMIN);
                assertDoesNotThrow(() ->
                    Roles.requireRole(ctx, Roles.CONSENT_READ)
                );
                assertDoesNotThrow(() ->
                    Roles.requireRole(ctx, Roles.CONSENT_WRITE)
                );
                assertDoesNotThrow(() ->
                    Roles.requireRole(ctx, Roles.ADMIN_READ)
                );
                assertDoesNotThrow(() ->
                    Roles.requireRole(ctx, Roles.ADMIN_WRITE)
                );
                assertDoesNotThrow(() ->
                    Roles.requireRole(ctx, Roles.SUPER_ADMIN)
                );
            }

            @ParameterizedTest
            @ValueSource(
                strings = {
                    "consent:read",
                    "consent:write",
                    "admin:read",
                    "admin:write",
                    "super-admin",
                }
            )
            @DisplayName("each role constant accepted when present")
            void eachRoleAccepted(String role) {
                SecurityContext ctx = securityContextWith(role);
                assertDoesNotThrow(() -> Roles.requireRole(ctx, role));
            }

            @Test
            @DisplayName("error message mentions required role name")
            void errorMessageFormat() {
                SecurityContext ctx = emptySecurityContext();
                OperioAuthException ex = assertThrows(
                    OperioAuthException.class,
                    () -> Roles.requireRole(ctx, "admin:write")
                );
                assertTrue(ex.getMessage().contains("admin:write"));
                assertTrue(ex.getMessage().contains("Access denied"));
            }
        }

        @Nested
        @DisplayName("requireAnyRole()")
        class RequireAnyRole {

            @Test
            @DisplayName("null context → OperioAuthException")
            void nullContextThrows() {
                assertThrows(OperioAuthException.class, () ->
                    Roles.requireAnyRole(
                        null,
                        Roles.CONSENT_READ,
                        Roles.CONSENT_WRITE
                    )
                );
            }

            @Test
            @DisplayName("no matching role → OperioAuthException")
            void noMatchingRoleThrows() {
                SecurityContext ctx = securityContextWith(Roles.ADMIN_READ);
                assertThrows(OperioAuthException.class, () ->
                    Roles.requireAnyRole(
                        ctx,
                        Roles.CONSENT_READ,
                        Roles.CONSENT_WRITE
                    )
                );
            }

            @Test
            @DisplayName("first role matches → passes")
            void firstRoleMatches() {
                SecurityContext ctx = securityContextWith(Roles.CONSENT_READ);
                assertDoesNotThrow(() ->
                    Roles.requireAnyRole(
                        ctx,
                        Roles.CONSENT_READ,
                        Roles.CONSENT_WRITE
                    )
                );
            }

            @Test
            @DisplayName("second role matches → passes")
            void secondRoleMatches() {
                SecurityContext ctx = securityContextWith(Roles.CONSENT_WRITE);
                assertDoesNotThrow(() ->
                    Roles.requireAnyRole(
                        ctx,
                        Roles.CONSENT_READ,
                        Roles.CONSENT_WRITE
                    )
                );
            }

            @Test
            @DisplayName("super-admin bypasses → passes")
            void superAdminBypasses() {
                SecurityContext ctx = securityContextWith(Roles.SUPER_ADMIN);
                assertDoesNotThrow(() ->
                    Roles.requireAnyRole(
                        ctx,
                        Roles.CONSENT_READ,
                        Roles.ADMIN_WRITE
                    )
                );
            }

            @Test
            @DisplayName("error message lists all required roles")
            void errorMessageListsRoles() {
                SecurityContext ctx = emptySecurityContext();
                OperioAuthException ex = assertThrows(
                    OperioAuthException.class,
                    () ->
                        Roles.requireAnyRole(
                            ctx,
                            Roles.CONSENT_READ,
                            Roles.ADMIN_WRITE
                        )
                );
                assertTrue(ex.getMessage().contains("consent:read"));
                assertTrue(ex.getMessage().contains("admin:write"));
            }
        }

        @Nested
        @DisplayName("Role constants")
        class Constants {

            @Test
            @DisplayName("CONSENT_READ = 'consent:read'")
            void consentRead() {
                assertEquals("consent:read", Roles.CONSENT_READ);
            }

            @Test
            @DisplayName("CONSENT_WRITE = 'consent:write'")
            void consentWrite() {
                assertEquals("consent:write", Roles.CONSENT_WRITE);
            }

            @Test
            @DisplayName("ADMIN_READ = 'admin:read'")
            void adminRead() {
                assertEquals("admin:read", Roles.ADMIN_READ);
            }

            @Test
            @DisplayName("ADMIN_WRITE = 'admin:write'")
            void adminWrite() {
                assertEquals("admin:write", Roles.ADMIN_WRITE);
            }

            @Test
            @DisplayName("SUPER_ADMIN = 'super-admin'")
            void superAdmin() {
                assertEquals("super-admin", Roles.SUPER_ADMIN);
            }
        }

        @Nested
        @DisplayName("No implicit role hierarchy")
        class NoHierarchy {

            @Test
            @DisplayName("consent:write does NOT imply consent:read")
            void writeDoesNotImplyRead() {
                SecurityContext ctx = securityContextWith(Roles.CONSENT_WRITE);
                assertThrows(OperioAuthException.class, () ->
                    Roles.requireRole(ctx, Roles.CONSENT_READ)
                );
            }

            @Test
            @DisplayName("consent:read does NOT imply consent:write")
            void readDoesNotImplyWrite() {
                SecurityContext ctx = securityContextWith(Roles.CONSENT_READ);
                assertThrows(OperioAuthException.class, () ->
                    Roles.requireRole(ctx, Roles.CONSENT_WRITE)
                );
            }

            @Test
            @DisplayName("admin:write does NOT imply admin:read")
            void adminWriteDoesNotImplyRead() {
                SecurityContext ctx = securityContextWith(Roles.ADMIN_WRITE);
                assertThrows(OperioAuthException.class, () ->
                    Roles.requireRole(ctx, Roles.ADMIN_READ)
                );
            }

            @Test
            @DisplayName("admin:read does NOT imply admin:write")
            void adminReadDoesNotImplyWrite() {
                SecurityContext ctx = securityContextWith(Roles.ADMIN_READ);
                assertThrows(OperioAuthException.class, () ->
                    Roles.requireRole(ctx, Roles.ADMIN_WRITE)
                );
            }

            @Test
            @DisplayName("consent roles do NOT grant admin access")
            void consentDoesNotGrantAdmin() {
                SecurityContext ctx = securityContextWith(
                    Set.of(Roles.CONSENT_READ, Roles.CONSENT_WRITE)
                );
                assertThrows(OperioAuthException.class, () ->
                    Roles.requireRole(ctx, Roles.ADMIN_READ)
                );
                assertThrows(OperioAuthException.class, () ->
                    Roles.requireRole(ctx, Roles.ADMIN_WRITE)
                );
            }

            @Test
            @DisplayName("admin roles do NOT grant consent access")
            void adminDoesNotGrantConsent() {
                SecurityContext ctx = securityContextWith(
                    Set.of(Roles.ADMIN_READ, Roles.ADMIN_WRITE)
                );
                assertThrows(OperioAuthException.class, () ->
                    Roles.requireRole(ctx, Roles.CONSENT_READ)
                );
                assertThrows(OperioAuthException.class, () ->
                    Roles.requireRole(ctx, Roles.CONSENT_WRITE)
                );
            }
        }

        @Nested
        @DisplayName("Multiple roles in context")
        class MultipleRoles {

            @Test
            @DisplayName(
                "context with both consent roles satisfies either check"
            )
            void bothConsentRoles() {
                SecurityContext ctx = securityContextWith(
                    Set.of(Roles.CONSENT_READ, Roles.CONSENT_WRITE)
                );
                assertDoesNotThrow(() ->
                    Roles.requireRole(ctx, Roles.CONSENT_READ)
                );
                assertDoesNotThrow(() ->
                    Roles.requireRole(ctx, Roles.CONSENT_WRITE)
                );
            }

            @Test
            @DisplayName("context with all roles satisfies any check")
            void allRoles() {
                SecurityContext ctx = securityContextWith(
                    Set.of(
                        Roles.CONSENT_READ,
                        Roles.CONSENT_WRITE,
                        Roles.ADMIN_READ,
                        Roles.ADMIN_WRITE
                    )
                );
                assertDoesNotThrow(() ->
                    Roles.requireRole(ctx, Roles.CONSENT_READ)
                );
                assertDoesNotThrow(() ->
                    Roles.requireRole(ctx, Roles.CONSENT_WRITE)
                );
                assertDoesNotThrow(() ->
                    Roles.requireRole(ctx, Roles.ADMIN_READ)
                );
                assertDoesNotThrow(() ->
                    Roles.requireRole(ctx, Roles.ADMIN_WRITE)
                );
            }
        }
    }

    // -------------------------------------------------------------------
    // ConsentResource RBAC tests
    // -------------------------------------------------------------------

    @Nested
    @DisplayName("ConsentResource RBAC")
    class ConsentResourceRbac {

        private ConsentResource resource;
        private TenantService tenantService;

        @SuppressWarnings("unchecked")
        @BeforeEach
        void setUp() throws Exception {
            resource = new ConsentResource();
            tenantService = mock(TenantService.class);

            // Inject TenantService via reflection
            var operioField = ConsentResource.class.getDeclaredField("tenantService");
            operioField.setAccessible(true);
            operioField.set(resource, tenantService);
        }

        // -- Test data helpers --

        private ConsentScopeDto testScope() {
            return new ConsentScopeDto("pii", "email", "user", "marketing");
        }

        private GrantRequest testGrantRequest() {
            return new GrantRequest("subject-1", testScope(), null);
        }

        private RevokeRequest testRevokeRequest() {
            return new RevokeRequest("subject-1", testScope());
        }

        private BatchGrantRequest testBatchGrantRequest() {
            BatchConsentEntryDto entry = new BatchConsentEntryDto(
                testScope(),
                null
            );
            return new BatchGrantRequest("subject-1", List.of(entry));
        }

        private BatchRevokeRequest testBatchRevokeRequest() {
            BatchConsentEntryDto entry = new BatchConsentEntryDto(
                testScope(),
                null
            );
            return new BatchRevokeRequest("subject-1", List.of(entry));
        }

        private EvaluateRequest testEvaluateRequest() {
            return new EvaluateRequest("subject-1", Set.of(testScope()));
        }

        // -- grant() --

        @Nested
        @DisplayName("POST /subjects/consent/grant")
        class Grant {

            @Test
            @DisplayName("consent:write → 200")
            void consentWriteAllowed() {
                SecurityContext ctx = securityContextWith(Roles.CONSENT_WRITE);
                Response response = resource.grant(ctx, testGrantRequest());
                assertEquals(200, response.getStatus());
            }

            @Test
            @DisplayName("super-admin → 200")
            void superAdminAllowed() {
                SecurityContext ctx = securityContextWith(Roles.SUPER_ADMIN);
                Response response = resource.grant(ctx, testGrantRequest());
                assertEquals(200, response.getStatus());
            }

            @Test
            @DisplayName("consent:read → 403 (OperioAuthException)")
            void consentReadDenied() {
                SecurityContext ctx = securityContextWith(Roles.CONSENT_READ);
                assertThrows(OperioAuthException.class, () ->
                    resource.grant(ctx, testGrantRequest())
                );
            }

            @Test
            @DisplayName("admin:write → 403")
            void adminWriteDenied() {
                SecurityContext ctx = securityContextWith(Roles.ADMIN_WRITE);
                assertThrows(OperioAuthException.class, () ->
                    resource.grant(ctx, testGrantRequest())
                );
            }

            @Test
            @DisplayName("no roles → 403")
            void noRolesDenied() {
                SecurityContext ctx = emptySecurityContext();
                assertThrows(OperioAuthException.class, () ->
                    resource.grant(ctx, testGrantRequest())
                );
            }

            @Test
            @DisplayName("null context → 403")
            void nullContextDenied() {
                assertThrows(OperioAuthException.class, () ->
                    resource.grant(null, testGrantRequest())
                );
            }
        }

        // -- revoke() --

        @Nested
        @DisplayName("POST /subjects/consent/revoke")
        class Revoke {

            @Test
            @DisplayName("consent:write → 200")
            void consentWriteAllowed() {
                SecurityContext ctx = securityContextWith(Roles.CONSENT_WRITE);
                Response response = resource.revoke(ctx, testRevokeRequest());
                assertEquals(200, response.getStatus());
            }

            @Test
            @DisplayName("super-admin → 200")
            void superAdminAllowed() {
                SecurityContext ctx = securityContextWith(Roles.SUPER_ADMIN);
                Response response = resource.revoke(ctx, testRevokeRequest());
                assertEquals(200, response.getStatus());
            }

            @Test
            @DisplayName("consent:read → 403")
            void consentReadDenied() {
                SecurityContext ctx = securityContextWith(Roles.CONSENT_READ);
                assertThrows(OperioAuthException.class, () ->
                    resource.revoke(ctx, testRevokeRequest())
                );
            }

            @Test
            @DisplayName("no roles → 403")
            void noRolesDenied() {
                assertThrows(OperioAuthException.class, () ->
                    resource.revoke(emptySecurityContext(), testRevokeRequest())
                );
            }
        }

        // -- grantAll() --

        @Nested
        @DisplayName("POST /subjects/consent/grant-all")
        class GrantAll {

            @BeforeEach
            void stubBatch() {
                when(
                    tenantService.grantAllConsent(anyString(), anyList())
                ).thenReturn(
                    new BatchConsentResult(1, 0, Collections.emptyList())
                );
            }

            @Test
            @DisplayName("consent:write → 200")
            void consentWriteAllowed() {
                SecurityContext ctx = securityContextWith(Roles.CONSENT_WRITE);
                Response response = resource.grantAll(
                    ctx,
                    testBatchGrantRequest()
                );
                assertEquals(200, response.getStatus());
            }

            @Test
            @DisplayName("super-admin → 200")
            void superAdminAllowed() {
                SecurityContext ctx = securityContextWith(Roles.SUPER_ADMIN);
                Response response = resource.grantAll(
                    ctx,
                    testBatchGrantRequest()
                );
                assertEquals(200, response.getStatus());
            }

            @Test
            @DisplayName("consent:read → 403")
            void consentReadDenied() {
                SecurityContext ctx = securityContextWith(Roles.CONSENT_READ);
                assertThrows(OperioAuthException.class, () ->
                    resource.grantAll(ctx, testBatchGrantRequest())
                );
            }

            @Test
            @DisplayName("no roles → 403")
            void noRolesDenied() {
                assertThrows(OperioAuthException.class, () ->
                    resource.grantAll(
                        emptySecurityContext(),
                        testBatchGrantRequest()
                    )
                );
            }
        }

        // -- revokeAll() --

        @Nested
        @DisplayName("POST /subjects/consent/revoke-all")
        class RevokeAll {

            @BeforeEach
            void stubBatch() {
                when(
                    tenantService.revokeAllConsent(anyString(), anyList())
                ).thenReturn(
                    new BatchConsentResult(1, 0, Collections.emptyList())
                );
            }

            @Test
            @DisplayName("consent:write → 200")
            void consentWriteAllowed() {
                SecurityContext ctx = securityContextWith(Roles.CONSENT_WRITE);
                Response response = resource.revokeAll(
                    ctx,
                    testBatchRevokeRequest()
                );
                assertEquals(200, response.getStatus());
            }

            @Test
            @DisplayName("consent:read → 403")
            void consentReadDenied() {
                SecurityContext ctx = securityContextWith(Roles.CONSENT_READ);
                assertThrows(OperioAuthException.class, () ->
                    resource.revokeAll(ctx, testBatchRevokeRequest())
                );
            }

            @Test
            @DisplayName("super-admin → 200")
            void superAdminAllowed() {
                SecurityContext ctx = securityContextWith(Roles.SUPER_ADMIN);
                Response response = resource.revokeAll(
                    ctx,
                    testBatchRevokeRequest()
                );
                assertEquals(200, response.getStatus());
            }
        }

        // -- evaluate() --

        @Nested
        @DisplayName("POST /subjects/consent/evaluate")
        class Evaluate {

            @BeforeEach
            @SuppressWarnings("unchecked")
            void stubEvaluate() {
                ConsentResponse stubResponse = new ConsentResponse(
                    "subject-1",
                    Collections.emptySet(),
                    Collections.emptySet(),
                    Collections.emptyMap(),
                    Collections.emptyMap()
                );
                doReturn(stubResponse)
                    .when(tenantService)
                    .evaluateConsent(anyString(), anySet());
            }

            @Test
            @DisplayName("consent:read → 200")
            void consentReadAllowed() {
                SecurityContext ctx = securityContextWith(Roles.CONSENT_READ);
                Response response = resource.evaluate(
                    ctx,
                    testEvaluateRequest()
                );
                assertEquals(200, response.getStatus());
            }

            @Test
            @DisplayName("super-admin → 200")
            void superAdminAllowed() {
                SecurityContext ctx = securityContextWith(Roles.SUPER_ADMIN);
                Response response = resource.evaluate(
                    ctx,
                    testEvaluateRequest()
                );
                assertEquals(200, response.getStatus());
            }

            @Test
            @DisplayName("consent:write → 403 (read-only endpoint)")
            void consentWriteDenied() {
                SecurityContext ctx = securityContextWith(Roles.CONSENT_WRITE);
                assertThrows(OperioAuthException.class, () ->
                    resource.evaluate(ctx, testEvaluateRequest())
                );
            }

            @Test
            @DisplayName("admin:read → 403")
            void adminReadDenied() {
                SecurityContext ctx = securityContextWith(Roles.ADMIN_READ);
                assertThrows(OperioAuthException.class, () ->
                    resource.evaluate(ctx, testEvaluateRequest())
                );
            }

            @Test
            @DisplayName("no roles → 403")
            void noRolesDenied() {
                assertThrows(OperioAuthException.class, () ->
                    resource.evaluate(
                        emptySecurityContext(),
                        testEvaluateRequest()
                    )
                );
            }

            @Test
            @DisplayName("null context → 403")
            void nullContextDenied() {
                assertThrows(OperioAuthException.class, () ->
                    resource.evaluate(null, testEvaluateRequest())
                );
            }
        }

        // -- subjectSummary() --

        @Nested
        @DisplayName("GET /subjects/{id}/summary")
        class SubjectSummary {

            @BeforeEach
            void stubSummary() {
                when(
                    tenantService.getSubjectConsent(anyString())
                ).thenReturn(new SubjectConsentView("subj-1", Collections.emptyList()));
            }

            @Test
            @DisplayName("consent:read → 200")
            void consentReadAllowed() {
                SecurityContext ctx = securityContextWith(Roles.CONSENT_READ);
                Response response = resource.subjectSummary(ctx, "subj-1");
                assertEquals(200, response.getStatus());
            }

            @Test
            @DisplayName("super-admin → 200")
            void superAdminAllowed() {
                SecurityContext ctx = securityContextWith(Roles.SUPER_ADMIN);
                Response response = resource.subjectSummary(ctx, "subj-1");
                assertEquals(200, response.getStatus());
            }

            @Test
            @DisplayName("consent:write → 403")
            void consentWriteDenied() {
                SecurityContext ctx = securityContextWith(Roles.CONSENT_WRITE);
                assertThrows(OperioAuthException.class, () ->
                    resource.subjectSummary(ctx, "subj-1")
                );
            }

            @Test
            @DisplayName("no roles → 403")
            void noRolesDenied() {
                assertThrows(OperioAuthException.class, () ->
                    resource.subjectSummary(emptySecurityContext(), "subj-1")
                );
            }
        }

        // -- subjectHistory() --

        @Nested
        @DisplayName("GET /subjects/{id}/history")
        class SubjectHistory {

            @BeforeEach
            void stubHistory() {
                when(
                    tenantService.getSubjectConsent(anyString())
                ).thenReturn(new SubjectConsentView("subj-1", Collections.emptyList()));
            }

            @Test
            @DisplayName("consent:read → 200")
            void consentReadAllowed() {
                SecurityContext ctx = securityContextWith(Roles.CONSENT_READ);
                Response response = resource.subjectHistory(
                    ctx,
                    "subj-1"
                );
                assertEquals(200, response.getStatus());
            }

            @Test
            @DisplayName("super-admin → 200")
            void superAdminAllowed() {
                SecurityContext ctx = securityContextWith(Roles.SUPER_ADMIN);
                Response response = resource.subjectHistory(
                    ctx,
                    "subj-1"
                );
                assertEquals(200, response.getStatus());
            }

            @Test
            @DisplayName("consent:write → 403")
            void consentWriteDenied() {
                SecurityContext ctx = securityContextWith(Roles.CONSENT_WRITE);
                assertThrows(OperioAuthException.class, () ->
                    resource.subjectHistory(ctx, "subj-1")
                );
            }

            @Test
            @DisplayName("no roles → 403")
            void noRolesDenied() {
                assertThrows(OperioAuthException.class, () ->
                    resource.subjectHistory(
                        emptySecurityContext(),
                        "subj-1"
                    )
                );
            }
        }

        // -- Cross-cutting consent tests --

        @Nested
        @DisplayName("Cross-cutting consent role checks")
        class ConsentCrossCutting {

            @BeforeEach
            @SuppressWarnings("unchecked")
            void stubAll() {
                when(
                    tenantService.grantAllConsent(anyString(), anyList())
                ).thenReturn(
                    new BatchConsentResult(1, 0, Collections.emptyList())
                );
                when(
                    tenantService.revokeAllConsent(anyString(), anyList())
                ).thenReturn(
                    new BatchConsentResult(1, 0, Collections.emptyList())
                );
                ConsentResponse stubResponse = new ConsentResponse(
                    "subject-1",
                    Collections.emptySet(),
                    Collections.emptySet(),
                    Collections.emptyMap(),
                    Collections.emptyMap()
                );
                doReturn(stubResponse)
                    .when(tenantService)
                    .evaluateConsent(anyString(), anySet());
                when(
                    tenantService.getSubjectConsent(anyString())
                ).thenReturn(new SubjectConsentView("subj-1", Collections.emptyList()));
            }

            @Test
            @DisplayName("admin:read cannot access any consent endpoint")
            void adminReadCannotAccessConsent() {
                SecurityContext ctx = securityContextWith(Roles.ADMIN_READ);
                assertThrows(OperioAuthException.class, () ->
                    resource.grant(ctx, testGrantRequest())
                );
                assertThrows(OperioAuthException.class, () ->
                    resource.revoke(ctx, testRevokeRequest())
                );
                assertThrows(OperioAuthException.class, () ->
                    resource.grantAll(ctx, testBatchGrantRequest())
                );
                assertThrows(OperioAuthException.class, () ->
                    resource.revokeAll(ctx, testBatchRevokeRequest())
                );
                assertThrows(OperioAuthException.class, () ->
                    resource.evaluate(ctx, testEvaluateRequest())
                );
                assertThrows(OperioAuthException.class, () ->
                    resource.subjectSummary(ctx, "subj-1")
                );
                assertThrows(OperioAuthException.class, () ->
                    resource.subjectHistory(ctx, "subj-1")
                );
            }

            @Test
            @DisplayName("admin:write cannot access any consent endpoint")
            void adminWriteCannotAccessConsent() {
                SecurityContext ctx = securityContextWith(Roles.ADMIN_WRITE);
                assertThrows(OperioAuthException.class, () ->
                    resource.grant(ctx, testGrantRequest())
                );
                assertThrows(OperioAuthException.class, () ->
                    resource.evaluate(ctx, testEvaluateRequest())
                );
            }

            @Test
            @DisplayName("both consent roles → full consent access")
            void bothConsentRolesGrantFullAccess() {
                SecurityContext ctx = securityContextWith(
                    Set.of(Roles.CONSENT_READ, Roles.CONSENT_WRITE)
                );
                // Write endpoints
                assertDoesNotThrow(() ->
                    resource.grant(ctx, testGrantRequest())
                );
                assertDoesNotThrow(() ->
                    resource.revoke(ctx, testRevokeRequest())
                );
                assertDoesNotThrow(() ->
                    resource.grantAll(ctx, testBatchGrantRequest())
                );
                assertDoesNotThrow(() ->
                    resource.revokeAll(ctx, testBatchRevokeRequest())
                );
                // Read endpoints
                assertDoesNotThrow(() ->
                    resource.evaluate(ctx, testEvaluateRequest())
                );
                assertDoesNotThrow(() ->
                    resource.subjectSummary(ctx, "subj-1")
                );
                assertDoesNotThrow(() ->
                    resource.subjectHistory(ctx, "subj-1")
                );
            }
        }
    }

    // -------------------------------------------------------------------
    // CatalogueResource RBAC tests
    // -------------------------------------------------------------------

    @Nested
    @DisplayName("CatalogueResource RBAC")
    class CatalogueResourceRbac {

        private CatalogueResource resource;
        private TenantService tenantService;

        @SuppressWarnings("unchecked")
        @BeforeEach
        void setUp() throws Exception {
            resource = new CatalogueResource();
            tenantService = mock(TenantService.class);

            when(tenantService.listFields()).thenReturn(
                Collections.emptyList()
            );

            var field = CatalogueResource.class.getDeclaredField("tenantService");
            field.setAccessible(true);
            field.set(resource, tenantService);
        }

        // -- Read endpoints --

        @Nested
        @DisplayName("GET /catalogue/fields (listFields)")
        class ListFields {

            @Test
            @DisplayName("admin:read → 200")
            void adminReadAllowed() {
                SecurityContext ctx = securityContextWith(Roles.ADMIN_READ);
                Response response = resource.listFields(ctx);
                assertEquals(200, response.getStatus());
            }

            @Test
            @DisplayName("super-admin → 200")
            void superAdminAllowed() {
                SecurityContext ctx = securityContextWith(Roles.SUPER_ADMIN);
                Response response = resource.listFields(ctx);
                assertEquals(200, response.getStatus());
            }

            @Test
            @DisplayName("admin:write → 403")
            void adminWriteDenied() {
                SecurityContext ctx = securityContextWith(Roles.ADMIN_WRITE);
                assertThrows(OperioAuthException.class, () ->
                    resource.listFields(ctx)
                );
            }

            @Test
            @DisplayName("consent:read → 403")
            void consentReadDenied() {
                SecurityContext ctx = securityContextWith(Roles.CONSENT_READ);
                assertThrows(OperioAuthException.class, () ->
                    resource.listFields(ctx)
                );
            }

            @Test
            @DisplayName("no roles → 403")
            void noRolesDenied() {
                assertThrows(OperioAuthException.class, () ->
                    resource.listFields(emptySecurityContext())
                );
            }

            @Test
            @DisplayName("null context → 403")
            void nullContextDenied() {
                assertThrows(OperioAuthException.class, () ->
                    resource.listFields(null)
                );
            }
        }

        // -- Write endpoints --

        @Nested
        @DisplayName("POST /catalogue/fields (registerField)")
        class RegisterField {

            private RegisterFieldRequest request() {
                return new RegisterFieldRequest(
                    "email",
                    "user",
                    "User email address",
                    "User entity"
                );
            }

            @Test
            @DisplayName("admin:write → 201")
            void adminWriteAllowed() {
                SecurityContext ctx = securityContextWith(Roles.ADMIN_WRITE);
                Response response = resource.registerField(ctx, request());
                assertEquals(201, response.getStatus());
            }

            @Test
            @DisplayName("super-admin → 201")
            void superAdminAllowed() {
                SecurityContext ctx = securityContextWith(Roles.SUPER_ADMIN);
                Response response = resource.registerField(ctx, request());
                assertEquals(201, response.getStatus());
            }

            @Test
            @DisplayName("admin:read → 403")
            void adminReadDenied() {
                SecurityContext ctx = securityContextWith(Roles.ADMIN_READ);
                assertThrows(OperioAuthException.class, () ->
                    resource.registerField(ctx, request())
                );
            }

            @Test
            @DisplayName("consent:write → 403")
            void consentWriteDenied() {
                SecurityContext ctx = securityContextWith(Roles.CONSENT_WRITE);
                assertThrows(OperioAuthException.class, () ->
                    resource.registerField(ctx, request())
                );
            }

            @Test
            @DisplayName("no roles → 403")
            void noRolesDenied() {
                assertThrows(OperioAuthException.class, () ->
                    resource.registerField(emptySecurityContext(), request())
                );
            }

            @Test
            @DisplayName("null context → 403")
            void nullContextDenied() {
                assertThrows(OperioAuthException.class, () ->
                    resource.registerField(null, request())
                );
            }
        }

        @Nested
        @DisplayName("POST /catalogue/fields/categories (assignFieldCategory)")
        class AssignFieldCategory {

            private AssignFieldCategoryRequest request() {
                return new AssignFieldCategoryRequest("email", "user", "pii", "Personal info");
            }

            @Test
            @DisplayName("admin:write → 200")
            void adminWriteAllowed() {
                SecurityContext ctx = securityContextWith(Roles.ADMIN_WRITE);
                Response response = resource.assignFieldCategory(
                    ctx,
                    request()
                );
                assertEquals(200, response.getStatus());
            }

            @Test
            @DisplayName("super-admin → 200")
            void superAdminAllowed() {
                SecurityContext ctx = securityContextWith(Roles.SUPER_ADMIN);
                Response response = resource.assignFieldCategory(
                    ctx,
                    request()
                );
                assertEquals(200, response.getStatus());
            }

            @Test
            @DisplayName("admin:read → 403")
            void adminReadDenied() {
                SecurityContext ctx = securityContextWith(Roles.ADMIN_READ);
                assertThrows(OperioAuthException.class, () ->
                    resource.assignFieldCategory(ctx, request())
                );
            }

            @Test
            @DisplayName("no roles → 403")
            void noRolesDenied() {
                assertThrows(OperioAuthException.class, () ->
                    resource.assignFieldCategory(
                        emptySecurityContext(),
                        request()
                    )
                );
            }
        }

        // -- Cross-cutting catalogue tests --

        @Nested
        @DisplayName("Cross-cutting catalogue role checks")
        class CatalogueCrossCutting {

            @Test
            @DisplayName("consent:read cannot access any catalogue endpoint")
            void consentReadCannotAccessCatalogue() {
                SecurityContext ctx = securityContextWith(Roles.CONSENT_READ);
                assertThrows(OperioAuthException.class, () ->
                    resource.listFields(ctx)
                );
                assertThrows(OperioAuthException.class, () ->
                    resource.registerField(
                        ctx,
                        new RegisterFieldRequest("f", "e", "d", "ed")
                    )
                );
            }

            @Test
            @DisplayName("consent:write cannot access any catalogue endpoint")
            void consentWriteCannotAccessCatalogue() {
                SecurityContext ctx = securityContextWith(Roles.CONSENT_WRITE);
                assertThrows(OperioAuthException.class, () ->
                    resource.listFields(ctx)
                );
                assertThrows(OperioAuthException.class, () ->
                    resource.registerField(
                        ctx,
                        new RegisterFieldRequest("f", "e", "d", "ed")
                    )
                );
            }

            @Test
            @DisplayName("both admin roles → full catalogue access")
            void bothAdminRolesGrantFullAccess() {
                SecurityContext ctx = securityContextWith(
                    Set.of(Roles.ADMIN_READ, Roles.ADMIN_WRITE)
                );
                assertDoesNotThrow(() -> resource.listFields(ctx));
                assertDoesNotThrow(() ->
                    resource.registerField(
                        ctx,
                        new RegisterFieldRequest("f", "e", "d", "ed")
                    )
                );
                assertDoesNotThrow(() ->
                    resource.assignFieldCategory(
                        ctx,
                        new AssignFieldCategoryRequest("f", "e", "c", "desc")
                    )
                );
            }

            @Test
            @DisplayName("super-admin → full catalogue access (read + write)")
            void superAdminFullCatalogueAccess() {
                SecurityContext ctx = securityContextWith(Roles.SUPER_ADMIN);
                assertDoesNotThrow(() -> resource.listFields(ctx));
                assertDoesNotThrow(() ->
                    resource.registerField(
                        ctx,
                        new RegisterFieldRequest("f", "e", "d", "ed")
                    )
                );
            }
        }
    }

    // -------------------------------------------------------------------
    // LegalResource RBAC tests
    // -------------------------------------------------------------------

    @Nested
    @DisplayName("LegalResource RBAC")
    class LegalResourceRbac {

        private LegalResource resource;
        private TenantService tenantService;

        @SuppressWarnings("unchecked")
        @BeforeEach
        void setUp() throws Exception {
            resource = new LegalResource();
            tenantService = mock(TenantService.class);

            when(tenantService.listPurposes()).thenReturn(
                Collections.emptyList()
            );
            when(tenantService.listRegulations()).thenReturn(
                Collections.emptyList()
            );

            var field = LegalResource.class.getDeclaredField("tenantService");
            field.setAccessible(true);
            field.set(resource, tenantService);
        }

        // -- Read endpoints --

        @Nested
        @DisplayName("GET /legal/purposes (listPurposes)")
        class ListPurposes {

            @Test
            @DisplayName("admin:read → 200")
            void adminReadAllowed() {
                SecurityContext ctx = securityContextWith(Roles.ADMIN_READ);
                Response response = resource.listPurposes(ctx);
                assertEquals(200, response.getStatus());
            }

            @Test
            @DisplayName("no roles → 403")
            void noRolesDenied() {
                assertThrows(OperioAuthException.class, () ->
                    resource.listPurposes(emptySecurityContext())
                );
            }
        }

        @Nested
        @DisplayName("GET /legal/regulations (listRegulations)")
        class ListRegulations {

            @Test
            @DisplayName("admin:read → 200")
            void adminReadAllowed() {
                SecurityContext ctx = securityContextWith(Roles.ADMIN_READ);
                Response response = resource.listRegulations(ctx);
                assertEquals(200, response.getStatus());
            }

            @Test
            @DisplayName("no roles → 403")
            void noRolesDenied() {
                assertThrows(OperioAuthException.class, () ->
                    resource.listRegulations(emptySecurityContext())
                );
            }
        }

        // -- Write endpoints --

        @Nested
        @DisplayName("POST /legal/purposes (registerPurpose)")
        class RegisterPurpose {

            private RegisterPurposeRequest request() {
                return new RegisterPurposeRequest(
                    "marketing",
                    "Marketing communications"
                );
            }

            @Test
            @DisplayName("admin:write → 201")
            void adminWriteAllowed() {
                SecurityContext ctx = securityContextWith(Roles.ADMIN_WRITE);
                Response response = resource.registerPurpose(ctx, request());
                assertEquals(201, response.getStatus());
            }

            @Test
            @DisplayName("admin:read → 403")
            void adminReadDenied() {
                SecurityContext ctx = securityContextWith(Roles.ADMIN_READ);
                assertThrows(OperioAuthException.class, () ->
                    resource.registerPurpose(ctx, request())
                );
            }
        }

        @Nested
        @DisplayName("POST /legal/regulations (registerRegulation)")
        class RegisterRegulation {

            private RegisterRegulationRequest request() {
                return new RegisterRegulationRequest(
                    "gdpr",
                    "General Data Protection Regulation"
                );
            }

            @Test
            @DisplayName("admin:write → 201")
            void adminWriteAllowed() {
                SecurityContext ctx = securityContextWith(Roles.ADMIN_WRITE);
                Response response = resource.registerRegulation(ctx, request());
                assertEquals(201, response.getStatus());
            }

            @Test
            @DisplayName("admin:read → 403")
            void adminReadDenied() {
                SecurityContext ctx = securityContextWith(Roles.ADMIN_READ);
                assertThrows(OperioAuthException.class, () ->
                    resource.registerRegulation(ctx, request())
                );
            }

            @Test
            @DisplayName("no roles → 403")
            void noRolesDenied() {
                assertThrows(OperioAuthException.class, () ->
                    resource.registerRegulation(
                        emptySecurityContext(),
                        request()
                    )
                );
            }
        }

        @Nested
        @DisplayName("POST /legal/purposes/links (linkPurposeToRegulation)")
        class LinkPurposeRegulation {

            private LinkPurposeRegulationRequest request() {
                return new LinkPurposeRegulationRequest(
                    "marketing",
                    "gdpr",
                    "consent",
                    "Granted action",
                    "Denied action"
                );
            }

            @Test
            @DisplayName("admin:write → 200")
            void adminWriteAllowed() {
                SecurityContext ctx = securityContextWith(Roles.ADMIN_WRITE);
                Response response = resource.linkPurposeToRegulation(
                    ctx,
                    request()
                );
                assertEquals(200, response.getStatus());
            }

            @Test
            @DisplayName("admin:read → 403")
            void adminReadDenied() {
                SecurityContext ctx = securityContextWith(Roles.ADMIN_READ);
                assertThrows(OperioAuthException.class, () ->
                    resource.linkPurposeToRegulation(ctx, request())
                );
            }

            @Test
            @DisplayName("no roles → 403")
            void noRolesDenied() {
                assertThrows(OperioAuthException.class, () ->
                    resource.linkPurposeToRegulation(
                        emptySecurityContext(),
                        request()
                    )
                );
            }
        }

        // -- Cross-cutting legal tests --

        @Nested
        @DisplayName("Cross-cutting legal role checks")
        class LegalCrossCutting {

            @Test
            @DisplayName("consent:read cannot access any legal endpoint")
            void consentReadCannotAccessLegal() {
                SecurityContext ctx = securityContextWith(Roles.CONSENT_READ);
                assertThrows(OperioAuthException.class, () ->
                    resource.listPurposes(ctx)
                );
                assertThrows(OperioAuthException.class, () ->
                    resource.listRegulations(ctx)
                );
            }

            @Test
            @DisplayName("consent:write cannot access any legal endpoint")
            void consentWriteCannotAccessLegal() {
                SecurityContext ctx = securityContextWith(Roles.CONSENT_WRITE);
                assertThrows(OperioAuthException.class, () ->
                    resource.listPurposes(ctx)
                );
                assertThrows(OperioAuthException.class, () ->
                    resource.registerPurpose(
                        ctx,
                        new RegisterPurposeRequest("p", "desc")
                    )
                );
            }

            @Test
            @DisplayName("both admin roles → full legal access")
            void bothAdminRolesGrantFullAccess() {
                SecurityContext ctx = securityContextWith(
                    Set.of(Roles.ADMIN_READ, Roles.ADMIN_WRITE)
                );
                assertDoesNotThrow(() -> resource.listPurposes(ctx));
                assertDoesNotThrow(() -> resource.listRegulations(ctx));
                assertDoesNotThrow(() ->
                    resource.registerPurpose(
                        ctx,
                        new RegisterPurposeRequest("p", "desc")
                    )
                );
                assertDoesNotThrow(() ->
                    resource.registerRegulation(
                        ctx,
                        new RegisterRegulationRequest("r", "desc")
                    )
                );
                assertDoesNotThrow(() ->
                    resource.linkPurposeToRegulation(
                        ctx,
                        new LinkPurposeRegulationRequest("p", "r", "consent", "granted", "denied")
                    )
                );
            }

            @Test
            @DisplayName("super-admin → full legal access (read + write)")
            void superAdminFullLegalAccess() {
                SecurityContext ctx = securityContextWith(Roles.SUPER_ADMIN);
                assertDoesNotThrow(() -> resource.listPurposes(ctx));
                assertDoesNotThrow(() -> resource.listRegulations(ctx));
                assertDoesNotThrow(() ->
                    resource.registerPurpose(
                        ctx,
                        new RegisterPurposeRequest("p", "desc")
                    )
                );
            }
        }
    }

    // -------------------------------------------------------------------
    // PlatformResource RBAC tests
    // -------------------------------------------------------------------

    @Nested
    @DisplayName("PlatformResource RBAC")
    class PlatformResourceRbac {

        private PlatformResource resource;
        private CoreCommandService<?> coreCommandService;
        private CoreQueryService<?> coreQueryService;
        private IamCommandService<?> iamCommandService;
        private IamQueryService<?> iamQueryService;
        private PlatformService platformService;
        private BootstrapKeyLifecycleService bootstrapLifecycle;

        @SuppressWarnings("unchecked")
        @BeforeEach
        void setUp() throws Exception {
            resource = new PlatformResource();
            coreCommandService = mock(CoreCommandService.class);
            coreQueryService = mock(CoreQueryService.class);
            iamCommandService = mock(IamCommandService.class);
            iamQueryService = mock(IamQueryService.class);
            platformService = mock(PlatformService.class);
            bootstrapLifecycle = mock(BootstrapKeyLifecycleService.class);

            // Inject via reflection
            for (var entry : java.util.Map.of(
                "coreCommandService", coreCommandService,
                "coreQueryService", coreQueryService,
                "iamCommandService", iamCommandService,
                "iamQueryService", iamQueryService,
                "platformService", platformService,
                "bootstrapLifecycle", bootstrapLifecycle
            ).entrySet()) {
                var field = PlatformResource.class.getDeclaredField(entry.getKey());
                field.setAccessible(true);
                field.set(resource, entry.getValue());
            }
        }

        @Nested
        @DisplayName("POST /platform/tenants (createTenant)")
        class CreateTenant {

            @Test
            @DisplayName("super-admin → 201")
            void superAdminAllowed() {
                SecurityContext ctx = securityContextWith(Roles.SUPER_ADMIN);
                CreateTenantRequest request = new CreateTenantRequest(
                    "tenant-1",
                    "Tenant One",
                    "free",
                    null
                );
                Response response = resource.createTenant(ctx, request);
                assertEquals(201, response.getStatus());
            }

            @Test
            @DisplayName("consent:write → 403")
            void consentWriteDenied() {
                SecurityContext ctx = securityContextWith(Roles.CONSENT_WRITE);
                CreateTenantRequest request = new CreateTenantRequest(
                    "tenant-1",
                    "Tenant One",
                    "free",
                    null
                );
                assertThrows(OperioAuthException.class, () ->
                    resource.createTenant(ctx, request)
                );
            }

            @Test
            @DisplayName("admin:write → 403")
            void adminWriteDenied() {
                SecurityContext ctx = securityContextWith(Roles.ADMIN_WRITE);
                CreateTenantRequest request = new CreateTenantRequest(
                    "tenant-1",
                    "Tenant One",
                    "free",
                    null
                );
                assertThrows(OperioAuthException.class, () ->
                    resource.createTenant(ctx, request)
                );
            }

            @Test
            @DisplayName("no roles → 403")
            void noRolesDenied() {
                CreateTenantRequest request = new CreateTenantRequest(
                    "tenant-1",
                    "Tenant One",
                    "free",
                    null
                );
                assertThrows(OperioAuthException.class, () ->
                    resource.createTenant(emptySecurityContext(), request)
                );
            }

            @Test
            @DisplayName("null context → 403")
            void nullContextDenied() {
                CreateTenantRequest request = new CreateTenantRequest(
                    "tenant-1",
                    "Tenant One",
                    "free",
                    null
                );
                assertThrows(OperioAuthException.class, () ->
                    resource.createTenant(null, request)
                );
            }
        }

        @Nested
        @DisplayName("GET /platform/tenants (listTenants)")
        class ListTenants {

            @Test
            @DisplayName("super-admin → 200")
            void superAdminAllowed() {
                SecurityContext ctx = securityContextWith(Roles.SUPER_ADMIN);
                when(coreQueryService.listTenants()).thenReturn(
                    Collections.emptyList()
                );
                Response response = resource.listTenants(ctx);
                assertEquals(200, response.getStatus());
            }

            @Test
            @DisplayName("admin:read → 403")
            void adminReadDenied() {
                SecurityContext ctx = securityContextWith(Roles.ADMIN_READ);
                assertThrows(OperioAuthException.class, () ->
                    resource.listTenants(ctx)
                );
            }

            @Test
            @DisplayName("consent:read → 403")
            void consentReadDenied() {
                SecurityContext ctx = securityContextWith(Roles.CONSENT_READ);
                assertThrows(OperioAuthException.class, () ->
                    resource.listTenants(ctx)
                );
            }

            @Test
            @DisplayName("all non-super-admin roles → 403")
            void allNonSuperAdminRolesDenied() {
                SecurityContext ctx = securityContextWith(
                    Set.of(
                        Roles.CONSENT_READ,
                        Roles.CONSENT_WRITE,
                        Roles.ADMIN_READ,
                        Roles.ADMIN_WRITE
                    )
                );
                assertThrows(OperioAuthException.class, () ->
                    resource.listTenants(ctx)
                );
            }
        }

        @Nested
        @DisplayName("GET /platform/tenants/{id} (getTenant)")
        class GetTenant {

            @Test
            @DisplayName("super-admin → 200")
            void superAdminAllowed() {
                SecurityContext ctx = securityContextWith(Roles.SUPER_ADMIN);
                var tenantInfo = new com.koostack.operio.core.platform.core.models.TenantInfo(
                    "t1",
                    "Tenant",
                    "free",
                    "active",
                    "t_t1",
                    null,
                    Instant.now(),
                    null
                );
                when(coreQueryService.getTenant("t1")).thenReturn(
                    tenantInfo
                );
                Response response = resource.getTenant(ctx, "t1");
                assertEquals(200, response.getStatus());
            }

            @Test
            @DisplayName("admin:read → 403")
            void adminReadDenied() {
                SecurityContext ctx = securityContextWith(Roles.ADMIN_READ);
                assertThrows(OperioAuthException.class, () ->
                    resource.getTenant(ctx, "t1")
                );
            }
        }

        @Nested
        @DisplayName("DELETE /platform/tenants/{id} (deleteTenant)")
        class DeleteTenant {

            @Test
            @DisplayName("super-admin → 200")
            void superAdminAllowed() {
                SecurityContext ctx = securityContextWith(Roles.SUPER_ADMIN);
                Response response = resource.deleteTenant(ctx, "t1");
                assertEquals(200, response.getStatus());
            }

            @Test
            @DisplayName("admin:write → 403")
            void adminWriteDenied() {
                SecurityContext ctx = securityContextWith(Roles.ADMIN_WRITE);
                assertThrows(OperioAuthException.class, () ->
                    resource.deleteTenant(ctx, "t1")
                );
            }
        }

        @Nested
        @DisplayName("POST /platform/api-keys (createApiKey)")
        class CreateApiKey {

            @Test
            @DisplayName("super-admin → 201")
            void superAdminAllowed() {
                SecurityContext ctx = securityContextWith(Roles.SUPER_ADMIN);
                when(
                    iamCommandService.createApiToken(
                        anyString(),
                        anyString(),
                        anyString(),
                        any()
                    )
                ).thenReturn(new IamCommandService.CreateApiTokenResult(
                    "key-id-1",
                    "opr_live",
                    "opr_live_test1234567890abcdef"
                ));
                CreateApiKeyRequest request = new CreateApiKeyRequest(
                    "tenant-1",
                    "test-subject",
                    Set.of("consent:read", "consent:write")
                );
                Response response = resource.createApiKey(ctx, request);
                assertEquals(201, response.getStatus());
            }

            @Test
            @DisplayName("admin:write → 403")
            void adminWriteDenied() {
                SecurityContext ctx = securityContextWith(Roles.ADMIN_WRITE);
                CreateApiKeyRequest request = new CreateApiKeyRequest(
                    "tenant-1",
                    "test-subject",
                    Set.of("consent:read")
                );
                assertThrows(OperioAuthException.class, () ->
                    resource.createApiKey(ctx, request)
                );
            }
        }

        @Nested
        @DisplayName("GET /platform/api-keys (listApiKeys)")
        class ListApiKeys {

            @Test
            @DisplayName("super-admin → 200")
            void superAdminAllowed() {
                SecurityContext ctx = securityContextWith(Roles.SUPER_ADMIN);
                when(iamQueryService.listApiKeys("t1")).thenReturn(
                    Collections.emptyList()
                );
                Response response = resource.listApiKeys(ctx, "t1");
                assertEquals(200, response.getStatus());
            }

            @Test
            @DisplayName("consent:read → 403")
            void consentReadDenied() {
                SecurityContext ctx = securityContextWith(Roles.CONSENT_READ);
                assertThrows(OperioAuthException.class, () ->
                    resource.listApiKeys(ctx, "t1")
                );
            }
        }

        @Nested
        @DisplayName("DELETE /platform/api-keys/{prefix} (revokeApiKey)")
        class RevokeApiKey {

            @Test
            @DisplayName("super-admin → 200")
            void superAdminAllowed() {
                SecurityContext ctx = securityContextWith(Roles.SUPER_ADMIN);
                when(iamQueryService.findByKeyPrefix("opr_live")).thenReturn(
                    new IamQueryService.ApiKeyInfo("key-1", "opr_live", "test", "t1", "PROD", null, null, "active", Instant.now(), null)
                );
                Response response = resource.revokeApiKey(ctx, "opr_live");
                assertEquals(200, response.getStatus());
            }

            @Test
            @DisplayName("admin:write → 403")
            void adminWriteDenied() {
                SecurityContext ctx = securityContextWith(Roles.ADMIN_WRITE);
                assertThrows(OperioAuthException.class, () ->
                    resource.revokeApiKey(ctx, "opr_live")
                );
            }

            @Test
            @DisplayName("no roles → 403")
            void noRolesDenied() {
                assertThrows(OperioAuthException.class, () ->
                    resource.revokeApiKey(emptySecurityContext(), "opr_live")
                );
            }
        }

        @Nested
        @DisplayName("Cross-cutting platform checks")
        class PlatformCrossCutting {

            @Test
            @DisplayName("only super-admin can access platform endpoints")
            void onlySuperAdminCanAccess() {
                // Try every non-super-admin role individually
                for (String role : List.of(
                    Roles.CONSENT_READ,
                    Roles.CONSENT_WRITE,
                    Roles.ADMIN_READ,
                    Roles.ADMIN_WRITE
                )) {
                    SecurityContext ctx = securityContextWith(role);
                    assertThrows(
                        OperioAuthException.class,
                        () -> resource.listTenants(ctx),
                        "Role '" + role + "' should not access listTenants"
                    );
                    assertThrows(
                        OperioAuthException.class,
                        () -> resource.getTenant(ctx, "t1"),
                        "Role '" + role + "' should not access getTenant"
                    );
                    assertThrows(
                        OperioAuthException.class,
                        () -> resource.deleteTenant(ctx, "t1"),
                        "Role '" + role + "' should not access deleteTenant"
                    );
                    assertThrows(
                        OperioAuthException.class,
                        () ->
                            resource.createTenant(
                                ctx,
                                new CreateTenantRequest("t1", "T", "free", null)
                            ),
                        "Role '" + role + "' should not access createTenant"
                    );
                }
            }

            @Test
            @DisplayName("combined non-super-admin roles still denied")
            void combinedNonSuperAdminDenied() {
                SecurityContext ctx = securityContextWith(
                    Set.of(
                        Roles.CONSENT_READ,
                        Roles.CONSENT_WRITE,
                        Roles.ADMIN_READ,
                        Roles.ADMIN_WRITE
                    )
                );
                assertThrows(OperioAuthException.class, () ->
                    resource.listTenants(ctx)
                );
                assertThrows(OperioAuthException.class, () ->
                    resource.createTenant(
                        ctx,
                        new CreateTenantRequest("t1", "T", "free", null)
                    )
                );
            }
        }
    }

    // -------------------------------------------------------------------
    // Integration-style: full role matrix
    // -------------------------------------------------------------------

    @Nested
    @DisplayName("Full role-to-endpoint matrix")
    class RoleEndpointMatrix {

        /**
         * Validates that the exception thrown for unauthorized access
         * is specifically OperioAuthException (which maps to 403),
         * and that the message contains "Access denied".
         */
        @Test
        @DisplayName("unauthorized responses have consistent error format")
        void unauthorizedResponseFormat() {
            SecurityContext noRoles = emptySecurityContext();

            OperioAuthException ex;

            ex = assertThrows(OperioAuthException.class, () ->
                Roles.requireRole(noRoles, Roles.CONSENT_READ)
            );
            assertTrue(ex.getMessage().startsWith("Access denied"));
            assertTrue(ex.getMessage().contains("consent:read"));

            ex = assertThrows(OperioAuthException.class, () ->
                Roles.requireRole(noRoles, Roles.CONSENT_WRITE)
            );
            assertTrue(ex.getMessage().contains("consent:write"));

            ex = assertThrows(OperioAuthException.class, () ->
                Roles.requireRole(noRoles, Roles.ADMIN_READ)
            );
            assertTrue(ex.getMessage().contains("admin:read"));

            ex = assertThrows(OperioAuthException.class, () ->
                Roles.requireRole(noRoles, Roles.ADMIN_WRITE)
            );
            assertTrue(ex.getMessage().contains("admin:write"));

            ex = assertThrows(OperioAuthException.class, () ->
                Roles.requireRole(noRoles, Roles.SUPER_ADMIN)
            );
            assertTrue(ex.getMessage().contains("super-admin"));
        }

        @Test
        @DisplayName("super-admin has universal access across all role checks")
        void superAdminUniversalAccess() {
            SecurityContext superAdmin = securityContextWith(Roles.SUPER_ADMIN);
            // Should not throw for any role
            String[] allRoles = {
                Roles.CONSENT_READ,
                Roles.CONSENT_WRITE,
                Roles.ADMIN_READ,
                Roles.ADMIN_WRITE,
                Roles.SUPER_ADMIN,
            };
            for (String role : allRoles) {
                assertDoesNotThrow(
                    () -> Roles.requireRole(superAdmin, role),
                    "super-admin should satisfy requirement for: " + role
                );
            }
        }

        @Test
        @DisplayName(
            "requireAnyRole with empty varargs → throws for non-super-admin"
        )
        void requireAnyRoleEmptyVarargs() {
            // edge case: no roles specified — should throw
            SecurityContext ctx = securityContextWith(Roles.CONSENT_READ);
            assertThrows(OperioAuthException.class, () ->
                Roles.requireAnyRole(ctx)
            );
        }

        @Test
        @DisplayName(
            "requireAnyRole with empty varargs → passes for super-admin"
        )
        void requireAnyRoleEmptyVarargsSuperAdmin() {
            SecurityContext ctx = securityContextWith(Roles.SUPER_ADMIN);
            assertDoesNotThrow(() -> Roles.requireAnyRole(ctx));
        }
    }
}
