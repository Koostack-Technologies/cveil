package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.tenant.consent.ConsentAdminCommandService;
import com.koostack.operio.core.tenant.consent.ConsentAdminQueryService;
import com.koostack.operio.core.models.SubjectConsentView.ConsentLedgerEntryView;
import com.koostack.operio.core.models.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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
        void noExpiredGrantsReturnsZero() throws Exception {
            when(queryService.findExpiredGrants(any(Instant.class)))
                .thenReturn(List.of());

            int result = TenantContext.callWhere(TENANT, sweeper::sweep);

            assertEquals(0, result);
            verify(commandService, never()).expireConsentCommand(
                any(), anyLong(), any(), any(), any(), any(), any());
        }

        @Test
        void expiresFieldScopedGrant() throws Exception {
            var expired = new ConsentLedgerEntryView(
                1L, "alice", 100L, "marketing", "FIELD_LEVEL",
                "email", "User", null, "GRANTED", "system",
                null, null, Instant.now(), Instant.now(), Instant.now().minusSeconds(3600)
            );
            when(queryService.findExpiredGrants(any(Instant.class)))
                .thenReturn(List.of(expired));

            int result = TenantContext.callWhere(TENANT, sweeper::sweep);

            assertEquals(1, result);
            verify(commandService).expireConsentCommand(
                "alice", 100L, "marketing", "FIELD_LEVEL", "email", "User", null);
        }

        @Test
        void expiresCategoryScopedGrant() throws Exception {
            var expired = new ConsentLedgerEntryView(
                2L, "bob", 200L, "analytics", "CATEGORY_LEVEL",
                null, null, "PII", "GRANTED", "system",
                null, null, Instant.now(), Instant.now(), Instant.now().minusSeconds(3600)
            );
            when(queryService.findExpiredGrants(any(Instant.class)))
                .thenReturn(List.of(expired));

            int result = TenantContext.callWhere(TENANT, sweeper::sweep);

            assertEquals(1, result);
            verify(commandService).expireConsentCommand(
                "bob", 200L, "analytics", "CATEGORY_LEVEL", null, null, "PII");
        }

        @Test
        void expiresMultipleGrants() throws Exception {
            var expired1 = new ConsentLedgerEntryView(
                1L, "alice", 100L, "marketing", "FIELD_LEVEL",
                "email", "User", null, "GRANTED", "system",
                null, null, Instant.now(), Instant.now(), Instant.now().minusSeconds(3600)
            );
            var expired2 = new ConsentLedgerEntryView(
                2L, "bob", 200L, "analytics", "CATEGORY_LEVEL",
                null, null, "PII", "GRANTED", "system",
                null, null, Instant.now(), Instant.now(), Instant.now().minusSeconds(7200)
            );
            when(queryService.findExpiredGrants(any(Instant.class)))
                .thenReturn(List.of(expired1, expired2));

            int result = TenantContext.callWhere(TENANT, sweeper::sweep);

            assertEquals(2, result);
            verify(commandService).expireConsentCommand(
                "alice", 100L, "marketing", "FIELD_LEVEL", "email", "User", null);
            verify(commandService).expireConsentCommand(
                "bob", 200L, "analytics", "CATEGORY_LEVEL", null, null, "PII");
        }

        @Test
        void continuesOnIndividualExpirationFailure() throws Exception {
            var expired1 = new ConsentLedgerEntryView(
                1L, "alice", 100L, "marketing", "FIELD_LEVEL",
                "email", "User", null, "GRANTED", "system",
                null, null, Instant.now(), Instant.now(), Instant.now().minusSeconds(3600)
            );
            var expired2 = new ConsentLedgerEntryView(
                2L, "bob", 200L, "analytics", "FIELD_LEVEL",
                "phone", "User", null, "GRANTED", "system",
                null, null, Instant.now(), Instant.now(), Instant.now().minusSeconds(7200)
            );
            when(queryService.findExpiredGrants(any(Instant.class)))
                .thenReturn(List.of(expired1, expired2));

            // First expire throws, second succeeds
            doThrow(new RuntimeException("transient error"))
                .doNothing()
                .when(commandService).expireConsentCommand(
                    any(), anyLong(), any(), any(), any(), any(), any());

            int result = TenantContext.callWhere(TENANT, sweeper::sweep);

            assertEquals(1, result); // only second succeeded
            verify(commandService, times(2)).expireConsentCommand(
                any(), anyLong(), any(), any(), any(), any(), any());
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

            when(queryService.findExpiredGrants(any(Instant.class)))
                .thenReturn(List.of());

            int total = sweeper.sweepAll();

            assertEquals(0, total);
            // findExpiredGrants called once per tenant
            verify(queryService, times(2)).findExpiredGrants(any(Instant.class));
        }
    }
}
