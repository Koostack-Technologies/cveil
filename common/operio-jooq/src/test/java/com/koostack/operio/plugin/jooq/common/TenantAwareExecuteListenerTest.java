package com.koostack.operio.plugin.jooq.common;

import static org.junit.jupiter.api.Assertions.*;

import com.koostack.operio.core.models.TenantContext;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class TenantAwareExecuteListenerTest {

    // =======================================================================
    // Sanitization
    // =======================================================================

    @Nested
    @DisplayName("Tenant ID Sanitization")
    class Sanitization {

        @Test
        @DisplayName("valid alphanumeric tenant ID passes through unchanged")
        void validAlphanumeric() {
            assertEquals("acme123", TenantAwareExecuteListener.sanitizeTenantId("acme123"));
        }

        @Test
        @DisplayName("valid tenant ID with underscores passes through")
        void validWithUnderscores() {
            assertEquals(
                    "acme_corp_01", TenantAwareExecuteListener.sanitizeTenantId("acme_corp_01"));
        }

        @Test
        @DisplayName("valid tenant ID with hyphens passes through")
        void validWithHyphens() {
            assertEquals("acme-corp", TenantAwareExecuteListener.sanitizeTenantId("acme-corp"));
        }

        @Test
        @DisplayName("single character tenant ID is valid")
        void singleCharacter() {
            assertEquals("a", TenantAwareExecuteListener.sanitizeTenantId("a"));
        }

        @Test
        @DisplayName("all uppercase tenant ID is valid")
        void allUppercase() {
            assertEquals("ACME", TenantAwareExecuteListener.sanitizeTenantId("ACME"));
        }

        @Test
        @DisplayName("null tenant ID throws IllegalArgumentException")
        void nullTenantId() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> TenantAwareExecuteListener.sanitizeTenantId(null));
        }

        @Test
        @DisplayName("blank tenant ID throws IllegalArgumentException")
        void blankTenantId() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> TenantAwareExecuteListener.sanitizeTenantId("   "));
        }

        @Test
        @DisplayName("empty tenant ID throws IllegalArgumentException")
        void emptyTenantId() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> TenantAwareExecuteListener.sanitizeTenantId(""));
        }

        @Test
        @DisplayName("tenant ID with dots is rejected")
        void dotsRejected() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> TenantAwareExecuteListener.sanitizeTenantId("acme.corp"));
        }

        @Test
        @DisplayName("tenant ID with spaces is rejected")
        void spacesRejected() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> TenantAwareExecuteListener.sanitizeTenantId("acme corp"));
        }

        @Test
        @DisplayName("SQL injection attempt is rejected")
        void sqlInjectionRejected() {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            TenantAwareExecuteListener.sanitizeTenantId(
                                    "acme; DROP TABLE tenant; --"));
        }

        @Test
        @DisplayName("tenant ID with semicolons is rejected")
        void semicolonRejected() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> TenantAwareExecuteListener.sanitizeTenantId("acme;evil"));
        }

        @Test
        @DisplayName("tenant ID with quotes is rejected")
        void quotesRejected() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> TenantAwareExecuteListener.sanitizeTenantId("acme'corp"));
        }

        @Test
        @DisplayName("tenant ID with slashes is rejected")
        void slashesRejected() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> TenantAwareExecuteListener.sanitizeTenantId("acme/corp"));
        }
    }

    // =======================================================================
    // Listener integration (with H2)
    // =======================================================================

    @Nested
    @DisplayName("Listener Integration")
    class ListenerIntegration {

        private DSLContext createTenantAwareH2Context() {
            DefaultConfiguration config = new DefaultConfiguration();
            config.set(SQLDialect.H2);
            config.set(new org.jooq.impl.DefaultConnectionProvider(createH2Connection()));
            config.set(new DefaultExecuteListenerProvider(new TenantAwareExecuteListener()));
            return DSL.using(config);
        }

        private Connection createH2Connection() {
            try {
                return java.sql.DriverManager.getConnection(
                        "jdbc:h2:mem:tenant_test_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
            } catch (SQLException e) {
                throw new RuntimeException("Failed to create H2 test connection", e);
            }
        }

        @Test
        @DisplayName("query succeeds without tenant context (listener is no-op)")
        void noTenantContextIsNoOp() {
            DSLContext ctx = createTenantAwareH2Context();
            assertDoesNotThrow(() -> ctx.selectOne().fetch());
        }

        @Test
        @DisplayName(
                "listener fires SET app.tenant_id when tenant context is active "
                        + "(fails on H2, proving the listener executed)")
        void listenerFiresWithTenantContext() throws Exception {
            DSLContext ctx = createTenantAwareH2Context();

            // With tenant context active, the listener will try to execute
            // "SET app.tenant_id = 'acme'" which H2 does not support.
            // The resulting exception proves the listener IS being invoked.
            TenantContext.callWhere(
                    "acme",
                    () -> {
                        DataAccessException ex =
                                assertThrows(
                                        DataAccessException.class, () -> ctx.selectOne().fetch());
                        assertTrue(
                                ex.getMessage().contains("app.tenant_id")
                                        || (ex.getCause() != null
                                                && ex.getCause()
                                                        .getMessage()
                                                        .contains("app.tenant_id")),
                                "Exception should reference app.tenant_id, got: "
                                        + ex.getMessage());
                        return null;
                    });
        }

        @Test
        @DisplayName("same context works after tenant scope exits (no lingering state)")
        void noLingeringStateAfterTenantScope() throws Exception {
            DSLContext ctx = createTenantAwareH2Context();

            assertDoesNotThrow(() -> ctx.selectOne().fetch());

            TenantContext.callWhere(
                    "acme",
                    () -> {
                        assertThrows(DataAccessException.class, () -> ctx.selectOne().fetch());
                        return null;
                    });

            assertDoesNotThrow(() -> ctx.selectOne().fetch());
        }

        @Test
        @DisplayName("invalid tenant ID in context throws IllegalArgumentException")
        void invalidTenantIdInContext() throws Exception {
            DSLContext ctx = createTenantAwareH2Context();

            TenantContext.callWhere(
                    "acme.corp",
                    () -> {
                        assertThrows(IllegalArgumentException.class, () -> ctx.selectOne().fetch());
                        return null;
                    });
        }
    }

    // =======================================================================
    // Concurrent Tenant Context Safety
    // =======================================================================

    @Nested
    @DisplayName("Concurrent Tenant Context Safety")
    class ConcurrentTenantContextSafety {

        @Test
        @DisplayName("sanitizeTenantId is thread-safe under concurrent calls")
        void sanitizeConcurrentSafety() throws Exception {
            int threadCount = 8;
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicReference<String> error = new AtomicReference<>();

            Thread[] threads = new Thread[threadCount];
            for (int i = 0; i < threadCount; i++) {
                final String id = "safe_id_" + i;
                threads[i] =
                        Thread.ofVirtual()
                                .start(
                                        () -> {
                                            try {
                                                for (int j = 0; j < 200; j++) {
                                                    String result =
                                                            TenantAwareExecuteListener
                                                                    .sanitizeTenantId(id);
                                                    if (!id.equals(result)) {
                                                        error.set(
                                                                "Expected "
                                                                        + id
                                                                        + " but got "
                                                                        + result);
                                                    }
                                                }
                                            } catch (Exception e) {
                                                error.set(e.getMessage());
                                            } finally {
                                                latch.countDown();
                                            }
                                        });
            }

            latch.await();
            assertNull(
                    error.get(),
                    "Concurrent sanitizeTenantId should be thread-safe: " + error.get());
        }

        @Test
        @DisplayName("TenantContext isolation across virtual threads")
        void tenantContextVirtualThreadIsolation() throws Exception {
            int threadCount = 5;
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicReference<String> error = new AtomicReference<>();

            for (int i = 0; i < threadCount; i++) {
                final String tenantId = "vt_tenant_" + i;

                Thread.ofVirtual()
                        .start(
                                () -> {
                                    try {
                                        TenantContext.callWhere(
                                                tenantId,
                                                () -> {
                                                    String current = TenantContext.get();
                                                    assertEquals(tenantId, current);
                                                    String sanitized =
                                                            TenantAwareExecuteListener
                                                                    .sanitizeTenantId(current);
                                                    assertEquals(tenantId, sanitized);
                                                    return null;
                                                });
                                    } catch (Exception e) {
                                        error.set(tenantId + ": " + e.getMessage());
                                    } finally {
                                        latch.countDown();
                                    }
                                });
            }

            latch.await();
            assertNull(
                    error.get(),
                    "TenantContext should be isolated across virtual threads: " + error.get());
        }
    }
}
