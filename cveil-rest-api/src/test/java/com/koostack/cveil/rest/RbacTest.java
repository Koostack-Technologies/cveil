package com.koostack.cveil.rest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.koostack.cveil.core.cqrs.CommandDispatcher;
import com.koostack.cveil.core.cqrs.QueryDispatcher;
import com.koostack.cveil.core.exception.CveilAuthException;
import com.koostack.cveil.core.models.AuthorizationContext;
import com.koostack.cveil.core.models.ConsentResponse;
import com.koostack.cveil.core.models.PurposeView;
import com.koostack.cveil.core.models.SubjectConsentView;
import com.koostack.cveil.core.platform.core.CoreCommandService;
import com.koostack.cveil.core.platform.core.CoreQueryService;
import com.koostack.cveil.core.platform.iam.IamQueryService;
import com.koostack.cveil.rest.auth.Permissions;
import com.koostack.cveil.rest.auth.Roles;
import com.koostack.cveil.rest.config.CveilConfig;
import com.koostack.cveil.rest.config.PlatformCqrsHolder;
import com.koostack.cveil.rest.context.CveilRequestHolder;
import com.koostack.cveil.rest.dto.AdminDtos.AssignFieldCategoryRequest;
import com.koostack.cveil.rest.dto.AdminDtos.LinkPurposeRegulationRequest;
import com.koostack.cveil.rest.dto.AdminDtos.RegisterFieldRequest;
import com.koostack.cveil.rest.dto.AdminDtos.RegisterPurposeRequest;
import com.koostack.cveil.rest.dto.AdminDtos.RegisterRegulationRequest;
import com.koostack.cveil.rest.dto.ConsentDtos.BatchConsentEntryDto;
import com.koostack.cveil.rest.dto.ConsentDtos.BatchGrantRequest;
import com.koostack.cveil.rest.dto.ConsentDtos.BatchRevokeRequest;
import com.koostack.cveil.rest.dto.ConsentDtos.EvaluateRequest;
import com.koostack.cveil.rest.dto.ConsentDtos.GrantRequest;
import com.koostack.cveil.rest.dto.ConsentDtos.RevokeRequest;
import com.koostack.cveil.rest.dto.ConsentScopeDto;
import com.koostack.cveil.rest.dto.PlatformDtos.CreateTenantRequest;
import com.koostack.cveil.rest.ratelimit.RateLimitService;
import com.koostack.cveil.rest.resource.CatalogueResource;
import com.koostack.cveil.rest.resource.ConsentResource;
import com.koostack.cveil.rest.resource.GovernanceResource;
import com.koostack.cveil.rest.resource.PlatformResource;
import com.koostack.cveil.rest.util.MetricsHelper;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.security.Principal;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Comprehensive RBAC unit tests for the Cveil REST API.
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>{@link Roles} utility class — requireRole, requireAnyRole, super-admin bypass, null context
 *       handling
 *   <li>{@link ConsentResource} — consent:read for evaluate/summary/history, consent:write for
 *       grant/revoke/batch operations
 *   <li>{@link CatalogueResource} — admin:read for listing fields, admin:write for
 *       register/remove/category endpoints
 *   <li>{@link GovernanceResource} — admin:read for listing purposes/regulations, admin:write for
 *       register/link endpoints
 *   <li>{@link PlatformResource} — super-admin requirement for all endpoints
 * </ul>
 */
@DisplayName("RBAC (Role-Based Access Control)")
class RbacTest {

    // -------------------------------------------------------------------
    // Shared state — requestHolder is injected into each resource
    // -------------------------------------------------------------------

    /**
     * Shared request holder that each resource's @BeforeEach injects. Updated by
     * securityContextWith() to match the role being tested.
     */
    private CveilRequestHolder requestHolder;

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    /**
     * Maps legacy role names to the equivalent permission set in the new DB-backed permissions
     * framework.
     */
    private static Set<String> permissionsForRoles(Set<String> roles) {
        Set<String> perms = new HashSet<>();
        if (roles.contains(Roles.CONSENT_WRITE)) {
            perms.add(Permissions.CONSENT_GRANT);
            perms.add(Permissions.CONSENT_REVOKE);
        }
        if (roles.contains(Roles.CONSENT_READ)) {
            perms.add(Permissions.CONSENT_READ);
            perms.add(Permissions.CONSENT_EVALUATE);
            perms.add(Permissions.CONSENT_EXPORT);
            perms.add(Permissions.CONSENT_DSAR);
            perms.add(Permissions.CONSENT_GRAPH_READ);
        }
        if (roles.contains(Roles.ADMIN_READ)) {
            perms.add(Permissions.CATALOGUE_READ);
            perms.add(Permissions.GOVERNANCE_READ);
            perms.add(Permissions.EVENTS_READ);
            perms.add(Permissions.REPORTS_READ);
        }
        if (roles.contains(Roles.ADMIN_WRITE)) {
            perms.add(Permissions.CATALOGUE_WRITE);
            perms.add(Permissions.GOVERNANCE_WRITE);
        }
        return perms;
    }

    /** Creates a SecurityContext backed by the given set of roles and configures authorization. */
    private SecurityContext securityContextWith(Set<String> roles) {
        if (requestHolder != null) {
            requestHolder.setAuthorization(
                    new AuthorizationContext(
                            "test-user", "test-tenant", roles, permissionsForRoles(roles)));
        }
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

    /** Creates a SecurityContext with a single role. */
    private SecurityContext securityContextWith(String role) {
        return securityContextWith(Set.of(role));
    }

    /** Creates a SecurityContext with no roles. */
    private SecurityContext emptySecurityContext() {
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
            @DisplayName("null SecurityContext → CveilAuthException")
            void nullContextThrows() {
                CveilAuthException ex =
                        assertThrows(
                                CveilAuthException.class,
                                () -> Roles.requireRole(null, Roles.CONSENT_READ));
                assertTrue(
                        ex.getMessage().contains("consent:read"),
                        "Error message should mention the required role");
            }

            @Test
            @DisplayName("empty roles → CveilAuthException")
            void noRolesThrows() {
                SecurityContext ctx = emptySecurityContext();
                assertThrows(
                        CveilAuthException.class,
                        () -> Roles.requireRole(ctx, Roles.CONSENT_WRITE));
            }

            @Test
            @DisplayName("wrong role → CveilAuthException")
            void wrongRoleThrows() {
                SecurityContext ctx = securityContextWith(Roles.ADMIN_READ);
                assertThrows(
                        CveilAuthException.class, () -> Roles.requireRole(ctx, Roles.CONSENT_READ));
            }

            @Test
            @DisplayName("matching role → no exception")
            void matchingRolePasses() {
                SecurityContext ctx = securityContextWith(Roles.CONSENT_READ);
                assertDoesNotThrow(() -> Roles.requireRole(ctx, Roles.CONSENT_READ));
            }

            @Test
            @DisplayName("super-admin bypasses any role check")
            void superAdminBypassesAll() {
                SecurityContext ctx = securityContextWith(Roles.SUPER_ADMIN);
                assertDoesNotThrow(() -> Roles.requireRole(ctx, Roles.CONSENT_READ));
                assertDoesNotThrow(() -> Roles.requireRole(ctx, Roles.CONSENT_WRITE));
                assertDoesNotThrow(() -> Roles.requireRole(ctx, Roles.ADMIN_READ));
                assertDoesNotThrow(() -> Roles.requireRole(ctx, Roles.ADMIN_WRITE));
                assertDoesNotThrow(() -> Roles.requireRole(ctx, Roles.SUPER_ADMIN));
            }

            @ParameterizedTest
            @ValueSource(
                    strings = {
                        "consent:read",
                        "consent:write",
                        "admin:read",
                        "admin:write",
                        "super-admin",
                    })
            @DisplayName("each role constant accepted when present")
            void eachRoleAccepted(String role) {
                SecurityContext ctx = securityContextWith(role);
                assertDoesNotThrow(() -> Roles.requireRole(ctx, role));
            }

            @Test
            @DisplayName("error message mentions required role name")
            void errorMessageFormat() {
                SecurityContext ctx = emptySecurityContext();
                CveilAuthException ex =
                        assertThrows(
                                CveilAuthException.class,
                                () -> Roles.requireRole(ctx, "admin:write"));
                assertTrue(ex.getMessage().contains("admin:write"));
                assertTrue(ex.getMessage().contains("Access denied"));
            }
        }

        @Nested
        @DisplayName("requireAnyRole()")
        class RequireAnyRole {

            @Test
            @DisplayName("null context → CveilAuthException")
            void nullContextThrows() {
                assertThrows(
                        CveilAuthException.class,
                        () -> Roles.requireAnyRole(null, Roles.CONSENT_READ, Roles.CONSENT_WRITE));
            }

            @Test
            @DisplayName("no matching role → CveilAuthException")
            void noMatchingRoleThrows() {
                SecurityContext ctx = securityContextWith(Roles.ADMIN_READ);
                assertThrows(
                        CveilAuthException.class,
                        () -> Roles.requireAnyRole(ctx, Roles.CONSENT_READ, Roles.CONSENT_WRITE));
            }

            @Test
            @DisplayName("first role matches → passes")
            void firstRoleMatches() {
                SecurityContext ctx = securityContextWith(Roles.CONSENT_READ);
                assertDoesNotThrow(
                        () -> Roles.requireAnyRole(ctx, Roles.CONSENT_READ, Roles.CONSENT_WRITE));
            }

            @Test
            @DisplayName("second role matches → passes")
            void secondRoleMatches() {
                SecurityContext ctx = securityContextWith(Roles.CONSENT_WRITE);
                assertDoesNotThrow(
                        () -> Roles.requireAnyRole(ctx, Roles.CONSENT_READ, Roles.CONSENT_WRITE));
            }

            @Test
            @DisplayName("super-admin bypasses → passes")
            void superAdminBypasses() {
                SecurityContext ctx = securityContextWith(Roles.SUPER_ADMIN);
                assertDoesNotThrow(
                        () -> Roles.requireAnyRole(ctx, Roles.CONSENT_READ, Roles.ADMIN_WRITE));
            }

            @Test
            @DisplayName("error message lists all required roles")
            void errorMessageListsRoles() {
                SecurityContext ctx = emptySecurityContext();
                CveilAuthException ex =
                        assertThrows(
                                CveilAuthException.class,
                                () ->
                                        Roles.requireAnyRole(
                                                ctx, Roles.CONSENT_READ, Roles.ADMIN_WRITE));
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
                assertThrows(
                        CveilAuthException.class, () -> Roles.requireRole(ctx, Roles.CONSENT_READ));
            }

            @Test
            @DisplayName("consent:read does NOT imply consent:write")
            void readDoesNotImplyWrite() {
                SecurityContext ctx = securityContextWith(Roles.CONSENT_READ);
                assertThrows(
                        CveilAuthException.class,
                        () -> Roles.requireRole(ctx, Roles.CONSENT_WRITE));
            }

            @Test
            @DisplayName("admin:write does NOT imply admin:read")
            void adminWriteDoesNotImplyRead() {
                SecurityContext ctx = securityContextWith(Roles.ADMIN_WRITE);
                assertThrows(
                        CveilAuthException.class, () -> Roles.requireRole(ctx, Roles.ADMIN_READ));
            }

            @Test
            @DisplayName("admin:read does NOT imply admin:write")
            void adminReadDoesNotImplyWrite() {
                SecurityContext ctx = securityContextWith(Roles.ADMIN_READ);
                assertThrows(
                        CveilAuthException.class, () -> Roles.requireRole(ctx, Roles.ADMIN_WRITE));
            }

            @Test
            @DisplayName("consent roles do NOT grant admin access")
            void consentDoesNotGrantAdmin() {
                SecurityContext ctx =
                        securityContextWith(Set.of(Roles.CONSENT_READ, Roles.CONSENT_WRITE));
                assertThrows(
                        CveilAuthException.class, () -> Roles.requireRole(ctx, Roles.ADMIN_READ));
                assertThrows(
                        CveilAuthException.class, () -> Roles.requireRole(ctx, Roles.ADMIN_WRITE));
            }

            @Test
            @DisplayName("admin roles do NOT grant consent access")
            void adminDoesNotGrantConsent() {
                SecurityContext ctx =
                        securityContextWith(Set.of(Roles.ADMIN_READ, Roles.ADMIN_WRITE));
                assertThrows(
                        CveilAuthException.class, () -> Roles.requireRole(ctx, Roles.CONSENT_READ));
                assertThrows(
                        CveilAuthException.class,
                        () -> Roles.requireRole(ctx, Roles.CONSENT_WRITE));
            }
        }

        @Nested
        @DisplayName("Multiple roles in context")
        class MultipleRoles {

            @Test
            @DisplayName("context with both consent roles satisfies either check")
            void bothConsentRoles() {
                SecurityContext ctx =
                        securityContextWith(Set.of(Roles.CONSENT_READ, Roles.CONSENT_WRITE));
                assertDoesNotThrow(() -> Roles.requireRole(ctx, Roles.CONSENT_READ));
                assertDoesNotThrow(() -> Roles.requireRole(ctx, Roles.CONSENT_WRITE));
            }

            @Test
            @DisplayName("context with all roles satisfies any check")
            void allRoles() {
                SecurityContext ctx =
                        securityContextWith(
                                Set.of(
                                        Roles.CONSENT_READ,
                                        Roles.CONSENT_WRITE,
                                        Roles.ADMIN_READ,
                                        Roles.ADMIN_WRITE));
                assertDoesNotThrow(() -> Roles.requireRole(ctx, Roles.CONSENT_READ));
                assertDoesNotThrow(() -> Roles.requireRole(ctx, Roles.CONSENT_WRITE));
                assertDoesNotThrow(() -> Roles.requireRole(ctx, Roles.ADMIN_READ));
                assertDoesNotThrow(() -> Roles.requireRole(ctx, Roles.ADMIN_WRITE));
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
        private CommandDispatcher consentDispatcher;
        private QueryDispatcher consentQueryDispatcher;

        @SuppressWarnings("unchecked")
        @BeforeEach
        void setUp() throws Exception {
            resource = new ConsentResource();
            consentDispatcher = mock(CommandDispatcher.class);
            consentQueryDispatcher = mock(QueryDispatcher.class);
            CveilConfig cveilConfig = mock(CveilConfig.class);

            requestHolder = new CveilRequestHolder();
            setField(resource, "requestHolder", requestHolder);
            setField(resource, "consentDispatcher", consentDispatcher);
            setField(resource, "consentQueryDispatcher", consentQueryDispatcher);
            setField(resource, "cveilConfig", cveilConfig);
            setField(resource, "metrics", mock(MetricsHelper.class));

            when(consentQueryDispatcher.dispatch(any()))
                    .thenAnswer(
                            inv -> {
                                Object input = inv.getArgument(0);
                                if (input.getClass().getSimpleName().equals("FindPolicyQuery")) {
                                    return new PurposeView.PolicyView(
                                            UUID.randomUUID(),
                                            "CATEGORY",
                                            new PurposeView.CategoryRef(UUID.randomUUID(), "pii"),
                                            null);
                                }
                                if (input.getClass()
                                        .getSimpleName()
                                        .equals("EvaluateConsentQuery")) {
                                    return new ConsentResponse(
                                            "subject-1",
                                            Collections.emptySet(),
                                            Collections.emptySet(),
                                            Collections.emptyMap(),
                                            Collections.emptyMap());
                                }
                                if (input.getClass()
                                        .getSimpleName()
                                        .equals("GetSubjectConsentQuery")) {
                                    return new SubjectConsentView(
                                            "subj-1", Collections.emptyList());
                                }
                                return null;
                            });
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
            BatchConsentEntryDto entry = new BatchConsentEntryDto(testScope(), null);
            return new BatchGrantRequest("subject-1", List.of(entry));
        }

        private BatchRevokeRequest testBatchRevokeRequest() {
            BatchConsentEntryDto entry = new BatchConsentEntryDto(testScope(), null);
            return new BatchRevokeRequest("subject-1", List.of(entry));
        }

        private EvaluateRequest testEvaluateRequest() {
            return new EvaluateRequest(Set.of(testScope()));
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
            @DisplayName("consent:read → 403 (CveilAuthException)")
            void consentReadDenied() {
                SecurityContext ctx = securityContextWith(Roles.CONSENT_READ);
                assertThrows(
                        CveilAuthException.class, () -> resource.grant(ctx, testGrantRequest()));
            }

            @Test
            @DisplayName("admin:write → 403")
            void adminWriteDenied() {
                SecurityContext ctx = securityContextWith(Roles.ADMIN_WRITE);
                assertThrows(
                        CveilAuthException.class, () -> resource.grant(ctx, testGrantRequest()));
            }

            @Test
            @DisplayName("no roles → 403")
            void noRolesDenied() {
                SecurityContext ctx = emptySecurityContext();
                assertThrows(
                        CveilAuthException.class, () -> resource.grant(ctx, testGrantRequest()));
            }

            @Test
            @DisplayName("null context → 403")
            void nullContextDenied() {
                assertThrows(
                        CveilAuthException.class, () -> resource.grant(null, testGrantRequest()));
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
                assertThrows(
                        CveilAuthException.class, () -> resource.revoke(ctx, testRevokeRequest()));
            }

            @Test
            @DisplayName("no roles → 403")
            void noRolesDenied() {
                assertThrows(
                        CveilAuthException.class,
                        () -> resource.revoke(emptySecurityContext(), testRevokeRequest()));
            }
        }

        // -- grantAll() --

        @Nested
        @DisplayName("POST /subjects/consent/grant-all")
        class GrantAll {

            @Test
            @DisplayName("consent:write → 200")
            void consentWriteAllowed() {
                SecurityContext ctx = securityContextWith(Roles.CONSENT_WRITE);
                Response response = resource.grantAll(ctx, testBatchGrantRequest());
                assertEquals(200, response.getStatus());
            }

            @Test
            @DisplayName("super-admin → 200")
            void superAdminAllowed() {
                SecurityContext ctx = securityContextWith(Roles.SUPER_ADMIN);
                Response response = resource.grantAll(ctx, testBatchGrantRequest());
                assertEquals(200, response.getStatus());
            }

            @Test
            @DisplayName("consent:read → 403")
            void consentReadDenied() {
                SecurityContext ctx = securityContextWith(Roles.CONSENT_READ);
                assertThrows(
                        CveilAuthException.class,
                        () -> resource.grantAll(ctx, testBatchGrantRequest()));
            }

            @Test
            @DisplayName("no roles → 403")
            void noRolesDenied() {
                assertThrows(
                        CveilAuthException.class,
                        () -> resource.grantAll(emptySecurityContext(), testBatchGrantRequest()));
            }
        }

        // -- revokeAll() --

        @Nested
        @DisplayName("POST /subjects/consent/revoke-all")
        class RevokeAll {

            @Test
            @DisplayName("consent:write → 200")
            void consentWriteAllowed() {
                SecurityContext ctx = securityContextWith(Roles.CONSENT_WRITE);
                Response response = resource.revokeAll(ctx, testBatchRevokeRequest());
                assertEquals(200, response.getStatus());
            }

            @Test
            @DisplayName("consent:read → 403")
            void consentReadDenied() {
                SecurityContext ctx = securityContextWith(Roles.CONSENT_READ);
                assertThrows(
                        CveilAuthException.class,
                        () -> resource.revokeAll(ctx, testBatchRevokeRequest()));
            }

            @Test
            @DisplayName("super-admin → 200")
            void superAdminAllowed() {
                SecurityContext ctx = securityContextWith(Roles.SUPER_ADMIN);
                Response response = resource.revokeAll(ctx, testBatchRevokeRequest());
                assertEquals(200, response.getStatus());
            }
        }

        // -- evaluate() --

        @Nested
        @DisplayName("POST /subjects/consent/evaluate")
        class Evaluate {

            @Test
            @DisplayName("consent:read → 200")
            void consentReadAllowed() {
                SecurityContext ctx = securityContextWith(Roles.CONSENT_READ);
                Response response = resource.evaluate(ctx, "subject-1", testEvaluateRequest());
                assertEquals(200, response.getStatus());
            }

            @Test
            @DisplayName("super-admin → 200")
            void superAdminAllowed() {
                SecurityContext ctx = securityContextWith(Roles.SUPER_ADMIN);
                Response response = resource.evaluate(ctx, "subject-1", testEvaluateRequest());
                assertEquals(200, response.getStatus());
            }

            @Test
            @DisplayName("consent:write → 403 (read-only endpoint)")
            void consentWriteDenied() {
                SecurityContext ctx = securityContextWith(Roles.CONSENT_WRITE);
                assertThrows(
                        CveilAuthException.class,
                        () -> resource.evaluate(ctx, "subject-1", testEvaluateRequest()));
            }

            @Test
            @DisplayName("admin:read → 403")
            void adminReadDenied() {
                SecurityContext ctx = securityContextWith(Roles.ADMIN_READ);
                assertThrows(
                        CveilAuthException.class,
                        () -> resource.evaluate(ctx, "subject-1", testEvaluateRequest()));
            }

            @Test
            @DisplayName("no roles → 403")
            void noRolesDenied() {
                assertThrows(
                        CveilAuthException.class,
                        () ->
                                resource.evaluate(
                                        emptySecurityContext(),
                                        "subject-1",
                                        testEvaluateRequest()));
            }

            @Test
            @DisplayName("null context → 403")
            void nullContextDenied() {
                assertThrows(
                        CveilAuthException.class,
                        () -> resource.evaluate(null, "subject-1", testEvaluateRequest()));
            }
        }

        // -- subjectSummary() --

        @Nested
        @DisplayName("GET /subjects/{id}/summary")
        class SubjectSummary {

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
                assertThrows(
                        CveilAuthException.class, () -> resource.subjectSummary(ctx, "subj-1"));
            }

            @Test
            @DisplayName("no roles → 403")
            void noRolesDenied() {
                assertThrows(
                        CveilAuthException.class,
                        () -> resource.subjectSummary(emptySecurityContext(), "subj-1"));
            }
        }

        // -- subjectHistory() --

        @Nested
        @DisplayName("GET /subjects/{id}/history")
        class SubjectHistory {

            @Test
            @DisplayName("consent:read → 200")
            void consentReadAllowed() {
                SecurityContext ctx = securityContextWith(Roles.CONSENT_READ);
                Response response = resource.subjectHistory(ctx, "subj-1");
                assertEquals(200, response.getStatus());
            }

            @Test
            @DisplayName("super-admin → 200")
            void superAdminAllowed() {
                SecurityContext ctx = securityContextWith(Roles.SUPER_ADMIN);
                Response response = resource.subjectHistory(ctx, "subj-1");
                assertEquals(200, response.getStatus());
            }

            @Test
            @DisplayName("consent:write → 403")
            void consentWriteDenied() {
                SecurityContext ctx = securityContextWith(Roles.CONSENT_WRITE);
                assertThrows(
                        CveilAuthException.class, () -> resource.subjectHistory(ctx, "subj-1"));
            }

            @Test
            @DisplayName("no roles → 403")
            void noRolesDenied() {
                assertThrows(
                        CveilAuthException.class,
                        () -> resource.subjectHistory(emptySecurityContext(), "subj-1"));
            }
        }

        // -- Cross-cutting consent tests --

        @Nested
        @DisplayName("Cross-cutting consent role checks")
        class ConsentCrossCutting {

            @Test
            @DisplayName("admin:read cannot access any consent endpoint")
            void adminReadCannotAccessConsent() {
                SecurityContext ctx = securityContextWith(Roles.ADMIN_READ);
                assertThrows(
                        CveilAuthException.class, () -> resource.grant(ctx, testGrantRequest()));
                assertThrows(
                        CveilAuthException.class, () -> resource.revoke(ctx, testRevokeRequest()));
                assertThrows(
                        CveilAuthException.class,
                        () -> resource.grantAll(ctx, testBatchGrantRequest()));
                assertThrows(
                        CveilAuthException.class,
                        () -> resource.revokeAll(ctx, testBatchRevokeRequest()));
                assertThrows(
                        CveilAuthException.class,
                        () -> resource.evaluate(ctx, "subj-1", testEvaluateRequest()));
                assertThrows(
                        CveilAuthException.class, () -> resource.subjectSummary(ctx, "subj-1"));
                assertThrows(
                        CveilAuthException.class, () -> resource.subjectHistory(ctx, "subj-1"));
            }

            @Test
            @DisplayName("admin:write cannot access any consent endpoint")
            void adminWriteCannotAccessConsent() {
                SecurityContext ctx = securityContextWith(Roles.ADMIN_WRITE);
                assertThrows(
                        CveilAuthException.class, () -> resource.grant(ctx, testGrantRequest()));
                assertThrows(
                        CveilAuthException.class,
                        () -> resource.evaluate(ctx, "subj-1", testEvaluateRequest()));
            }

            @Test
            @DisplayName("both consent roles → full consent access")
            void bothConsentRolesGrantFullAccess() {
                SecurityContext ctx =
                        securityContextWith(Set.of(Roles.CONSENT_READ, Roles.CONSENT_WRITE));
                // Write endpoints
                assertDoesNotThrow(() -> resource.grant(ctx, testGrantRequest()));
                assertDoesNotThrow(() -> resource.revoke(ctx, testRevokeRequest()));
                assertDoesNotThrow(() -> resource.grantAll(ctx, testBatchGrantRequest()));
                assertDoesNotThrow(() -> resource.revokeAll(ctx, testBatchRevokeRequest()));
                // Read endpoints
                assertDoesNotThrow(() -> resource.evaluate(ctx, "subj-1", testEvaluateRequest()));
                assertDoesNotThrow(() -> resource.subjectSummary(ctx, "subj-1"));
                assertDoesNotThrow(() -> resource.subjectHistory(ctx, "subj-1"));
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
        private CommandDispatcher consentDispatcher;
        private QueryDispatcher consentQueryDispatcher;

        @SuppressWarnings("unchecked")
        @BeforeEach
        void setUp() throws Exception {
            resource = new CatalogueResource();
            consentDispatcher = mock(CommandDispatcher.class);
            consentQueryDispatcher = mock(QueryDispatcher.class);

            when(consentQueryDispatcher.dispatch(any())).thenReturn(Collections.emptyList());

            requestHolder = new CveilRequestHolder();
            setField(resource, "requestHolder", requestHolder);
            setField(resource, "consentDispatcher", consentDispatcher);
            setField(resource, "consentQueryDispatcher", consentQueryDispatcher);
            setField(resource, "metrics", mock(MetricsHelper.class));
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
                assertThrows(CveilAuthException.class, () -> resource.listFields(ctx));
            }

            @Test
            @DisplayName("consent:read → 403")
            void consentReadDenied() {
                SecurityContext ctx = securityContextWith(Roles.CONSENT_READ);
                assertThrows(CveilAuthException.class, () -> resource.listFields(ctx));
            }

            @Test
            @DisplayName("no roles → 403")
            void noRolesDenied() {
                assertThrows(
                        CveilAuthException.class,
                        () -> resource.listFields(emptySecurityContext()));
            }

            @Test
            @DisplayName("null context → 403")
            void nullContextDenied() {
                assertThrows(CveilAuthException.class, () -> resource.listFields(null));
            }
        }

        // -- Write endpoints --

        @Nested
        @DisplayName("POST /catalogue/fields (registerField)")
        class RegisterField {

            private RegisterFieldRequest request() {
                return new RegisterFieldRequest(
                        "email", "user", "User email address", "User entity");
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
                assertThrows(
                        CveilAuthException.class, () -> resource.registerField(ctx, request()));
            }

            @Test
            @DisplayName("consent:write → 403")
            void consentWriteDenied() {
                SecurityContext ctx = securityContextWith(Roles.CONSENT_WRITE);
                assertThrows(
                        CveilAuthException.class, () -> resource.registerField(ctx, request()));
            }

            @Test
            @DisplayName("no roles → 403")
            void noRolesDenied() {
                assertThrows(
                        CveilAuthException.class,
                        () -> resource.registerField(emptySecurityContext(), request()));
            }

            @Test
            @DisplayName("null context → 403")
            void nullContextDenied() {
                assertThrows(
                        CveilAuthException.class, () -> resource.registerField(null, request()));
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
                Response response = resource.assignFieldCategory(ctx, request());
                assertEquals(200, response.getStatus());
            }

            @Test
            @DisplayName("super-admin → 200")
            void superAdminAllowed() {
                SecurityContext ctx = securityContextWith(Roles.SUPER_ADMIN);
                Response response = resource.assignFieldCategory(ctx, request());
                assertEquals(200, response.getStatus());
            }

            @Test
            @DisplayName("admin:read → 403")
            void adminReadDenied() {
                SecurityContext ctx = securityContextWith(Roles.ADMIN_READ);
                assertThrows(
                        CveilAuthException.class,
                        () -> resource.assignFieldCategory(ctx, request()));
            }

            @Test
            @DisplayName("no roles → 403")
            void noRolesDenied() {
                assertThrows(
                        CveilAuthException.class,
                        () -> resource.assignFieldCategory(emptySecurityContext(), request()));
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
                assertThrows(CveilAuthException.class, () -> resource.listFields(ctx));
                assertThrows(
                        CveilAuthException.class,
                        () ->
                                resource.registerField(
                                        ctx, new RegisterFieldRequest("f", "e", "d", "ed")));
            }

            @Test
            @DisplayName("consent:write cannot access any catalogue endpoint")
            void consentWriteCannotAccessCatalogue() {
                SecurityContext ctx = securityContextWith(Roles.CONSENT_WRITE);
                assertThrows(CveilAuthException.class, () -> resource.listFields(ctx));
                assertThrows(
                        CveilAuthException.class,
                        () ->
                                resource.registerField(
                                        ctx, new RegisterFieldRequest("f", "e", "d", "ed")));
            }

            @Test
            @DisplayName("both admin roles → full catalogue access")
            void bothAdminRolesGrantFullAccess() {
                SecurityContext ctx =
                        securityContextWith(Set.of(Roles.ADMIN_READ, Roles.ADMIN_WRITE));
                assertDoesNotThrow(() -> resource.listFields(ctx));
                assertDoesNotThrow(
                        () ->
                                resource.registerField(
                                        ctx, new RegisterFieldRequest("f", "e", "d", "ed")));
                assertDoesNotThrow(
                        () ->
                                resource.assignFieldCategory(
                                        ctx,
                                        new AssignFieldCategoryRequest("f", "e", "c", "desc")));
            }

            @Test
            @DisplayName("super-admin → full catalogue access (read + write)")
            void superAdminFullCatalogueAccess() {
                SecurityContext ctx = securityContextWith(Roles.SUPER_ADMIN);
                assertDoesNotThrow(() -> resource.listFields(ctx));
                assertDoesNotThrow(
                        () ->
                                resource.registerField(
                                        ctx, new RegisterFieldRequest("f", "e", "d", "ed")));
            }
        }
    }

    // -------------------------------------------------------------------
    // GovernanceResource RBAC tests
    // -------------------------------------------------------------------

    @Nested
    @DisplayName("GovernanceResource RBAC")
    class GovernanceResourceRbac {

        private GovernanceResource resource;
        private CommandDispatcher consentDispatcher;
        private QueryDispatcher consentQueryDispatcher;

        @SuppressWarnings("unchecked")
        @BeforeEach
        void setUp() throws Exception {
            resource = new GovernanceResource();
            consentDispatcher = mock(CommandDispatcher.class);
            consentQueryDispatcher = mock(QueryDispatcher.class);

            when(consentQueryDispatcher.dispatch(any())).thenReturn(Collections.emptyList());

            requestHolder = new CveilRequestHolder();
            setField(resource, "requestHolder", requestHolder);
            setField(resource, "consentDispatcher", consentDispatcher);
            setField(resource, "consentQueryDispatcher", consentQueryDispatcher);
            setField(resource, "metrics", mock(MetricsHelper.class));
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
                assertThrows(
                        CveilAuthException.class,
                        () -> resource.listPurposes(emptySecurityContext()));
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
                assertThrows(
                        CveilAuthException.class,
                        () -> resource.listRegulations(emptySecurityContext()));
            }
        }

        // -- Write endpoints --

        @Nested
        @DisplayName("POST /legal/purposes (registerPurpose)")
        class RegisterPurpose {

            private RegisterPurposeRequest request() {
                return new RegisterPurposeRequest("marketing", "Marketing communications");
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
                assertThrows(
                        CveilAuthException.class, () -> resource.registerPurpose(ctx, request()));
            }
        }

        @Nested
        @DisplayName("POST /legal/regulations (registerRegulation)")
        class RegisterRegulation {

            private RegisterRegulationRequest request() {
                return new RegisterRegulationRequest("gdpr", "General Data Protection Regulation");
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
                assertThrows(
                        CveilAuthException.class,
                        () -> resource.registerRegulation(ctx, request()));
            }

            @Test
            @DisplayName("no roles → 403")
            void noRolesDenied() {
                assertThrows(
                        CveilAuthException.class,
                        () -> resource.registerRegulation(emptySecurityContext(), request()));
            }
        }

        @Nested
        @DisplayName("POST /legal/purposes/links (linkPurposeToRegulation)")
        class LinkPurposeRegulation {

            private LinkPurposeRegulationRequest request() {
                return new LinkPurposeRegulationRequest(
                        "marketing", "gdpr", "consent", "Granted action", "Denied action");
            }

            @Test
            @DisplayName("admin:write → 200")
            void adminWriteAllowed() {
                SecurityContext ctx = securityContextWith(Roles.ADMIN_WRITE);
                Response response = resource.linkPurposeToRegulation(ctx, request());
                assertEquals(200, response.getStatus());
            }

            @Test
            @DisplayName("admin:read → 403")
            void adminReadDenied() {
                SecurityContext ctx = securityContextWith(Roles.ADMIN_READ);
                assertThrows(
                        CveilAuthException.class,
                        () -> resource.linkPurposeToRegulation(ctx, request()));
            }

            @Test
            @DisplayName("no roles → 403")
            void noRolesDenied() {
                assertThrows(
                        CveilAuthException.class,
                        () -> resource.linkPurposeToRegulation(emptySecurityContext(), request()));
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
                assertThrows(CveilAuthException.class, () -> resource.listPurposes(ctx));
                assertThrows(CveilAuthException.class, () -> resource.listRegulations(ctx));
            }

            @Test
            @DisplayName("consent:write cannot access any legal endpoint")
            void consentWriteCannotAccessLegal() {
                SecurityContext ctx = securityContextWith(Roles.CONSENT_WRITE);
                assertThrows(CveilAuthException.class, () -> resource.listPurposes(ctx));
                assertThrows(
                        CveilAuthException.class,
                        () ->
                                resource.registerPurpose(
                                        ctx, new RegisterPurposeRequest("p", "desc")));
            }

            @Test
            @DisplayName("both admin roles → full legal access")
            void bothAdminRolesGrantFullAccess() {
                SecurityContext ctx =
                        securityContextWith(Set.of(Roles.ADMIN_READ, Roles.ADMIN_WRITE));
                assertDoesNotThrow(() -> resource.listPurposes(ctx));
                assertDoesNotThrow(() -> resource.listRegulations(ctx));
                assertDoesNotThrow(
                        () ->
                                resource.registerPurpose(
                                        ctx, new RegisterPurposeRequest("p", "desc")));
                assertDoesNotThrow(
                        () ->
                                resource.registerRegulation(
                                        ctx, new RegisterRegulationRequest("r", "desc")));
                assertDoesNotThrow(
                        () ->
                                resource.linkPurposeToRegulation(
                                        ctx,
                                        new LinkPurposeRegulationRequest(
                                                "p", "r", "consent", "granted", "denied")));
            }

            @Test
            @DisplayName("super-admin → full legal access (read + write)")
            void superAdminFullLegalAccess() {
                SecurityContext ctx = securityContextWith(Roles.SUPER_ADMIN);
                assertDoesNotThrow(() -> resource.listPurposes(ctx));
                assertDoesNotThrow(() -> resource.listRegulations(ctx));
                assertDoesNotThrow(
                        () ->
                                resource.registerPurpose(
                                        ctx, new RegisterPurposeRequest("p", "desc")));
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
        private PlatformCqrsHolder cqrsHolder;

        @SuppressWarnings("rawtypes")
        private CoreQueryService coreQueryService;

        @SuppressWarnings("unchecked")
        @BeforeEach
        void setUp() throws Exception {
            resource = new PlatformResource();
            coreQueryService = mock(CoreQueryService.class);

            // Create PlatformCqrsHolder mock
            cqrsHolder = mock(PlatformCqrsHolder.class);
            when(cqrsHolder.isCqrsAvailable()).thenReturn(true);
            doReturn(mock(CoreCommandService.class)).when(cqrsHolder).coreCommandService();
            doReturn(coreQueryService).when(cqrsHolder).coreQueryService();
            doReturn(mock(CommandDispatcher.class)).when(cqrsHolder).iamDispatcher();
            doReturn(mock(IamQueryService.class)).when(cqrsHolder).iamQueryService();
            doReturn(mock(CommandDispatcher.class)).when(cqrsHolder).coreDispatcher();
            doReturn(mock(CommandDispatcher.class)).when(cqrsHolder).billingDispatcher();
            doReturn(mock(QueryDispatcher.class)).when(cqrsHolder).coreQueryDispatcher();
            doReturn(mock(com.koostack.cveil.core.platform.billing.BillingCommandService.class))
                    .when(cqrsHolder)
                    .billingCommandService();

            // Inject via reflection
            requestHolder = new CveilRequestHolder();
            setField(resource, "requestHolder", requestHolder);
            setField(resource, "cqrs", cqrsHolder);
            setField(resource, "metrics", mock(MetricsHelper.class));
            setField(resource, "rateLimitService", mock(RateLimitService.class));
        }

        @Nested
        @DisplayName("POST /platform/tenants (createTenant)")
        class CreateTenant {

            @Test
            @DisplayName("super-admin → 501")
            void superAdminAllowed() {
                SecurityContext ctx = securityContextWith(Roles.SUPER_ADMIN);
                CreateTenantRequest request =
                        new CreateTenantRequest("tenant-1", "Tenant One", "free", null);
                Response response = resource.createTenant(ctx, request);
                assertEquals(501, response.getStatus());
            }

            @Test
            @DisplayName("consent:write → 403")
            void consentWriteDenied() {
                SecurityContext ctx = securityContextWith(Roles.CONSENT_WRITE);
                CreateTenantRequest request =
                        new CreateTenantRequest("tenant-1", "Tenant One", "free", null);
                assertThrows(CveilAuthException.class, () -> resource.createTenant(ctx, request));
            }

            @Test
            @DisplayName("admin:write → 403")
            void adminWriteDenied() {
                SecurityContext ctx = securityContextWith(Roles.ADMIN_WRITE);
                CreateTenantRequest request =
                        new CreateTenantRequest("tenant-1", "Tenant One", "free", null);
                assertThrows(CveilAuthException.class, () -> resource.createTenant(ctx, request));
            }

            @Test
            @DisplayName("no roles → 403")
            void noRolesDenied() {
                CreateTenantRequest request =
                        new CreateTenantRequest("tenant-1", "Tenant One", "free", null);
                assertThrows(
                        CveilAuthException.class,
                        () -> resource.createTenant(emptySecurityContext(), request));
            }

            @Test
            @DisplayName("null context → 403")
            void nullContextDenied() {
                CreateTenantRequest request =
                        new CreateTenantRequest("tenant-1", "Tenant One", "free", null);
                assertThrows(CveilAuthException.class, () -> resource.createTenant(null, request));
            }
        }

        @Nested
        @DisplayName("GET /platform/tenants (listTenants)")
        class ListTenants {

            @Test
            @DisplayName("super-admin → 200")
            void superAdminAllowed() {
                SecurityContext ctx = securityContextWith(Roles.SUPER_ADMIN);
                when(cqrsHolder.coreQueryDispatcher().dispatch(any()))
                        .thenReturn(Collections.emptyList());
                Response response = resource.listTenants(ctx);
                assertEquals(200, response.getStatus());
            }

            @Test
            @DisplayName("admin:read → 403")
            void adminReadDenied() {
                SecurityContext ctx = securityContextWith(Roles.ADMIN_READ);
                assertThrows(CveilAuthException.class, () -> resource.listTenants(ctx));
            }

            @Test
            @DisplayName("consent:read → 403")
            void consentReadDenied() {
                SecurityContext ctx = securityContextWith(Roles.CONSENT_READ);
                assertThrows(CveilAuthException.class, () -> resource.listTenants(ctx));
            }

            @Test
            @DisplayName("all non-super-admin roles → 403")
            void allNonSuperAdminRolesDenied() {
                SecurityContext ctx =
                        securityContextWith(
                                Set.of(
                                        Roles.CONSENT_READ,
                                        Roles.CONSENT_WRITE,
                                        Roles.ADMIN_READ,
                                        Roles.ADMIN_WRITE));
                assertThrows(CveilAuthException.class, () -> resource.listTenants(ctx));
            }
        }

        @Nested
        @DisplayName("GET /platform/tenants/{id} (getTenant)")
        class GetTenant {

            @Test
            @DisplayName("super-admin → 200")
            void superAdminAllowed() {
                SecurityContext ctx = securityContextWith(Roles.SUPER_ADMIN);
                var tenantInfo =
                        new com.koostack.cveil.core.platform.core.models.TenantInfo(
                                "t1", "Tenant", "free", "active", Instant.now(), null);
                when(cqrsHolder.coreQueryDispatcher().dispatch(any())).thenReturn(tenantInfo);
                Response response = resource.getTenant(ctx, "t1");
                assertEquals(200, response.getStatus());
            }

            @Test
            @DisplayName("admin:read → 403")
            void adminReadDenied() {
                SecurityContext ctx = securityContextWith(Roles.ADMIN_READ);
                assertThrows(CveilAuthException.class, () -> resource.getTenant(ctx, "t1"));
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
                assertThrows(CveilAuthException.class, () -> resource.deleteTenant(ctx, "t1"));
            }
        }

        @Nested
        @DisplayName("Cross-cutting platform checks")
        class PlatformCrossCutting {

            @Test
            @DisplayName("only super-admin can access platform endpoints")
            void onlySuperAdminCanAccess() {
                // Try every non-super-admin role individually
                for (String role :
                        List.of(
                                Roles.CONSENT_READ,
                                Roles.CONSENT_WRITE,
                                Roles.ADMIN_READ,
                                Roles.ADMIN_WRITE)) {
                    SecurityContext ctx = securityContextWith(role);
                    assertThrows(
                            CveilAuthException.class,
                            () -> resource.listTenants(ctx),
                            "Role '" + role + "' should not access listTenants");
                    assertThrows(
                            CveilAuthException.class,
                            () -> resource.getTenant(ctx, "t1"),
                            "Role '" + role + "' should not access getTenant");
                    assertThrows(
                            CveilAuthException.class,
                            () -> resource.deleteTenant(ctx, "t1"),
                            "Role '" + role + "' should not access deleteTenant");
                    assertThrows(
                            CveilAuthException.class,
                            () ->
                                    resource.createTenant(
                                            ctx, new CreateTenantRequest("t1", "T", "free", null)),
                            "Role '" + role + "' should not access createTenant");
                }
            }

            @Test
            @DisplayName("combined non-super-admin roles still denied")
            void combinedNonSuperAdminDenied() {
                SecurityContext ctx =
                        securityContextWith(
                                Set.of(
                                        Roles.CONSENT_READ,
                                        Roles.CONSENT_WRITE,
                                        Roles.ADMIN_READ,
                                        Roles.ADMIN_WRITE));
                assertThrows(CveilAuthException.class, () -> resource.listTenants(ctx));
                assertThrows(
                        CveilAuthException.class,
                        () ->
                                resource.createTenant(
                                        ctx, new CreateTenantRequest("t1", "T", "free", null)));
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
         * Validates that the exception thrown for unauthorized access is specifically
         * CveilAuthException (which maps to 403), and that the message contains "Access denied".
         */
        @Test
        @DisplayName("unauthorized responses have consistent error format")
        void unauthorizedResponseFormat() {
            SecurityContext noRoles = emptySecurityContext();

            CveilAuthException ex;

            ex =
                    assertThrows(
                            CveilAuthException.class,
                            () -> Roles.requireRole(noRoles, Roles.CONSENT_READ));
            assertTrue(ex.getMessage().startsWith("Access denied"));
            assertTrue(ex.getMessage().contains("consent:read"));

            ex =
                    assertThrows(
                            CveilAuthException.class,
                            () -> Roles.requireRole(noRoles, Roles.CONSENT_WRITE));
            assertTrue(ex.getMessage().contains("consent:write"));

            ex =
                    assertThrows(
                            CveilAuthException.class,
                            () -> Roles.requireRole(noRoles, Roles.ADMIN_READ));
            assertTrue(ex.getMessage().contains("admin:read"));

            ex =
                    assertThrows(
                            CveilAuthException.class,
                            () -> Roles.requireRole(noRoles, Roles.ADMIN_WRITE));
            assertTrue(ex.getMessage().contains("admin:write"));

            ex =
                    assertThrows(
                            CveilAuthException.class,
                            () -> Roles.requireRole(noRoles, Roles.SUPER_ADMIN));
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
                        "super-admin should satisfy requirement for: " + role);
            }
        }

        @Test
        @DisplayName("requireAnyRole with empty varargs → throws for non-super-admin")
        void requireAnyRoleEmptyVarargs() {
            // edge case: no roles specified — should throw
            SecurityContext ctx = securityContextWith(Roles.CONSENT_READ);
            assertThrows(CveilAuthException.class, () -> Roles.requireAnyRole(ctx));
        }

        @Test
        @DisplayName("requireAnyRole with empty varargs → passes for super-admin")
        void requireAnyRoleEmptyVarargsSuperAdmin() {
            SecurityContext ctx = securityContextWith(Roles.SUPER_ADMIN);
            assertDoesNotThrow(() -> Roles.requireAnyRole(ctx));
        }
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
