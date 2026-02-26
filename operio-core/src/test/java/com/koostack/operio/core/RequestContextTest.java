package com.koostack.operio.core;

import static org.junit.jupiter.api.Assertions.*;

import com.koostack.operio.core.models.RequestContext;
import com.koostack.operio.core.models.RequestContext.RequestMeta;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link RequestContext} — the ScopedValue-based request metadata
 * carrier used across the Operio stack.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Default values outside any scope (actor="system", ipAddress=null, userAgent=null)</li>
 *   <li>Binding inside {@code runWhere} / {@code callWhere}</li>
 *   <li>Auto-clearing when scope exits (including on exception)</li>
 *   <li>Nested scopes: inner value wins, outer restored on exit</li>
 *   <li>Virtual thread propagation: ScopedValue is visible in a virtual
 *       thread spawned inside the scope</li>
 * </ul>
 */
@DisplayName("RequestContext (ScopedValue)")
class RequestContextTest {

    private static final RequestMeta SAMPLE_META = new RequestMeta(
        "alice",
        "10.0.0.1",
        "Mozilla/5.0"
    );

    private static final RequestMeta OTHER_META = new RequestMeta(
        "bob",
        "192.168.1.1",
        "curl/8.0"
    );

    // -----------------------------------------------------------------------
    // Outside scope — defaults
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Outside any scope")
    class OutsideScope {

        @Test
        @DisplayName("get() returns null when no scope is active")
        void getReturnsNullOutsideScope() {
            assertNull(RequestContext.get());
        }

        @Test
        @DisplayName("actor() returns 'system' when no scope is active")
        void actorDefaultsToSystem() {
            assertEquals("system", RequestContext.actor());
        }

        @Test
        @DisplayName("ipAddress() returns null when no scope is active")
        void ipAddressReturnsNullOutsideScope() {
            assertNull(RequestContext.ipAddress());
        }

        @Test
        @DisplayName("userAgent() returns null when no scope is active")
        void userAgentReturnsNullOutsideScope() {
            assertNull(RequestContext.userAgent());
        }

        @Test
        @DisplayName("isPresent() returns false when no scope is active")
        void isPresentFalseOutsideScope() {
            assertFalse(RequestContext.isPresent());
        }
    }

    // -----------------------------------------------------------------------
    // runWhere — bind, auto-clear
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("runWhere()")
    class RunWhere {

        @Test
        @DisplayName("binds request metadata inside the scope")
        void bindsMetadataInsideScope() {
            RequestContext.runWhere(SAMPLE_META, () -> {
                assertSame(SAMPLE_META, RequestContext.get());
                assertEquals("alice", RequestContext.actor());
                assertEquals("10.0.0.1", RequestContext.ipAddress());
                assertEquals("Mozilla/5.0", RequestContext.userAgent());
                assertTrue(RequestContext.isPresent());
            });
        }

        @Test
        @DisplayName("auto-clears metadata after scope exits")
        void autoClearsAfterScope() {
            RequestContext.runWhere(SAMPLE_META, () -> {
                assertEquals("alice", RequestContext.actor());
            });
            // After scope exits, defaults are restored
            assertNull(RequestContext.get());
            assertEquals("system", RequestContext.actor());
            assertNull(RequestContext.ipAddress());
            assertNull(RequestContext.userAgent());
            assertFalse(RequestContext.isPresent());
        }

        @Test
        @DisplayName("auto-clears metadata even when scope throws")
        void autoClearsOnException() {
            RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                RequestContext.runWhere(SAMPLE_META, () -> {
                    assertEquals("alice", RequestContext.actor());
                    throw new RuntimeException("boom");
                })
            );
            assertEquals("boom", thrown.getMessage());
            assertNull(RequestContext.get());
            assertEquals("system", RequestContext.actor());
            assertFalse(RequestContext.isPresent());
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
            "returns value from callable and binds metadata inside scope"
        )
        void returnsValueAndBindsMetadata() throws Throwable {
            String result = RequestContext.callWhere(SAMPLE_META, () -> {
                assertEquals("alice", RequestContext.actor());
                assertEquals("10.0.0.1", RequestContext.ipAddress());
                return "actor=" + RequestContext.actor();
            });

            assertEquals("actor=alice", result);
        }

        @Test
        @DisplayName("auto-clears metadata after callWhere exits")
        void autoClearsAfterCall() throws Throwable {
            RequestContext.callWhere(SAMPLE_META, () -> "ok");

            assertNull(RequestContext.get());
            assertEquals("system", RequestContext.actor());
            assertFalse(RequestContext.isPresent());
        }

        @Test
        @DisplayName("auto-clears metadata even when callable throws")
        void autoClearsOnException() {
            assertThrows(IllegalStateException.class, () ->
                RequestContext.callWhere(SAMPLE_META, () -> {
                    throw new IllegalStateException("fail");
                })
            );
            assertNull(RequestContext.get());
            assertEquals("system", RequestContext.actor());
            assertFalse(RequestContext.isPresent());
        }
    }

    // -----------------------------------------------------------------------
    // Edge cases — null fields in RequestMeta
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Null fields in RequestMeta")
    class NullFields {

        @Test
        @DisplayName("actor() returns 'system' when meta.actor is null")
        void actorDefaultsWhenMetaActorNull() {
            RequestMeta nullActor = new RequestMeta(null, "10.0.0.1", "curl");
            RequestContext.runWhere(nullActor, () -> {
                assertTrue(RequestContext.isPresent());
                assertEquals(
                    "system",
                    RequestContext.actor(),
                    "actor() should fall back to 'system' when the meta's actor is null"
                );
                assertEquals("10.0.0.1", RequestContext.ipAddress());
                assertEquals("curl", RequestContext.userAgent());
            });
        }

        @Test
        @DisplayName(
            "ipAddress() and userAgent() return null when meta fields are null"
        )
        void nullIpAndUserAgent() {
            RequestMeta sparse = new RequestMeta("alice", null, null);
            RequestContext.runWhere(sparse, () -> {
                assertEquals("alice", RequestContext.actor());
                assertNull(RequestContext.ipAddress());
                assertNull(RequestContext.userAgent());
            });
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
            RequestContext.runWhere(SAMPLE_META, () -> {
                assertEquals("alice", RequestContext.actor());
                assertEquals("10.0.0.1", RequestContext.ipAddress());

                RequestContext.runWhere(OTHER_META, () -> {
                    // Inner scope wins
                    assertEquals("bob", RequestContext.actor());
                    assertEquals("192.168.1.1", RequestContext.ipAddress());
                    assertEquals("curl/8.0", RequestContext.userAgent());
                });

                // Outer restored
                assertEquals("alice", RequestContext.actor());
                assertEquals("10.0.0.1", RequestContext.ipAddress());
                assertEquals("Mozilla/5.0", RequestContext.userAgent());
            });

            // All scopes exited
            assertNull(RequestContext.get());
            assertEquals("system", RequestContext.actor());
        }

        @Test
        @DisplayName("triple-nested scopes restore correctly")
        void tripleNested() {
            RequestMeta metaC = new RequestMeta(
                "charlie",
                "172.16.0.1",
                "httpie"
            );

            RequestContext.runWhere(SAMPLE_META, () -> {
                assertEquals("alice", RequestContext.actor());

                RequestContext.runWhere(OTHER_META, () -> {
                    assertEquals("bob", RequestContext.actor());

                    RequestContext.runWhere(metaC, () -> {
                        assertEquals("charlie", RequestContext.actor());
                        assertEquals("172.16.0.1", RequestContext.ipAddress());
                    });

                    assertEquals("bob", RequestContext.actor());
                });

                assertEquals("alice", RequestContext.actor());
            });

            assertNull(RequestContext.get());
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
            RequestContext.runWhere(SAMPLE_META, () -> {
                AtomicReference<String> capturedActor = new AtomicReference<>();
                AtomicReference<String> capturedIp = new AtomicReference<>();

                // ScopedValue is NOT automatically inherited by virtual threads
                // spawned via Thread.ofVirtual().start().  This is by design —
                // ScopedValue requires StructuredTaskScope for structured
                // propagation.  A raw virtual thread starts with no bindings.
                Thread vt = Thread.ofVirtual().start(() -> {
                    capturedActor.set(RequestContext.actor());
                    capturedIp.set(RequestContext.ipAddress());
                });

                try {
                    vt.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    fail("Interrupted waiting for virtual thread");
                }

                assertEquals(
                    "system",
                    capturedActor.get(),
                    "ScopedValue should NOT be inherited by a raw virtual thread " +
                        "(Thread.ofVirtual().start()) — actor falls back to 'system'"
                );
                assertNull(
                    capturedIp.get(),
                    "ScopedValue should NOT be inherited by a raw virtual thread " +
                        "(Thread.ofVirtual().start()) — ipAddress falls back to null"
                );
            });
        }

        @Test
        @DisplayName("virtual thread can bind its own metadata via runWhere()")
        void virtualThreadCanBindOwnMetadata() throws Exception {
            CompletableFuture<String> future = new CompletableFuture<>();

            Thread vt = Thread.ofVirtual().start(() ->
                RequestContext.runWhere(SAMPLE_META, () ->
                    future.complete(RequestContext.actor())
                )
            );
            vt.join();

            assertEquals(
                "alice",
                future.get(),
                "Virtual thread should be able to bind its own metadata via runWhere()"
            );
        }

        @Test
        @DisplayName(
            "virtual thread does not see scope after parent scope exits"
        )
        void noLeakAfterScopeExit() throws Exception {
            CompletableFuture<String> future = new CompletableFuture<>();

            RequestContext.runWhere(SAMPLE_META, () -> {
                assertEquals("alice", RequestContext.actor());
            });

            // After scope exits, a new virtual thread should NOT see the metadata
            Thread vt = Thread.ofVirtual().start(() ->
                future.complete(RequestContext.actor())
            );
            vt.join();

            assertEquals(
                "system",
                future.get(),
                "Virtual thread started after scope exits should get default actor"
            );
        }

        @Test
        @DisplayName(
            "two concurrent virtual threads with different metadata are isolated"
        )
        void concurrentVirtualThreadsIsolated() throws Exception {
            CompletableFuture<String> futureA = new CompletableFuture<>();
            CompletableFuture<String> futureB = new CompletableFuture<>();

            Thread vtA = Thread.ofVirtual().start(() ->
                RequestContext.runWhere(SAMPLE_META, () -> {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ignored) {}
                    futureA.complete(RequestContext.actor());
                })
            );

            Thread vtB = Thread.ofVirtual().start(() ->
                RequestContext.runWhere(OTHER_META, () -> {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ignored) {}
                    futureB.complete(RequestContext.actor());
                })
            );

            vtA.join();
            vtB.join();

            assertEquals(
                "alice",
                futureA.get(),
                "Virtual thread A should see alice"
            );
            assertEquals(
                "bob",
                futureB.get(),
                "Virtual thread B should see bob"
            );
        }
    }

    // -----------------------------------------------------------------------
    // RequestMeta record contract
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("RequestMeta record")
    class RequestMetaRecord {

        @Test
        @DisplayName("accessors return constructor values")
        void accessors() {
            RequestMeta meta = new RequestMeta(
                "eve",
                "127.0.0.1",
                "TestAgent/1.0"
            );
            assertEquals("eve", meta.actor());
            assertEquals("127.0.0.1", meta.ipAddress());
            assertEquals("TestAgent/1.0", meta.userAgent());
        }

        @Test
        @DisplayName("equals and hashCode follow record semantics")
        void equalsAndHashCode() {
            RequestMeta a = new RequestMeta("eve", "127.0.0.1", "Agent");
            RequestMeta b = new RequestMeta("eve", "127.0.0.1", "Agent");
            RequestMeta c = new RequestMeta("mallory", "127.0.0.1", "Agent");

            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
            assertNotEquals(a, c);
        }

        @Test
        @DisplayName("toString includes field values")
        void toStringIncludesFields() {
            RequestMeta meta = new RequestMeta("eve", "127.0.0.1", "Agent");
            String s = meta.toString();
            assertTrue(s.contains("eve"), "toString should contain actor");
            assertTrue(
                s.contains("127.0.0.1"),
                "toString should contain ipAddress"
            );
            assertTrue(
                s.contains("Agent"),
                "toString should contain userAgent"
            );
        }
    }
}
