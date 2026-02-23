package com.koostack.operio.rest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.koostack.operio.core.Models.IdentityContext;
import com.koostack.operio.core.RequestContext;
import com.koostack.operio.core.RequestContext.RequestMeta;
import com.koostack.operio.core.TenantContext;
import com.koostack.operio.rest.context.OperioRequestHolder;
import com.koostack.operio.rest.interceptor.TenantScopeInterceptor;
import jakarta.interceptor.InvocationContext;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link TenantScopeInterceptor} — the CDI interceptor that
 * wraps JAX-RS resource method invocations inside {@code TenantContext} and
 * {@code RequestContext} scoped-value bindings.
 *
 * <p>These tests exercise the interceptor in isolation using Mockito mocks
 * for the {@link OperioRequestHolder} and {@link InvocationContext}. The key
 * verification strategy is: inside the intercepted {@code proceed()} call,
 * capture the current values of {@code TenantContext.get()} and
 * {@code RequestContext.actor()} to confirm correct scoped-value binding.
 *
 * <p>Covers:
 * <ul>
 *   <li>Tenant user — both TenantContext and RequestContext are bound</li>
 *   <li>Bootstrap admin (no tenant) — only RequestContext is bound,
 *       TenantContext returns null</li>
 *   <li>Unauthenticated request — neither context bound, proceed() still
 *       called (defensive guard)</li>
 *   <li>TenantContext is cleared after scope exits</li>
 *   <li>RequestContext actor metadata (subject, remoteAddress, userAgent)
 *       is correctly populated</li>
 *   <li>Exceptions thrown by proceed() propagate through the interceptor</li>
 *   <li>Return value from proceed() is passed through the interceptor</li>
 *   <li>Nested interceptor calls with different tenants maintain isolation</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TenantScopeInterceptor")
class TenantScopeInterceptorTest {

    // -----------------------------------------------------------------------
    // Mocks
    // -----------------------------------------------------------------------

    @Mock
    private OperioRequestHolder requestHolder;

    @Mock
    private InvocationContext invocationContext;

    // -----------------------------------------------------------------------
    // System under test
    // -----------------------------------------------------------------------

    private TenantScopeInterceptor interceptor;

    @BeforeEach
    void setUp() throws Exception {
        interceptor = new TenantScopeInterceptor();
        injectField(interceptor, "requestHolder", requestHolder);

        // Default: invocationContext.getTarget() returns a dummy object
        // and getMethod() returns a real method for logging purposes
        lenient().when(invocationContext.getTarget()).thenReturn(this);
        lenient()
            .when(invocationContext.getMethod())
            .thenReturn(getClass().getDeclaredMethod("setUp"));
    }

    // -----------------------------------------------------------------------
    // Reflection helper
    // -----------------------------------------------------------------------

    private static void injectField(
        Object target,
        String fieldName,
        Object value
    ) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Field findField(Class<?> clazz, String fieldName)
        throws NoSuchFieldException {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(
            "Field '" +
                fieldName +
                "' not found in " +
                clazz.getName() +
                " or its superclasses"
        );
    }

    /**
     * Invokes the package-private {@code wrapInScopedContext} method via
     * reflection so the test (in a different package) can exercise it.
     */
    private Object invokeWrap() throws Exception {
        java.lang.reflect.Method m =
            TenantScopeInterceptor.class.getDeclaredMethod(
                "wrapInScopedContext",
                InvocationContext.class
            );
        m.setAccessible(true);
        try {
            return m.invoke(interceptor, invocationContext);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) throw ex;
            if (cause instanceof Error err) throw err;
            throw e;
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static IdentityContext tenantIdentity(
        String subject,
        String tenantId,
        Set<String> roles
    ) {
        return new IdentityContext(
            subject,
            "test-token-" + subject,
            subject + "@example.com",
            roles,
            Map.of(),
            tenantId,
            "api-key"
        );
    }

    private static IdentityContext bootstrapIdentity() {
        return new IdentityContext(
            "bootstrap-admin",
            "bootstrap-key-value",
            null,
            Set.of("super-admin"),
            Map.of(),
            null, // no tenant for bootstrap
            "bootstrap-key"
        );
    }

    // =======================================================================
    // Tenant user — both TenantContext and RequestContext bound
    // =======================================================================

    @Nested
    @DisplayName("Tenant user (tenantId present)")
    class TenantUser {

        @Test
        @DisplayName("TenantContext.get() returns tenantId inside proceed()")
        void tenantContextBound() throws Exception {
            IdentityContext identity = tenantIdentity(
                "alice",
                "acme_corp",
                Set.of("admin")
            );
            when(requestHolder.identity()).thenReturn(identity);
            when(requestHolder.remoteAddress()).thenReturn("192.168.1.42");
            when(requestHolder.userAgent()).thenReturn("OperioSDK/1.0");

            AtomicReference<String> capturedTenantId = new AtomicReference<>();

            when(invocationContext.proceed()).thenAnswer(inv -> {
                capturedTenantId.set(TenantContext.get());
                return "result-value";
            });

            Object result = invokeWrap();

            assertEquals(
                "acme_corp",
                capturedTenantId.get(),
                "TenantContext should be bound to 'acme_corp' inside proceed()"
            );
            assertEquals("result-value", result);
        }

        @Test
        @DisplayName(
            "RequestContext.actor() returns correct subject inside proceed()"
        )
        void requestContextActorSubject() throws Exception {
            IdentityContext identity = tenantIdentity(
                "alice",
                "acme_corp",
                Set.of("admin")
            );
            when(requestHolder.identity()).thenReturn(identity);
            when(requestHolder.remoteAddress()).thenReturn("192.168.1.42");
            when(requestHolder.userAgent()).thenReturn("OperioSDK/1.0");

            AtomicReference<String> capturedActor = new AtomicReference<>();

            when(invocationContext.proceed()).thenAnswer(inv -> {
                RequestMeta meta = RequestContext.get();
                if (meta != null) {
                    capturedActor.set(meta.actor());
                }
                return null;
            });

            invokeWrap();

            assertEquals(
                "alice",
                capturedActor.get(),
                "RequestContext.actor().actor() should be 'alice' inside proceed()"
            );
        }

        @Test
        @DisplayName(
            "RequestContext.actor() returns correct remoteAddress inside proceed()"
        )
        void requestContextActorRemoteAddress() throws Exception {
            IdentityContext identity = tenantIdentity(
                "bob",
                "beta_co",
                Set.of("writer")
            );
            when(requestHolder.identity()).thenReturn(identity);
            when(requestHolder.remoteAddress()).thenReturn("10.0.0.5");
            when(requestHolder.userAgent()).thenReturn("curl/8.0");

            AtomicReference<String> capturedAddr = new AtomicReference<>();

            when(invocationContext.proceed()).thenAnswer(inv -> {
                RequestMeta meta = RequestContext.get();
                if (meta != null) {
                    capturedAddr.set(meta.ipAddress());
                }
                return null;
            });

            invokeWrap();

            assertEquals(
                "10.0.0.5",
                capturedAddr.get(),
                "RequestContext.actor().ipAddress() should be '10.0.0.5'"
            );
        }

        @Test
        @DisplayName(
            "RequestContext.actor() returns correct userAgent inside proceed()"
        )
        void requestContextActorUserAgent() throws Exception {
            IdentityContext identity = tenantIdentity(
                "carol",
                "gamma_inc",
                Set.of("reader")
            );
            when(requestHolder.identity()).thenReturn(identity);
            when(requestHolder.remoteAddress()).thenReturn("172.16.0.1");
            when(requestHolder.userAgent()).thenReturn(
                "Mozilla/5.0 Firefox/120"
            );

            AtomicReference<String> capturedUA = new AtomicReference<>();

            when(invocationContext.proceed()).thenAnswer(inv -> {
                RequestMeta meta = RequestContext.get();
                if (meta != null) {
                    capturedUA.set(meta.userAgent());
                }
                return null;
            });

            invokeWrap();

            assertEquals(
                "Mozilla/5.0 Firefox/120",
                capturedUA.get(),
                "RequestContext.actor().userAgent() should be 'Mozilla/5.0 Firefox/120'"
            );
        }

        @Test
        @DisplayName("TenantContext is cleared after scope exits")
        void tenantContextClearedAfterScope() throws Exception {
            IdentityContext identity = tenantIdentity(
                "dave",
                "delta_llc",
                Set.of("admin")
            );
            when(requestHolder.identity()).thenReturn(identity);
            when(requestHolder.remoteAddress()).thenReturn(null);
            when(requestHolder.userAgent()).thenReturn(null);

            when(invocationContext.proceed()).thenAnswer(inv -> {
                assertEquals("delta_llc", TenantContext.get());
                return null;
            });

            invokeWrap();

            // After the interceptor returns, TenantContext should be unbound
            assertNull(
                TenantContext.get(),
                "TenantContext should be null after interceptor scope exits"
            );
        }

        @Test
        @DisplayName("return value from proceed() is passed through")
        void returnValuePassedThrough() throws Exception {
            IdentityContext identity = tenantIdentity(
                "eve",
                "echo_corp",
                Set.of("admin")
            );
            when(requestHolder.identity()).thenReturn(identity);
            when(requestHolder.remoteAddress()).thenReturn(null);
            when(requestHolder.userAgent()).thenReturn(null);

            Object expectedResult = Map.of("status", "OK", "count", 42);
            when(invocationContext.proceed()).thenReturn(expectedResult);

            Object actualResult = invokeWrap();

            assertSame(
                expectedResult,
                actualResult,
                "Interceptor should pass through the proceed() return value"
            );
        }

        @Test
        @DisplayName("null return value from proceed() is passed through")
        void nullReturnValuePassedThrough() throws Exception {
            IdentityContext identity = tenantIdentity(
                "frank",
                "foxtrot_co",
                Set.of("writer")
            );
            when(requestHolder.identity()).thenReturn(identity);
            when(requestHolder.remoteAddress()).thenReturn(null);
            when(requestHolder.userAgent()).thenReturn(null);

            when(invocationContext.proceed()).thenReturn(null);

            Object result = invokeWrap();

            assertNull(
                result,
                "Interceptor should pass through null return value"
            );
        }

        @Test
        @DisplayName("proceed() is called exactly once")
        void proceedCalledOnce() throws Exception {
            IdentityContext identity = tenantIdentity(
                "gina",
                "golf_tech",
                Set.of("admin")
            );
            when(requestHolder.identity()).thenReturn(identity);
            when(requestHolder.remoteAddress()).thenReturn(null);
            when(requestHolder.userAgent()).thenReturn(null);
            when(invocationContext.proceed()).thenReturn("done");

            invokeWrap();

            verify(invocationContext, times(1)).proceed();
        }
    }

    // =======================================================================
    // Bootstrap admin (no tenantId) — only RequestContext bound
    // =======================================================================

    @Nested
    @DisplayName("Bootstrap admin (no tenantId)")
    class BootstrapAdmin {

        @Test
        @DisplayName(
            "TenantContext.get() returns null inside proceed() for bootstrap"
        )
        void tenantContextIsNull() throws Exception {
            IdentityContext identity = bootstrapIdentity();
            when(requestHolder.identity()).thenReturn(identity);
            when(requestHolder.remoteAddress()).thenReturn("10.0.0.1");
            when(requestHolder.userAgent()).thenReturn("curl/8.0");

            AtomicReference<String> capturedTenantId = new AtomicReference<>(
                "SENTINEL"
            );

            when(invocationContext.proceed()).thenAnswer(inv -> {
                capturedTenantId.set(TenantContext.get());
                return null;
            });

            invokeWrap();

            assertNull(
                capturedTenantId.get(),
                "TenantContext should be null for bootstrap admin (no tenant)"
            );
        }

        @Test
        @DisplayName(
            "RequestContext.actor() is still bound for bootstrap admin"
        )
        void requestContextBound() throws Exception {
            IdentityContext identity = bootstrapIdentity();
            when(requestHolder.identity()).thenReturn(identity);
            when(requestHolder.remoteAddress()).thenReturn("10.0.0.1");
            when(requestHolder.userAgent()).thenReturn("curl/8.0");

            AtomicReference<RequestMeta> capturedMeta = new AtomicReference<>();

            when(invocationContext.proceed()).thenAnswer(inv -> {
                capturedMeta.set(RequestContext.get());
                return null;
            });

            invokeWrap();

            assertNotNull(
                capturedMeta.get(),
                "RequestContext should be bound for bootstrap admin"
            );
            assertEquals("bootstrap-admin", capturedMeta.get().actor());
            assertEquals("10.0.0.1", capturedMeta.get().ipAddress());
            assertEquals("curl/8.0", capturedMeta.get().userAgent());
        }

        @Test
        @DisplayName("proceed() is called for bootstrap admin")
        void proceedCalledForBootstrap() throws Exception {
            IdentityContext identity = bootstrapIdentity();
            when(requestHolder.identity()).thenReturn(identity);
            when(requestHolder.remoteAddress()).thenReturn(null);
            when(requestHolder.userAgent()).thenReturn(null);
            when(invocationContext.proceed()).thenReturn("bootstrap-result");

            Object result = invokeWrap();

            verify(invocationContext, times(1)).proceed();
            assertEquals("bootstrap-result", result);
        }

        @Test
        @DisplayName("return value passed through for bootstrap admin")
        void returnValuePassedThrough() throws Exception {
            IdentityContext identity = bootstrapIdentity();
            when(requestHolder.identity()).thenReturn(identity);
            when(requestHolder.remoteAddress()).thenReturn(null);
            when(requestHolder.userAgent()).thenReturn(null);

            Map<String, Object> expectedResult = Map.of("tenants", 3);
            when(invocationContext.proceed()).thenReturn(expectedResult);

            Object result = invokeWrap();

            assertSame(expectedResult, result);
        }
    }

    // =======================================================================
    // Unauthenticated request — no identity in holder
    // =======================================================================

    @Nested
    @DisplayName("Unauthenticated request (no identity)")
    class UnauthenticatedRequest {

        @Test
        @DisplayName(
            "proceed() is called even without identity (defensive guard)"
        )
        void proceedCalledWithoutIdentity() throws Exception {
            when(requestHolder.identity()).thenReturn(null);
            when(invocationContext.proceed()).thenReturn(
                "unauthenticated-result"
            );

            Object result = invokeWrap();

            verify(invocationContext, times(1)).proceed();
            assertEquals("unauthenticated-result", result);
        }

        @Test
        @DisplayName("TenantContext is NOT bound for unauthenticated request")
        void tenantContextNotBound() throws Exception {
            when(requestHolder.identity()).thenReturn(null);

            AtomicReference<String> capturedTenantId = new AtomicReference<>(
                "SENTINEL"
            );

            when(invocationContext.proceed()).thenAnswer(inv -> {
                capturedTenantId.set(TenantContext.get());
                return null;
            });

            invokeWrap();

            assertNull(
                capturedTenantId.get(),
                "TenantContext should be null for unauthenticated requests"
            );
        }

        @Test
        @DisplayName(
            "return value from proceed() passes through for unauthenticated"
        )
        void returnValuePassedThrough() throws Exception {
            when(requestHolder.identity()).thenReturn(null);
            when(invocationContext.proceed()).thenReturn("fallback-value");

            Object result = invokeWrap();

            assertEquals("fallback-value", result);
        }
    }

    // =======================================================================
    // Exception propagation
    // =======================================================================

    @Nested
    @DisplayName("Exception propagation from proceed()")
    class ExceptionPropagation {

        @Test
        @DisplayName(
            "RuntimeException from proceed() propagates through interceptor"
        )
        void runtimeExceptionPropagates() throws Exception {
            IdentityContext identity = tenantIdentity(
                "hank",
                "hotel_inc",
                Set.of("admin")
            );
            when(requestHolder.identity()).thenReturn(identity);
            when(requestHolder.remoteAddress()).thenReturn(null);
            when(requestHolder.userAgent()).thenReturn(null);

            RuntimeException expectedException = new RuntimeException(
                "DB connection lost"
            );
            when(invocationContext.proceed()).thenThrow(expectedException);

            RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                invokeWrap()
            );

            assertSame(
                expectedException,
                thrown,
                "The exact exception from proceed() should propagate"
            );
        }

        @Test
        @DisplayName(
            "checked Exception from proceed() propagates through interceptor"
        )
        void checkedExceptionPropagates() throws Exception {
            IdentityContext identity = tenantIdentity(
                "iris",
                "india_co",
                Set.of("writer")
            );
            when(requestHolder.identity()).thenReturn(identity);
            when(requestHolder.remoteAddress()).thenReturn(null);
            when(requestHolder.userAgent()).thenReturn(null);

            Exception expectedException = new Exception(
                "Checked exception from resource"
            );
            when(invocationContext.proceed()).thenThrow(expectedException);

            Exception thrown = assertThrows(Exception.class, () ->
                invokeWrap()
            );

            assertSame(
                expectedException,
                thrown,
                "The exact checked exception from proceed() should propagate"
            );
        }

        @Test
        @DisplayName("TenantContext is cleared after exception in proceed()")
        void tenantContextClearedAfterException() throws Exception {
            IdentityContext identity = tenantIdentity(
                "jack",
                "juliet_tech",
                Set.of("admin")
            );
            when(requestHolder.identity()).thenReturn(identity);
            when(requestHolder.remoteAddress()).thenReturn(null);
            when(requestHolder.userAgent()).thenReturn(null);

            when(invocationContext.proceed()).thenThrow(
                new RuntimeException("Boom")
            );

            try {
                invokeWrap();
            } catch (RuntimeException ignored) {
                // expected
            }

            assertNull(
                TenantContext.get(),
                "TenantContext should be cleared after an exception in proceed()"
            );
        }

        @Test
        @DisplayName("Exception propagates for unauthenticated request too")
        void exceptionPropagatesForUnauthenticated() throws Exception {
            when(requestHolder.identity()).thenReturn(null);

            RuntimeException expectedException = new RuntimeException(
                "Unauthenticated failure"
            );
            when(invocationContext.proceed()).thenThrow(expectedException);

            RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                invokeWrap()
            );

            assertSame(expectedException, thrown);
        }

        @Test
        @DisplayName("Exception propagates for bootstrap admin too")
        void exceptionPropagatesForBootstrap() throws Exception {
            IdentityContext identity = bootstrapIdentity();
            when(requestHolder.identity()).thenReturn(identity);
            when(requestHolder.remoteAddress()).thenReturn(null);
            when(requestHolder.userAgent()).thenReturn(null);

            RuntimeException expectedException = new RuntimeException(
                "Platform error"
            );
            when(invocationContext.proceed()).thenThrow(expectedException);

            RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                invokeWrap()
            );

            assertSame(expectedException, thrown);
        }
    }

    // =======================================================================
    // Null optional metadata fields
    // =======================================================================

    @Nested
    @DisplayName("Null optional metadata in RequestMeta")
    class NullOptionalMetadata {

        @Test
        @DisplayName("null remoteAddress is passed through to RequestMeta")
        void nullRemoteAddress() throws Exception {
            IdentityContext identity = tenantIdentity(
                "kate",
                "kilo_co",
                Set.of("admin")
            );
            when(requestHolder.identity()).thenReturn(identity);
            when(requestHolder.remoteAddress()).thenReturn(null);
            when(requestHolder.userAgent()).thenReturn("Agent/1.0");

            AtomicReference<RequestMeta> capturedMeta = new AtomicReference<>();

            when(invocationContext.proceed()).thenAnswer(inv -> {
                capturedMeta.set(RequestContext.get());
                return null;
            });

            invokeWrap();

            assertNotNull(capturedMeta.get());
            assertEquals("kate", capturedMeta.get().actor());
            assertNull(
                capturedMeta.get().ipAddress(),
                "remoteAddress should be null when holder has null"
            );
            assertEquals("Agent/1.0", capturedMeta.get().userAgent());
        }

        @Test
        @DisplayName("null userAgent is passed through to RequestMeta")
        void nullUserAgent() throws Exception {
            IdentityContext identity = tenantIdentity(
                "leo",
                "lima_llc",
                Set.of("reader")
            );
            when(requestHolder.identity()).thenReturn(identity);
            when(requestHolder.remoteAddress()).thenReturn("10.0.0.99");
            when(requestHolder.userAgent()).thenReturn(null);

            AtomicReference<RequestMeta> capturedMeta = new AtomicReference<>();

            when(invocationContext.proceed()).thenAnswer(inv -> {
                capturedMeta.set(RequestContext.get());
                return null;
            });

            invokeWrap();

            assertNotNull(capturedMeta.get());
            assertEquals("leo", capturedMeta.get().actor());
            assertEquals("10.0.0.99", capturedMeta.get().ipAddress());
            assertNull(
                capturedMeta.get().userAgent(),
                "userAgent should be null when holder has null"
            );
        }

        @Test
        @DisplayName("both null remoteAddress and userAgent are passed through")
        void bothNullOptionals() throws Exception {
            IdentityContext identity = tenantIdentity(
                "mike",
                "mike_co",
                Set.of("admin")
            );
            when(requestHolder.identity()).thenReturn(identity);
            when(requestHolder.remoteAddress()).thenReturn(null);
            when(requestHolder.userAgent()).thenReturn(null);

            AtomicReference<RequestMeta> capturedMeta = new AtomicReference<>();

            when(invocationContext.proceed()).thenAnswer(inv -> {
                capturedMeta.set(RequestContext.get());
                return null;
            });

            invokeWrap();

            assertNotNull(capturedMeta.get());
            assertEquals("mike", capturedMeta.get().actor());
            assertNull(capturedMeta.get().ipAddress());
            assertNull(capturedMeta.get().userAgent());
        }
    }

    // =======================================================================
    // Different tenants produce different TenantContext values
    // =======================================================================

    @Nested
    @DisplayName("Multiple tenants produce correct TenantContext values")
    class MultipleTenants {

        @Test
        @DisplayName(
            "tenant A and tenant B see their own tenantId in TenantContext"
        )
        void differentTenantsSeeDifferentContext() throws Exception {
            // --- Tenant A ---
            IdentityContext identityA = tenantIdentity(
                "alice",
                "tenant_a",
                Set.of("admin")
            );
            when(requestHolder.identity()).thenReturn(identityA);
            when(requestHolder.remoteAddress()).thenReturn(null);
            when(requestHolder.userAgent()).thenReturn(null);

            AtomicReference<String> capturedTenantA = new AtomicReference<>();
            when(invocationContext.proceed()).thenAnswer(inv -> {
                capturedTenantA.set(TenantContext.get());
                return "A";
            });

            Object resultA = invokeWrap();
            assertEquals("tenant_a", capturedTenantA.get());
            assertEquals("A", resultA);

            // After exit, TenantContext should be unbound
            assertNull(TenantContext.get());

            // --- Tenant B ---
            IdentityContext identityB = tenantIdentity(
                "bob",
                "tenant_b",
                Set.of("writer")
            );
            when(requestHolder.identity()).thenReturn(identityB);

            AtomicReference<String> capturedTenantB = new AtomicReference<>();
            when(invocationContext.proceed()).thenAnswer(inv -> {
                capturedTenantB.set(TenantContext.get());
                return "B";
            });

            Object resultB = invokeWrap();
            assertEquals("tenant_b", capturedTenantB.get());
            assertEquals("B", resultB);

            // After exit, TenantContext should be unbound again
            assertNull(TenantContext.get());
        }

        @Test
        @DisplayName(
            "tenant context from first call does not leak into second call"
        )
        void noContextLeakBetweenCalls() throws Exception {
            // First call: tenant_x
            IdentityContext identity1 = tenantIdentity(
                "user1",
                "tenant_x",
                Set.of("admin")
            );
            when(requestHolder.identity()).thenReturn(identity1);
            when(requestHolder.remoteAddress()).thenReturn(null);
            when(requestHolder.userAgent()).thenReturn(null);
            when(invocationContext.proceed()).thenReturn(null);

            invokeWrap();

            // Between calls: should be unbound
            assertNull(
                TenantContext.get(),
                "TenantContext should be null between interceptor calls"
            );

            // Second call: tenant_y
            IdentityContext identity2 = tenantIdentity(
                "user2",
                "tenant_y",
                Set.of("writer")
            );
            when(requestHolder.identity()).thenReturn(identity2);

            AtomicReference<String> capturedTenant = new AtomicReference<>();
            when(invocationContext.proceed()).thenAnswer(inv -> {
                capturedTenant.set(TenantContext.get());
                return null;
            });

            invokeWrap();

            assertEquals(
                "tenant_y",
                capturedTenant.get(),
                "Second call should see tenant_y, not tenant_x"
            );

            assertNull(
                TenantContext.get(),
                "TenantContext should be null after second interceptor call"
            );
        }
    }

    // =======================================================================
    // Edge case: empty tenantId string
    // =======================================================================

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName(
            "identity with non-null but valid tenantId binds TenantContext"
        )
        void nonNullTenantIdBindsContext() throws Exception {
            IdentityContext identity = tenantIdentity(
                "nancy",
                "n",
                Set.of("admin")
            );
            when(requestHolder.identity()).thenReturn(identity);
            when(requestHolder.remoteAddress()).thenReturn(null);
            when(requestHolder.userAgent()).thenReturn(null);

            AtomicReference<String> capturedTenant = new AtomicReference<>();
            when(invocationContext.proceed()).thenAnswer(inv -> {
                capturedTenant.set(TenantContext.get());
                return null;
            });

            invokeWrap();

            assertEquals(
                "n",
                capturedTenant.get(),
                "Even a single-character tenantId should be bound"
            );
        }

        @Test
        @DisplayName("RequestMeta subject is always from identity.subject()")
        void requestMetaSubjectFromIdentity() throws Exception {
            IdentityContext identity = new IdentityContext(
                "custom-subject-with-special-chars@domain.com",
                "token-value",
                "email@domain.com",
                Set.of("admin"),
                Map.of("custom_claim", "value"),
                "tenant_special",
                "custom-provider"
            );
            when(requestHolder.identity()).thenReturn(identity);
            when(requestHolder.remoteAddress()).thenReturn("::1");
            when(requestHolder.userAgent()).thenReturn("CustomAgent/3.0");

            AtomicReference<RequestMeta> capturedMeta = new AtomicReference<>();
            when(invocationContext.proceed()).thenAnswer(inv -> {
                capturedMeta.set(RequestContext.get());
                return null;
            });

            invokeWrap();

            assertNotNull(capturedMeta.get());
            assertEquals(
                "custom-subject-with-special-chars@domain.com",
                capturedMeta.get().actor()
            );
            assertEquals("::1", capturedMeta.get().ipAddress());
            assertEquals("CustomAgent/3.0", capturedMeta.get().userAgent());
        }
    }

    // =======================================================================
    // TenantContext.isPresent() verification
    // =======================================================================

    @Nested
    @DisplayName("TenantContext.isPresent() verification")
    class TenantContextIsPresent {

        @Test
        @DisplayName(
            "TenantContext.isPresent() returns true inside tenant scope"
        )
        void isPresentInsideTenantScope() throws Exception {
            IdentityContext identity = tenantIdentity(
                "oscar",
                "oscar_co",
                Set.of("admin")
            );
            when(requestHolder.identity()).thenReturn(identity);
            when(requestHolder.remoteAddress()).thenReturn(null);
            when(requestHolder.userAgent()).thenReturn(null);

            AtomicReference<Boolean> capturedIsPresent =
                new AtomicReference<>();
            when(invocationContext.proceed()).thenAnswer(inv -> {
                capturedIsPresent.set(TenantContext.isPresent());
                return null;
            });

            invokeWrap();

            assertTrue(
                capturedIsPresent.get(),
                "TenantContext.isPresent() should return true inside tenant scope"
            );
        }

        @Test
        @DisplayName(
            "TenantContext.isPresent() returns false after scope exits"
        )
        void isNotPresentAfterScope() throws Exception {
            IdentityContext identity = tenantIdentity(
                "peter",
                "papa_inc",
                Set.of("admin")
            );
            when(requestHolder.identity()).thenReturn(identity);
            when(requestHolder.remoteAddress()).thenReturn(null);
            when(requestHolder.userAgent()).thenReturn(null);
            when(invocationContext.proceed()).thenReturn(null);

            invokeWrap();

            assertFalse(
                TenantContext.isPresent(),
                "TenantContext.isPresent() should return false after scope exits"
            );
        }

        @Test
        @DisplayName(
            "TenantContext.isPresent() returns false for bootstrap (no tenant)"
        )
        void isNotPresentForBootstrap() throws Exception {
            IdentityContext identity = bootstrapIdentity();
            when(requestHolder.identity()).thenReturn(identity);
            when(requestHolder.remoteAddress()).thenReturn(null);
            when(requestHolder.userAgent()).thenReturn(null);

            AtomicReference<Boolean> capturedIsPresent =
                new AtomicReference<>();
            when(invocationContext.proceed()).thenAnswer(inv -> {
                capturedIsPresent.set(TenantContext.isPresent());
                return null;
            });

            invokeWrap();

            assertFalse(
                capturedIsPresent.get(),
                "TenantContext.isPresent() should return false for bootstrap admin"
            );
        }

        @Test
        @DisplayName(
            "TenantContext.isPresent() returns false for unauthenticated"
        )
        void isNotPresentForUnauthenticated() throws Exception {
            when(requestHolder.identity()).thenReturn(null);

            AtomicReference<Boolean> capturedIsPresent =
                new AtomicReference<>();
            when(invocationContext.proceed()).thenAnswer(inv -> {
                capturedIsPresent.set(TenantContext.isPresent());
                return null;
            });

            invokeWrap();

            assertFalse(
                capturedIsPresent.get(),
                "TenantContext.isPresent() should return false for unauthenticated requests"
            );
        }
    }
}
