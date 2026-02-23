package com.koostack.operio.plugin.jooq.common;

import static org.junit.jupiter.api.Assertions.*;

import com.koostack.operio.core.TenantContext;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
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
            assertEquals(
                "acme123",
                TenantAwareExecuteListener.sanitizeTenantId("acme123")
            );
        }

        @Test
        @DisplayName("valid tenant ID with underscores passes through")
        void validWithUnderscores() {
            assertEquals(
                "acme_corp_01",
                TenantAwareExecuteListener.sanitizeTenantId("acme_corp_01")
            );
        }

        @Test
        @DisplayName("single character tenant ID is valid")
        void singleCharacter() {
            assertEquals("a", TenantAwareExecuteListener.sanitizeTenantId("a"));
        }

        @Test
        @DisplayName("all uppercase tenant ID is valid")
        void allUppercase() {
            assertEquals(
                "ACME",
                TenantAwareExecuteListener.sanitizeTenantId("ACME")
            );
        }

        @Test
        @DisplayName("null tenant ID throws IllegalArgumentException")
        void nullTenantId() {
            assertThrows(IllegalArgumentException.class, () ->
                TenantAwareExecuteListener.sanitizeTenantId(null)
            );
        }

        @Test
        @DisplayName("blank tenant ID throws IllegalArgumentException")
        void blankTenantId() {
            assertThrows(IllegalArgumentException.class, () ->
                TenantAwareExecuteListener.sanitizeTenantId("   ")
            );
        }

        @Test
        @DisplayName("empty tenant ID throws IllegalArgumentException")
        void emptyTenantId() {
            assertThrows(IllegalArgumentException.class, () ->
                TenantAwareExecuteListener.sanitizeTenantId("")
            );
        }

        @Test
        @DisplayName("tenant ID with hyphens is rejected")
        void hyphensRejected() {
            IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> TenantAwareExecuteListener.sanitizeTenantId("acme-corp")
            );
            assertTrue(ex.getMessage().contains("illegal characters"));
        }

        @Test
        @DisplayName("tenant ID with dots is rejected")
        void dotsRejected() {
            assertThrows(IllegalArgumentException.class, () ->
                TenantAwareExecuteListener.sanitizeTenantId("acme.corp")
            );
        }

        @Test
        @DisplayName("tenant ID with spaces is rejected")
        void spacesRejected() {
            assertThrows(IllegalArgumentException.class, () ->
                TenantAwareExecuteListener.sanitizeTenantId("acme corp")
            );
        }

        @Test
        @DisplayName("SQL injection attempt is rejected")
        void sqlInjectionRejected() {
            assertThrows(IllegalArgumentException.class, () ->
                TenantAwareExecuteListener.sanitizeTenantId(
                    "acme; DROP TABLE tenant; --"
                )
            );
        }

        @Test
        @DisplayName("tenant ID with semicolons is rejected")
        void semicolonRejected() {
            assertThrows(IllegalArgumentException.class, () ->
                TenantAwareExecuteListener.sanitizeTenantId("acme;evil")
            );
        }

        @Test
        @DisplayName("tenant ID with quotes is rejected")
        void quotesRejected() {
            assertThrows(IllegalArgumentException.class, () ->
                TenantAwareExecuteListener.sanitizeTenantId("acme'corp")
            );
        }

        @Test
        @DisplayName("tenant ID with slashes is rejected")
        void slashesRejected() {
            assertThrows(IllegalArgumentException.class, () ->
                TenantAwareExecuteListener.sanitizeTenantId("acme/corp")
            );
        }
    }

    // =======================================================================
    // Schema name generation
    // =======================================================================

    @Nested
    @DisplayName("Schema Name Generation")
    class SchemaName {

        @Test
        @DisplayName("schemaNameFor prepends t_ prefix")
        void prependsPrefix() {
            assertEquals(
                "t_acme",
                TenantAwareExecuteListener.schemaNameFor("acme")
            );
        }

        @Test
        @DisplayName("schemaNameFor preserves case")
        void preservesCase() {
            assertEquals(
                "t_AcmeCorp",
                TenantAwareExecuteListener.schemaNameFor("AcmeCorp")
            );
        }

        @Test
        @DisplayName("schemaNameFor with underscores")
        void withUnderscores() {
            assertEquals(
                "t_acme_corp_01",
                TenantAwareExecuteListener.schemaNameFor("acme_corp_01")
            );
        }

        @Test
        @DisplayName("schemaNameFor rejects invalid tenant ID")
        void rejectsInvalid() {
            assertThrows(IllegalArgumentException.class, () ->
                TenantAwareExecuteListener.schemaNameFor("acme-corp")
            );
        }

        @Test
        @DisplayName("schemaNameFor rejects null")
        void rejectsNull() {
            assertThrows(IllegalArgumentException.class, () ->
                TenantAwareExecuteListener.schemaNameFor(null)
            );
        }

        @Test
        @DisplayName("SCHEMA_PREFIX constant is t_")
        void schemaPrefixConstant() {
            assertEquals("t_", TenantAwareExecuteListener.SCHEMA_PREFIX);
        }
    }

    // =======================================================================
    // Listener integration (with H2)
    // =======================================================================

    @Nested
    @DisplayName("Listener Integration")
    class ListenerIntegration {

        /**
         * Creates a tenant-aware DSLContext backed by an in-memory H2
         * database. The TenantAwareExecuteListener is attached to
         * intercept all statement executions.
         */
        private DSLContext createTenantAwareH2Context() {
            DefaultConfiguration config = new DefaultConfiguration();
            config.set(SQLDialect.H2);
            config.set(
                new org.jooq.impl.DefaultConnectionProvider(
                    createH2Connection()
                )
            );
            config.set(
                new DefaultExecuteListenerProvider(
                    new TenantAwareExecuteListener()
                )
            );
            return DSL.using(config);
        }

        private java.sql.Connection createH2Connection() {
            try {
                return java.sql.DriverManager.getConnection(
                    "jdbc:h2:mem:tenant_test_" +
                        System.nanoTime() +
                        ";DB_CLOSE_DELAY=-1",
                    "sa",
                    ""
                );
            } catch (java.sql.SQLException e) {
                throw new RuntimeException(
                    "Failed to create H2 test connection",
                    e
                );
            }
        }

        @Test
        @DisplayName(
            "query succeeds without tenant context (listener is no-op)"
        )
        void noTenantContextIsNoOp() {
            DSLContext ctx = createTenantAwareH2Context();

            // SELECT 1 should work fine — no tenant context means the
            // listener does nothing
            assertDoesNotThrow(() -> ctx.selectOne().fetch());
        }

        @Test
        @DisplayName(
            "listener fires SET search_path when tenant context is active " +
                "(fails on H2, proving the listener executed)"
        )
        void listenerFiresWithTenantContext() throws Exception {
            DSLContext ctx = createTenantAwareH2Context();

            // With tenant context active, the listener will try to execute
            // "SET search_path TO t_acme, public" which H2 does not
            // support. The resulting exception proves the listener IS
            // being invoked on the connection.
            TenantContext.callWhere("acme", () -> {
                DataAccessException ex = assertThrows(
                    DataAccessException.class,
                    () -> ctx.selectOne().fetch()
                );
                // The outer exception should mention the tenant schema
                assertTrue(
                    ex.getMessage().contains("t_acme") ||
                        ex.getCause().getMessage().contains("search_path"),
                    "Exception should reference the tenant schema or search_path, got: " +
                        ex.getMessage()
                );
                return null;
            });
        }

        @Test
        @DisplayName(
            "same context works after tenant scope exits (no lingering state)"
        )
        void noLingeringStateAfterTenantScope() throws Exception {
            DSLContext ctx = createTenantAwareH2Context();

            // First: verify works without tenant
            assertDoesNotThrow(() -> ctx.selectOne().fetch());

            // Second: enter tenant scope — will fail on H2
            TenantContext.callWhere("acme", () -> {
                assertThrows(DataAccessException.class, () ->
                    ctx.selectOne().fetch()
                );
                return null;
            });

            // Third: after exiting tenant scope, should work again
            assertDoesNotThrow(() -> ctx.selectOne().fetch());
        }

        @Test
        @DisplayName(
            "invalid tenant ID in context throws IllegalArgumentException"
        )
        void invalidTenantIdInContext() throws Exception {
            DSLContext ctx = createTenantAwareH2Context();

            TenantContext.callWhere("acme-corp", () -> {
                assertThrows(IllegalArgumentException.class, () ->
                    ctx.selectOne().fetch()
                );
                return null;
            });
        }
    }

    // =======================================================================
    // Factory integration
    // =======================================================================

    @Nested
    @DisplayName("JooqContextFactory Integration")
    class FactoryIntegration {

        @Test
        @DisplayName(
            "createTenantAwareContext produces a DSLContext with the listener attached"
        )
        void tenantAwareContextHasListener() throws Exception {
            JooqPluginConfig config = new JooqPluginConfig() {
                @Override
                public String connectionString() {
                    return (
                        "jdbc:h2:mem:factory_test_" +
                        System.nanoTime() +
                        ";DB_CLOSE_DELAY=-1"
                    );
                }

                @Override
                public String dialect() {
                    return "H2";
                }
            };

            DSLContext ctx = JooqContextFactory.createTenantAwareContext(
                config,
                "test-pool"
            );

            // Without tenant context — should work
            assertDoesNotThrow(() -> ctx.selectOne().fetch());

            // With tenant context — should fail on H2 (proving listener
            // is attached)
            TenantContext.callWhere("acme", () -> {
                assertThrows(DataAccessException.class, () ->
                    ctx.selectOne().fetch()
                );
                return null;
            });
        }

        @Test
        @DisplayName(
            "createContext (non-tenant-aware) does not attach the listener"
        )
        void plainContextHasNoListener() throws Exception {
            JooqPluginConfig config = new JooqPluginConfig() {
                @Override
                public String connectionString() {
                    return (
                        "jdbc:h2:mem:plain_test_" +
                        System.nanoTime() +
                        ";DB_CLOSE_DELAY=-1"
                    );
                }

                @Override
                public String dialect() {
                    return "H2";
                }
            };

            DSLContext ctx = JooqContextFactory.createContext(
                config,
                "test-pool"
            );

            // Without tenant context — should work
            assertDoesNotThrow(() -> ctx.selectOne().fetch());

            // With tenant context — should ALSO work because the plain
            // context does NOT have the tenant-aware listener
            TenantContext.callWhere("acme", () -> {
                assertDoesNotThrow(() -> ctx.selectOne().fetch());
                return null;
            });
        }
    }

    // =======================================================================
    // H2 Schema-Based Tenant Routing (Task 7.4)
    // =======================================================================

    @Nested
    @DisplayName("H2 Schema-Based Tenant Routing")
    class H2SchemaBasedRouting {

        /**
         * Creates a shared H2 connection with DB_CLOSE_DELAY=-1 so the
         * database persists across statements.
         */
        private Connection createSharedH2Connection() {
            try {
                return java.sql.DriverManager.getConnection(
                    "jdbc:h2:mem:h2_schema_test_" +
                        System.nanoTime() +
                        ";DB_CLOSE_DELAY=-1",
                    "sa",
                    ""
                );
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }

        @Test
        @DisplayName(
            "H2 SET SCHEMA routes queries to the correct tenant schema"
        )
        void h2SetSchemaRoutesTenantQueries() throws Exception {
            Connection conn = createSharedH2Connection();
            try (Statement stmt = conn.createStatement()) {
                // Create two tenant schemas with tables
                stmt.execute("CREATE SCHEMA t_acme");
                stmt.execute("CREATE SCHEMA t_globex");
                stmt.execute(
                    "CREATE TABLE t_acme.demo (id INT PRIMARY KEY, val VARCHAR(50))"
                );
                stmt.execute(
                    "CREATE TABLE t_globex.demo (id INT PRIMARY KEY, val VARCHAR(50))"
                );
                stmt.execute("INSERT INTO t_acme.demo VALUES (1, 'acme-data')");
                stmt.execute(
                    "INSERT INTO t_globex.demo VALUES (1, 'globex-data')"
                );

                // Route to acme
                stmt.execute("SET SCHEMA t_acme");
                try (
                    ResultSet rs = stmt.executeQuery(
                        "SELECT val FROM demo WHERE id = 1"
                    )
                ) {
                    assertTrue(rs.next());
                    assertEquals("acme-data", rs.getString("val"));
                }

                // Route to globex
                stmt.execute("SET SCHEMA t_globex");
                try (
                    ResultSet rs = stmt.executeQuery(
                        "SELECT val FROM demo WHERE id = 1"
                    )
                ) {
                    assertTrue(rs.next());
                    assertEquals("globex-data", rs.getString("val"));
                }
            } finally {
                conn.close();
            }
        }

        @Test
        @DisplayName(
            "H2 SET SCHEMA to non-existent schema fails (validates routing)"
        )
        void h2SetSchemaToNonExistentFails() throws Exception {
            Connection conn = createSharedH2Connection();
            try (Statement stmt = conn.createStatement()) {
                assertThrows(SQLException.class, () ->
                    stmt.execute("SET SCHEMA t_no_such_tenant")
                );
            } finally {
                conn.close();
            }
        }

        @Test
        @DisplayName(
            "H2 schemas are fully isolated — INSERT in one does not affect other"
        )
        void h2SchemaInsertIsolation() throws Exception {
            Connection conn = createSharedH2Connection();
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE SCHEMA t_alpha");
                stmt.execute("CREATE SCHEMA t_beta");
                stmt.execute(
                    "CREATE TABLE t_alpha.items (id INT PRIMARY KEY, name VARCHAR(50))"
                );
                stmt.execute(
                    "CREATE TABLE t_beta.items (id INT PRIMARY KEY, name VARCHAR(50))"
                );

                // Insert 3 rows in alpha, 1 in beta
                stmt.execute("SET SCHEMA t_alpha");
                stmt.execute("INSERT INTO items VALUES (1, 'a1')");
                stmt.execute("INSERT INTO items VALUES (2, 'a2')");
                stmt.execute("INSERT INTO items VALUES (3, 'a3')");

                stmt.execute("SET SCHEMA t_beta");
                stmt.execute("INSERT INTO items VALUES (1, 'b1')");

                // Verify counts
                stmt.execute("SET SCHEMA t_alpha");
                try (
                    ResultSet rs = stmt.executeQuery(
                        "SELECT COUNT(*) AS cnt FROM items"
                    )
                ) {
                    assertTrue(rs.next());
                    assertEquals(3, rs.getInt("cnt"));
                }

                stmt.execute("SET SCHEMA t_beta");
                try (
                    ResultSet rs = stmt.executeQuery(
                        "SELECT COUNT(*) AS cnt FROM items"
                    )
                ) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt("cnt"));
                }
            } finally {
                conn.close();
            }
        }

        @Test
        @DisplayName(
            "H2 DROP SCHEMA CASCADE removes tenant data without affecting other schemas"
        )
        void h2DropSchemaIsolation() throws Exception {
            Connection conn = createSharedH2Connection();
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE SCHEMA t_ephemeral");
                stmt.execute("CREATE SCHEMA t_permanent");
                stmt.execute(
                    "CREATE TABLE t_ephemeral.data (id INT PRIMARY KEY)"
                );
                stmt.execute(
                    "CREATE TABLE t_permanent.data (id INT PRIMARY KEY)"
                );
                stmt.execute("INSERT INTO t_ephemeral.data VALUES (1)");
                stmt.execute("INSERT INTO t_permanent.data VALUES (1)");

                // Drop ephemeral
                stmt.execute("DROP SCHEMA t_ephemeral CASCADE");

                // Verify ephemeral is gone
                Set<String> schemas = new HashSet<>();
                try (
                    ResultSet rs = stmt.executeQuery(
                        "SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA"
                    )
                ) {
                    while (rs.next()) {
                        schemas.add(rs.getString("SCHEMA_NAME"));
                    }
                }
                assertFalse(
                    schemas.contains("T_EPHEMERAL"),
                    "Dropped schema should not exist"
                );
                assertTrue(
                    schemas.contains("T_PERMANENT"),
                    "Permanent schema should still exist"
                );

                // Verify permanent data is intact
                stmt.execute("SET SCHEMA t_permanent");
                try (
                    ResultSet rs = stmt.executeQuery(
                        "SELECT COUNT(*) AS cnt FROM data"
                    )
                ) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt("cnt"));
                }
            } finally {
                conn.close();
            }
        }
    }

    // =======================================================================
    // Platform DSLContext Never Tenant-Routed (Task 7.4)
    // =======================================================================

    @Nested
    @DisplayName("Platform DSLContext is Never Tenant-Routed")
    class PlatformNeverTenantRouted {

        @Test
        @DisplayName(
            "platform (plain) context succeeds under multiple tenant contexts"
        )
        void platformSucceedsUnderMultipleTenants() throws Exception {
            JooqPluginConfig config = new JooqPluginConfig() {
                @Override
                public String connectionString() {
                    return (
                        "jdbc:h2:mem:platform_multi_" +
                        System.nanoTime() +
                        ";DB_CLOSE_DELAY=-1"
                    );
                }

                @Override
                public String dialect() {
                    return "H2";
                }
            };

            DSLContext platformCtx = JooqContextFactory.createContext(
                config,
                "platform-pool"
            );

            // Verify SELECT works under various tenant contexts
            for (String tenant : new String[] {
                "acme",
                "globex",
                "big_corp",
                "tiny_co",
            }) {
                TenantContext.callWhere(tenant, () -> {
                    assertDoesNotThrow(
                        () -> platformCtx.selectOne().fetch(),
                        "Platform context should work under tenant '" +
                            tenant +
                            "'"
                    );
                    return null;
                });
            }

            // Also works without tenant
            assertDoesNotThrow(() -> platformCtx.selectOne().fetch());
        }

        @Test
        @DisplayName(
            "tenant-aware context fails while platform context succeeds " +
                "(same tenant, proves listener attachment difference)"
        )
        void tenantAwareFailsWhilePlatformSucceeds() throws Exception {
            String dbUrl =
                "jdbc:h2:mem:dual_ctx_" +
                System.nanoTime() +
                ";DB_CLOSE_DELAY=-1";

            JooqPluginConfig config = new JooqPluginConfig() {
                @Override
                public String connectionString() {
                    return dbUrl;
                }

                @Override
                public String dialect() {
                    return "H2";
                }
            };

            DSLContext platformCtx = JooqContextFactory.createContext(
                config,
                "platform-dual"
            );
            DSLContext tenantCtx = JooqContextFactory.createTenantAwareContext(
                config,
                "tenant-dual"
            );

            TenantContext.callWhere("acme", () -> {
                // Platform context succeeds (no listener)
                assertDoesNotThrow(
                    () -> platformCtx.selectOne().fetch(),
                    "Platform context should succeed under tenant context"
                );

                // Tenant-aware context fails (listener fires SET search_path on H2)
                assertThrows(
                    DataAccessException.class,
                    () -> tenantCtx.selectOne().fetch(),
                    "Tenant-aware context should fail on H2 (proving listener is attached)"
                );
                return null;
            });
        }

        @Test
        @DisplayName(
            "platform context can write to shared tables while tenant context is active"
        )
        void platformWritesToSharedTablesUnderTenantContext() throws Exception {
            Connection conn = java.sql.DriverManager.getConnection(
                "jdbc:h2:mem:platform_write_" +
                    System.nanoTime() +
                    ";DB_CLOSE_DELAY=-1",
                "sa",
                ""
            );

            // Create a "platform" table in default schema
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(
                    "CREATE TABLE platform_tenant (" +
                        "tenant_id VARCHAR(100) PRIMARY KEY, " +
                        "display_name VARCHAR(200))"
                );
            }

            DefaultConfiguration config = new DefaultConfiguration();
            config.set(SQLDialect.H2);
            config.set(new org.jooq.impl.DefaultConnectionProvider(conn));
            // No TenantAwareExecuteListener — this is a platform context
            DSLContext platformCtx = DSL.using(config);

            // Insert under a tenant context — should succeed because
            // platform context has no tenant-aware listener
            TenantContext.callWhere("acme", () -> {
                platformCtx
                    .insertInto(DSL.table("platform_tenant"))
                    .columns(
                        DSL.field("tenant_id", String.class),
                        DSL.field("display_name", String.class)
                    )
                    .values("acme", "Acme Corp")
                    .execute();
                return null;
            });

            // Verify the row exists (no tenant context)
            var result = platformCtx
                .select(DSL.field("display_name", String.class))
                .from(DSL.table("platform_tenant"))
                .where(DSL.field("tenant_id", String.class).eq("acme"))
                .fetchOne();
            assertNotNull(result);
            assertEquals("Acme Corp", result.value1());

            conn.close();
        }
    }

    // =======================================================================
    // Concurrent Tenant Context Safety (Task 7.4)
    // =======================================================================

    @Nested
    @DisplayName("Concurrent Tenant Context Safety")
    class ConcurrentTenantContextSafety {

        @Test
        @DisplayName(
            "schemaNameFor is safe under concurrent TenantContext switches"
        )
        void schemaNameForConcurrentSafety() throws Exception {
            // schemaNameFor is a pure function — it doesn't read TenantContext.
            // This test verifies it's deterministic under concurrent calls.
            int threadCount = 10;
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicReference<String> error = new AtomicReference<>();

            Thread[] threads = new Thread[threadCount];
            for (int i = 0; i < threadCount; i++) {
                final String tenantId = "tenant_" + i;
                threads[i] = Thread.ofVirtual().start(() -> {
                    try {
                        for (int j = 0; j < 100; j++) {
                            String result =
                                TenantAwareExecuteListener.schemaNameFor(
                                    tenantId
                                );
                            if (!("t_" + tenantId).equals(result)) {
                                error.set(
                                    "Expected t_" +
                                        tenantId +
                                        " but got " +
                                        result
                                );
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
                "Concurrent schemaNameFor should be deterministic: " +
                    error.get()
            );
        }

        @Test
        @DisplayName("sanitizeTenantId is thread-safe under concurrent calls")
        void sanitizeConcurrentSafety() throws Exception {
            int threadCount = 8;
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicReference<String> error = new AtomicReference<>();

            Thread[] threads = new Thread[threadCount];
            for (int i = 0; i < threadCount; i++) {
                final String id = "safe_id_" + i;
                threads[i] = Thread.ofVirtual().start(() -> {
                    try {
                        for (int j = 0; j < 200; j++) {
                            String result =
                                TenantAwareExecuteListener.sanitizeTenantId(id);
                            if (!id.equals(result)) {
                                error.set(
                                    "Expected " + id + " but got " + result
                                );
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
                "Concurrent sanitizeTenantId should be thread-safe: " +
                    error.get()
            );
        }

        @Test
        @DisplayName(
            "TenantContext.callWhere on different virtual threads produces " +
                "isolated schema names"
        )
        void tenantContextVirtualThreadIsolation() throws Exception {
            int threadCount = 5;
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicReference<String> error = new AtomicReference<>();

            for (int i = 0; i < threadCount; i++) {
                final String tenantId = "vt_tenant_" + i;
                final String expected = "t_" + tenantId;

                Thread.ofVirtual().start(() -> {
                    try {
                        TenantContext.callWhere(tenantId, () -> {
                            // Inside TenantContext, schemaNameFor should
                            // produce the correct result for this thread's
                            // tenant
                            String current = TenantContext.get();
                            assertEquals(tenantId, current);
                            String schema =
                                TenantAwareExecuteListener.schemaNameFor(
                                    current
                                );
                            assertEquals(expected, schema);
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
                "Virtual thread tenant isolation failed: " + error.get()
            );
        }
    }
}
