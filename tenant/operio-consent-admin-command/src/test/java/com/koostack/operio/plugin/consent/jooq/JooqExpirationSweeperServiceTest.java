package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.tenant.consent.ConsentAdminCommandService;
import com.koostack.operio.core.tenant.consent.ConsentAdminQueryService;
import com.koostack.operio.core.models.ConsentScope;
import com.koostack.operio.core.models.PolicySummaryEntry;
import com.koostack.operio.core.models.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link JooqExpirationSweeperService}.
 */
class JooqExpirationSweeperServiceTest {

    private static final String TENANT = "test-tenant";

    private ConsentAdminQueryService<?> queryService;
    private ConsentAdminCommandService<?> commandService;
    private JooqExpirationSweeperService sweeper;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        queryService = mock(ConsentAdminQueryService.class);
        commandService = mock(ConsentAdminCommandService.class);
        sweeper = new JooqExpirationSweeperService(
            queryService, commandService, () -> List.of(TENANT)
        );
    }

    @Nested
    class Sweep {

        @Test
        void noExpiredPoliciesReturnsZero() throws Exception {
            when(queryService.findExpiredPolicies(any(Instant.class)))
                .thenReturn(List.of());

            int result = TenantContext.callWhere(TENANT, sweeper::sweep);

            assertEquals(0, result);
            verify(commandService, never()).revokeConsentCommand(any(), any());
        }

        @Test
        void revokesExpiredFieldScopedPolicy() throws Exception {
            var expired = new PolicySummaryEntry(
                "alice", "email", "User", null, "marketing",
                "GRANTED", Instant.now().minusSeconds(60), Instant.now().minusSeconds(3600)
            );
            when(queryService.findExpiredPolicies(any(Instant.class)))
                .thenReturn(List.of(expired));

            int result = TenantContext.callWhere(TENANT, sweeper::sweep);

            assertEquals(1, result);
            var scopeCaptor = ArgumentCaptor.forClass(ConsentScope.class);
            verify(commandService).revokeConsentCommand(eq("alice"), scopeCaptor.capture());

            ConsentScope revokedScope = scopeCaptor.getValue();
            assertTrue(revokedScope.isFieldScoped());
            assertEquals("email", revokedScope.fieldName());
            assertEquals("User", revokedScope.entityName());
            assertEquals("marketing", revokedScope.purposeName());
        }

        @Test
        void revokesExpiredCategoryScopedPolicy() throws Exception {
            var expired = new PolicySummaryEntry(
                "bob", null, null, "PII", "analytics",
                "GRANTED", Instant.now().minusSeconds(60), Instant.now().minusSeconds(3600)
            );
            when(queryService.findExpiredPolicies(any(Instant.class)))
                .thenReturn(List.of(expired));

            int result = TenantContext.callWhere(TENANT, sweeper::sweep);

            assertEquals(1, result);
            var scopeCaptor = ArgumentCaptor.forClass(ConsentScope.class);
            verify(commandService).revokeConsentCommand(eq("bob"), scopeCaptor.capture());

            ConsentScope revokedScope = scopeCaptor.getValue();
            assertTrue(revokedScope.isCategoryScoped());
            assertEquals("PII", revokedScope.category());
            assertEquals("analytics", revokedScope.purposeName());
        }

        @Test
        void revokesMultipleExpiredPolicies() throws Exception {
            var expired1 = new PolicySummaryEntry(
                "alice", "email", "User", null, "marketing",
                "GRANTED", Instant.now().minusSeconds(60), Instant.now().minusSeconds(3600)
            );
            var expired2 = new PolicySummaryEntry(
                "bob", null, null, "PII", "analytics",
                "GRANTED", Instant.now().minusSeconds(30), Instant.now().minusSeconds(7200)
            );
            when(queryService.findExpiredPolicies(any(Instant.class)))
                .thenReturn(List.of(expired1, expired2));

            int result = TenantContext.callWhere(TENANT, sweeper::sweep);

            assertEquals(2, result);
            verify(commandService, times(2)).revokeConsentCommand(any(), any());
        }

        @Test
        void continuesOnIndividualRevocationFailure() throws Exception {
            var expired1 = new PolicySummaryEntry(
                "alice", "email", "User", null, "marketing",
                "GRANTED", Instant.now().minusSeconds(60), Instant.now().minusSeconds(3600)
            );
            var expired2 = new PolicySummaryEntry(
                "bob", "phone", "User", null, "analytics",
                "GRANTED", Instant.now().minusSeconds(30), Instant.now().minusSeconds(7200)
            );
            when(queryService.findExpiredPolicies(any(Instant.class)))
                .thenReturn(List.of(expired1, expired2));

            // First revoke throws, second succeeds
            doThrow(new RuntimeException("transient error"))
                .doNothing()
                .when(commandService).revokeConsentCommand(any(), any());

            int result = TenantContext.callWhere(TENANT, sweeper::sweep);

            assertEquals(1, result); // only second succeeded
            verify(commandService, times(2)).revokeConsentCommand(any(), any());
        }

        @Test
        void nullPurposeFallsBackToWildcard() throws Exception {
            var expired = new PolicySummaryEntry(
                "alice", "email", "User", null, null,
                "GRANTED", Instant.now().minusSeconds(60), Instant.now().minusSeconds(3600)
            );
            when(queryService.findExpiredPolicies(any(Instant.class)))
                .thenReturn(List.of(expired));

            int result = TenantContext.callWhere(TENANT, sweeper::sweep);

            assertEquals(1, result);
            var scopeCaptor = ArgumentCaptor.forClass(ConsentScope.class);
            verify(commandService).revokeConsentCommand(eq("alice"), scopeCaptor.capture());

            ConsentScope scope = scopeCaptor.getValue();
            assertTrue(scope.isFieldScoped());
            assertEquals("email", scope.fieldName());
            assertEquals(ConsentScope.WILDCARD, scope.purposeName());
        }

        @Test
        void bothFieldAndCategoryPrefersField() throws Exception {
            var expired = new PolicySummaryEntry(
                "alice", "email", "User", "PII", "marketing",
                "GRANTED", Instant.now().minusSeconds(60), Instant.now().minusSeconds(3600)
            );
            when(queryService.findExpiredPolicies(any(Instant.class)))
                .thenReturn(List.of(expired));

            int result = TenantContext.callWhere(TENANT, sweeper::sweep);

            assertEquals(1, result);
            var scopeCaptor = ArgumentCaptor.forClass(ConsentScope.class);
            verify(commandService).revokeConsentCommand(eq("alice"), scopeCaptor.capture());

            ConsentScope scope = scopeCaptor.getValue();
            assertTrue(scope.isFieldScoped());
            assertEquals("email", scope.fieldName());
            assertEquals("User", scope.entityName());
            assertEquals("marketing", scope.purposeName());
        }
    }

    @Nested
    class SweepAll {

        @Test
        void sweepsAcrossAllTenants() {
            sweeper = new JooqExpirationSweeperService(
                queryService, commandService,
                () -> List.of("tenant-a", "tenant-b")
            );

            when(queryService.findExpiredPolicies(any(Instant.class)))
                .thenReturn(List.of());

            int total = sweeper.sweepAll();

            assertEquals(0, total);
            // findExpiredPolicies called once per tenant
            verify(queryService, times(2)).findExpiredPolicies(any(Instant.class));
        }
    }
}
