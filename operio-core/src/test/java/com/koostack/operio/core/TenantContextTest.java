package com.koostack.operio.core;

import static org.junit.jupiter.api.Assertions.*;

import com.koostack.operio.core.exception.OperioConfigurationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link TenantContext} — the ScopedValue-based tenant routing
 * primitive used across the entire Operio stack.
 *
 * <p>ScopedValue semantics to verify:
 * <ul>
 *   <li>Unbound outside any scope (null / throws / isPresent=false)</li>
 *   <li>Bound inside {@code runWhere} / {@code callWhere}</li>
 *   <li>Auto-cleared when scope exits</li>
 *   <li>Nested scopes: inner value wins, outer restored on exit</li>
 *   <li>Virtual thread propagation: ScopedValue is visible in a virtual
 *       thread spawned inside the scope</li>
 * </ul>
 */
@DisplayName("TenantContext (ScopedValue)")
class TenantContextTest {

    // -----------------------------------------------------------------------
    // Outside scope — unbound
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Outside any scope")
    class OutsideScope {

        @Test
        @DisplayName("get() returns null when no scope is active")
        void getReturnsNullOutsideScope() {
            assertNull(TenantContext.get());
        }

        @Test
        @DisplayName(
            "require() throws OperioConfigurationException when no scope is active"
        )
        void requireThrowsOutsideScope() {
            OperioConfigurationException ex = assertThrows(
                OperioConfigurationException.class,
                TenantContext::require
            );
            assertTrue(
                ex.getMessage().toLowerCase().contains("no tenant context"),
                "Exception message should mention missing tenant context, was: " +
                    ex.getMessage()
            );
        }

        @Test
        @DisplayName("isPresent() returns false when no scope is active")
        void isPresentFalseOutsideScope() {
            assertFalse(TenantContext.isPresent());
        }
    }

    // -----------------------------------------------------------------------
    // runWhere — bind, auto-clear
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("runWhere()")
    class RunWhere {

        @Test
        @DisplayName("binds tenant ID inside the scope")
        void bindsTenantInsideScope() {
            TenantContext.runWhere("acme", () -> {
                assertEquals("acme", TenantContext.get());
                assertEquals("acme", TenantContext.require());
                assertTrue(TenantContext.isPresent());
            });
        }

        @Test
        @DisplayName("auto-clears tenant ID after scope exits")
        void autoClearsAfterScope() {
            TenantContext.runWhere("acme", () -> {
                // Tenant is bound here
                assertEquals("acme", TenantContext.get());
            });
            // After scope exits, tenant should be unbound
            assertNull(TenantContext.get());
            assertFalse(TenantContext.isPresent());
        }

        @Test
        @DisplayName("auto-clears tenant ID even when scope throws")
        void autoClearsOnException() {
            RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                TenantContext.runWhere("acme", () -> {
                    assertEquals("acme", TenantContext.get());
                    throw new RuntimeException("boom");
                })
            );
            assertEquals("boom", thrown.getMessage());
            assertNull(TenantContext.get());
            assertFalse(TenantContext.isPresent());
        }
    }

    // -----------------------------------------------------------------------
    // callWhere — bind, return value, auto-clear
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("callWhere()")
    class CallWhere {

        @Test
        @DisplayName(
            "returns value from callable and binds tenant inside scope"
        )
        void returnsValueAndBindsTenant() throws Throwable {
            String result = TenantContext.callWhere("globex", () -> {
                assertEquals("globex", TenantContext.get());
                return "hello from " + TenantContext.require();
            });

            assertEquals("hello from globex", result);
        }

        @Test
        @DisplayName("auto-clears tenant ID after callWhere exits")
        void autoClearsAfterCall() throws Throwable {
            TenantContext.callWhere("globex", () -> "ok");

            assertNull(TenantContext.get());
            assertFalse(TenantContext.isPresent());
        }

        @Test
        @DisplayName("auto-clears tenant ID even when callable throws")
        void autoClearsOnException() {
            assertThrows(IllegalStateException.class, () ->
                TenantContext.callWhere("globex", () -> {
                    throw new IllegalStateException("fail");
                })
            );
            assertNull(TenantContext.get());
            assertFalse(TenantContext.isPresent());
        }
    }

    // -----------------------------------------------------------------------
    // Nested scopes
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Nested scopes")
    class NestedScopes {

        @Test
        @DisplayName(
            "inner scope overrides outer; outer restored on inner exit"
        )
        void innerOverridesOuter() {
            TenantContext.runWhere("outer-tenant", () -> {
                assertEquals("outer-tenant", TenantContext.get());

                TenantContext.runWhere("inner-tenant", () -> {
                    // Inner scope wins
                    assertEquals("inner-tenant", TenantContext.get());
                    assertEquals("inner-tenant", TenantContext.require());
                });

                // After inner scope exits, outer is restored
                assertEquals("outer-tenant", TenantContext.get());
                assertEquals("outer-tenant", TenantContext.require());
            });

            // After all scopes exit, unbound
            assertNull(TenantContext.get());
        }

        @Test
        @DisplayName("triple-nested scopes restore correctly")
        void tripleNested() {
            TenantContext.runWhere("a", () -> {
                assertEquals("a", TenantContext.get());

                TenantContext.runWhere("b", () -> {
                    assertEquals("b", TenantContext.get());

                    TenantContext.runWhere("c", () -> {
                        assertEquals("c", TenantContext.get());
                    });

                    assertEquals("b", TenantContext.get());
                });

                assertEquals("a", TenantContext.get());
            });

            assertNull(TenantContext.get());
        }
    }

    // -----------------------------------------------------------------------
    // Virtual thread propagation
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Virtual thread propagation")
    class VirtualThreadPropagation {

        @Test
        @DisplayName(
            "ScopedValue is NOT inherited by Thread.ofVirtual().start() — by design"
        )
        void notInheritedByRawVirtualThread() {
            TenantContext.runWhere("vt-tenant", () -> {
                AtomicReference<String> captured = new AtomicReference<>();

                // ScopedValue is NOT automatically inherited by virtual threads
                // spawned via Thread.ofVirtual().start().  This is by design —
                // ScopedValue requires StructuredTaskScope for structured
                // propagation.  A raw virtual thread starts with no bindings.
                Thread vt = Thread.ofVirtual().start(() ->
                    captured.set(TenantContext.get())
                );

                try {
                    vt.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    fail("Interrupted waiting for virtual thread");
                }

                assertNull(
                    captured.get(),
                    "ScopedValue should NOT be inherited by a raw virtual thread " +
                        "(Thread.ofVirtual().start()) — use StructuredTaskScope or " +
                        "explicit runWhere() inside the virtual thread instead"
                );
            });
        }

        @Test
        @DisplayName("virtual thread can bind its own tenant via runWhere()")
        void virtualThreadCanBindOwnTenant() throws Exception {
            CompletableFuture<String> future = new CompletableFuture<>();

            Thread vt = Thread.ofVirtual().start(() ->
                TenantContext.runWhere("vt-own-tenant", () ->
                    future.complete(TenantContext.get())
                )
            );
            vt.join();

            assertEquals(
                "vt-own-tenant",
                future.get(),
                "Virtual thread should be able to bind its own tenant via runWhere()"
            );
        }

        @Test
        @DisplayName(
            "virtual thread does not see scope after parent scope exits"
        )
        void noLeakAfterScopeExit() throws Exception {
            CompletableFuture<String> future = new CompletableFuture<>();

            TenantContext.runWhere("ephemeral", () -> {
                assertEquals("ephemeral", TenantContext.get());
            });

            // After scope exits, a new virtual thread should NOT see the tenant
            Thread vt = Thread.ofVirtual().start(() ->
                future.complete(TenantContext.get())
            );
            vt.join();

            assertNull(
                future.get(),
                "Virtual thread started after scope exits should not see the tenant"
            );
        }

        @Test
        @DisplayName(
            "two concurrent virtual threads with different tenants are isolated"
        )
        void concurrentVirtualThreadsIsolated() throws Exception {
            CompletableFuture<String> futureA = new CompletableFuture<>();
            CompletableFuture<String> futureB = new CompletableFuture<>();

            Thread vtA = Thread.ofVirtual().start(() ->
                TenantContext.runWhere("tenant-a", () -> {
                    // Simulate some work
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ignored) {}
                    futureA.complete(TenantContext.get());
                })
            );

            Thread vtB = Thread.ofVirtual().start(() ->
                TenantContext.runWhere("tenant-b", () -> {
                    // Simulate some work
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ignored) {}
                    futureB.complete(TenantContext.get());
                })
            );

            vtA.join();
            vtB.join();

            assertEquals(
                "tenant-a",
                futureA.get(),
                "Virtual thread A should see tenant-a"
            );
            assertEquals(
                "tenant-b",
                futureB.get(),
                "Virtual thread B should see tenant-b"
            );
        }
    }
}
