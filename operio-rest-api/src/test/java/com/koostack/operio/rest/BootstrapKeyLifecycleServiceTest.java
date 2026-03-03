package com.koostack.operio.rest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.koostack.operio.core.platform.core.CoreQueryService;
import com.koostack.operio.core.platform.core.models.TenantInfo;
import com.koostack.operio.core.platform.iam.IamQueryService;
import com.koostack.operio.rest.config.OperioConfig;
import com.koostack.operio.rest.lifecycle.BootstrapKeyLifecycleService;
import jakarta.enterprise.inject.Instance;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link BootstrapKeyLifecycleService} — the service that
 * manages the lifecycle of the bootstrap super-admin key (active, consumed,
 * force-override).
 *
 * <p>Covers:
 * <ul>
 *   <li>No bootstrap key configured — all queries return inactive/false</li>
 *   <li>Active bootstrap key — token matching, isActive</li>
 *   <li>Auto-disable after API key creation — markBootstrapConsumed()</li>
 *   <li>Force override — isBootstrapKeyActive when force=true</li>
 *   <li>Startup check — existing API keys auto-disable, no tenants = active</li>
 *   <li>Token matching — isActiveBootstrapKey, isBootstrapKeyValue</li>
 *   <li>Idempotent consumption — double markBootstrapConsumed()</li>
 *   <li>Null/blank token edge cases</li>
 * </ul>
 */
@DisplayName("BootstrapKeyLifecycleService")
@ExtendWith(MockitoExtension.class)
class BootstrapKeyLifecycleServiceTest {

    private static final String BOOTSTRAP_KEY =
        "super-secret-bootstrap-key-12345";

    @Mock
    private OperioConfig config;

    @Mock
    private OperioConfig.SuperAdminConfig superAdminConfig;

    @Mock
    private CoreQueryService<?> coreQueryService;

    @Mock
    private IamQueryService<?> iamQueryService;

    @Mock
    @SuppressWarnings("rawtypes")
    private Instance<CoreQueryService> coreQueryServiceInstance;

    @Mock
    @SuppressWarnings("rawtypes")
    private Instance<IamQueryService> iamQueryServiceInstance;

    private BootstrapKeyLifecycleService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws Exception {
        service = new BootstrapKeyLifecycleService();
        injectField(service, "config", config);

        lenient().when(coreQueryServiceInstance.isResolvable()).thenReturn(true);
        lenient().when(coreQueryServiceInstance.get()).thenReturn(coreQueryService);
        lenient().when(iamQueryServiceInstance.isResolvable()).thenReturn(true);
        lenient().when(iamQueryServiceInstance.get()).thenReturn(iamQueryService);

        injectField(service, "coreQueryServiceInstance", coreQueryServiceInstance);
        injectField(service, "iamQueryServiceInstance", iamQueryServiceInstance);
    }

    // -----------------------------------------------------------------------
    // Reflection helpers
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

    private static Field findField(Class<?> clazz, String name) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new RuntimeException(
            "Field '" + name + "' not found in " + clazz
        );
    }

    private static void setBootstrapConsumed(
        BootstrapKeyLifecycleService svc,
        boolean value
    ) throws Exception {
        Field field = findField(svc.getClass(), "bootstrapConsumed");
        field.setAccessible(true);
        ((AtomicBoolean) field.get(svc)).set(value);
    }

    /**
     * Invokes the package-private {@code onStartup(StartupEvent)} method
     * via reflection so the test (in a different package) can exercise it.
     */
    private static void invokeOnStartup(BootstrapKeyLifecycleService svc)
        throws Exception {
        Method m = BootstrapKeyLifecycleService.class.getDeclaredMethod(
            "onStartup",
            io.quarkus.runtime.StartupEvent.class
        );
        m.setAccessible(true);
        m.invoke(svc, (Object) null);
    }

    // -----------------------------------------------------------------------
    // Config stub helpers
    // -----------------------------------------------------------------------

    /** Configures mocks so that a bootstrap key IS present. */
    private void givenBootstrapKeyConfigured() {
        givenBootstrapKeyConfigured(BOOTSTRAP_KEY, false);
    }

    private void givenBootstrapKeyConfigured(
        String key,
        boolean forceBootstrap
    ) {
        lenient()
            .when(superAdminConfig.bootstrapKey())
            .thenReturn(Optional.of(key));
        lenient()
            .when(superAdminConfig.forceBootstrap())
            .thenReturn(forceBootstrap);
        lenient()
            .when(config.superAdmin())
            .thenReturn(Optional.of(superAdminConfig));
    }

    /** Configures mocks so that NO bootstrap key is present. */
    private void givenNoBootstrapKeyConfigured() {
        lenient().when(config.superAdmin()).thenReturn(Optional.empty());
    }

    /** Configures mocks so the bootstrap key is blank. */
    private void givenBlankBootstrapKeyConfigured() {
        lenient()
            .when(superAdminConfig.bootstrapKey())
            .thenReturn(Optional.of("   "));
        lenient().when(superAdminConfig.forceBootstrap()).thenReturn(false);
        lenient()
            .when(config.superAdmin())
            .thenReturn(Optional.of(superAdminConfig));
    }

    /** Configures mocks so force-bootstrap is enabled. */
    private void givenForceBootstrapEnabled() {
        givenBootstrapKeyConfigured(BOOTSTRAP_KEY, true);
    }

    // -----------------------------------------------------------------------
    // Platform stub helpers
    // -----------------------------------------------------------------------

    private static TenantInfo tenantInfo(String tenantId) {
        return new TenantInfo(
            tenantId,
            "Tenant " + tenantId,
            "STANDARD",
            "ACTIVE",
            "t_" + tenantId,
            null,
            Instant.now(),
            Instant.now()
        );
    }

    private static IamQueryService.ApiKeyInfo activeApiKey(String tenantId) {
        return new IamQueryService.ApiKeyInfo(
            "key-id",
            "key-prefix",
            "key-name",
            tenantId,
            "production",
            "admin@" + tenantId,
            "admin",
            "ACTIVE",
            Instant.now(),
            null
        );
    }

    private static IamQueryService.ApiKeyInfo revokedApiKey(String tenantId) {
        return new IamQueryService.ApiKeyInfo(
            "key-id",
            "key-prefix",
            "key-name",
            tenantId,
            "production",
            "admin@" + tenantId,
            "admin",
            "REVOKED",
            Instant.now(),
            null
        );
    }

    // ===================================================================
    // Test classes
    // ===================================================================

    @Nested
    @DisplayName("No bootstrap key configured")
    class NoKeyConfigured {

        @BeforeEach
        void setup() {
            givenNoBootstrapKeyConfigured();
        }

        @Test
        @DisplayName("isBootstrapKeyActive() returns false")
        void isNotActive() {
            assertFalse(service.isBootstrapKeyActive());
        }

        @Test
        @DisplayName("isActiveBootstrapKey(token) returns false for any token")
        void noActiveMatch() {
            assertFalse(service.isActiveBootstrapKey(BOOTSTRAP_KEY));
            assertFalse(service.isActiveBootstrapKey("random-token"));
        }

        @Test
        @DisplayName("isBootstrapKeyValue(token) returns false for any token")
        void noValueMatch() {
            assertFalse(service.isBootstrapKeyValue(BOOTSTRAP_KEY));
            assertFalse(service.isBootstrapKeyValue("random-token"));
        }

        @Test
        @DisplayName("isBootstrapConsumed() returns false by default")
        void notConsumed() {
            assertFalse(service.isBootstrapConsumed());
        }

        @Test
        @DisplayName(
            "markBootstrapConsumed() is safe to call (no-op in practice)"
        )
        void markConsumedSafe() {
            assertDoesNotThrow(() -> service.markBootstrapConsumed());
            // isBootstrapConsumed is true because the AtomicBoolean was set,
            // but isBootstrapKeyActive still returns false (no key configured)
            assertTrue(service.isBootstrapConsumed());
            assertFalse(service.isBootstrapKeyActive());
        }
    }

    @Nested
    @DisplayName("Blank bootstrap key configured")
    class BlankKeyConfigured {

        @BeforeEach
        void setup() {
            givenBlankBootstrapKeyConfigured();
        }

        @Test
        @DisplayName("isBootstrapKeyActive() returns false for blank key")
        void isNotActive() {
            assertFalse(service.isBootstrapKeyActive());
        }

        @Test
        @DisplayName("isActiveBootstrapKey(token) returns false")
        void noActiveMatch() {
            assertFalse(service.isActiveBootstrapKey("   "));
            assertFalse(service.isActiveBootstrapKey(BOOTSTRAP_KEY));
        }

        @Test
        @DisplayName("isBootstrapKeyValue(token) returns false")
        void noValueMatch() {
            assertFalse(service.isBootstrapKeyValue("   "));
            assertFalse(service.isBootstrapKeyValue(BOOTSTRAP_KEY));
        }
    }

    @Nested
    @DisplayName("Bootstrap key configured — super-admin absent")
    class SuperAdminPresentKeyAbsent {

        @BeforeEach
        void setup() {
            when(superAdminConfig.bootstrapKey()).thenReturn(Optional.empty());
            lenient().when(superAdminConfig.forceBootstrap()).thenReturn(false);
            when(config.superAdmin()).thenReturn(Optional.of(superAdminConfig));
        }

        @Test
        @DisplayName(
            "isBootstrapKeyActive() returns false when key is Optional.empty"
        )
        void isNotActive() {
            assertFalse(service.isBootstrapKeyActive());
        }

        @Test
        @DisplayName("isActiveBootstrapKey(token) returns false")
        void noActiveMatch() {
            assertFalse(service.isActiveBootstrapKey("any-token"));
        }

        @Test
        @DisplayName("isBootstrapKeyValue(token) returns false")
        void noValueMatch() {
            assertFalse(service.isBootstrapKeyValue("any-token"));
        }
    }

    @Nested
    @DisplayName("Active bootstrap key (fresh deployment)")
    class ActiveBootstrapKey {

        @BeforeEach
        void setup() {
            givenBootstrapKeyConfigured();
        }

        @Test
        @DisplayName("isBootstrapKeyActive() returns true")
        void isActive() {
            assertTrue(service.isBootstrapKeyActive());
        }

        @Test
        @DisplayName("isBootstrapConsumed() returns false initially")
        void notConsumedInitially() {
            assertFalse(service.isBootstrapConsumed());
        }

        @Test
        @DisplayName("isActiveBootstrapKey(correct token) returns true")
        void matchesCorrectToken() {
            assertTrue(service.isActiveBootstrapKey(BOOTSTRAP_KEY));
        }

        @Test
        @DisplayName("isActiveBootstrapKey(wrong token) returns false")
        void rejectsWrongToken() {
            assertFalse(service.isActiveBootstrapKey("wrong-key"));
        }

        @Test
        @DisplayName("isActiveBootstrapKey(null) returns false")
        void rejectsNullToken() {
            assertFalse(service.isActiveBootstrapKey(null));
        }

        @Test
        @DisplayName("isActiveBootstrapKey(blank) returns false")
        void rejectsBlankToken() {
            assertFalse(service.isActiveBootstrapKey(""));
            assertFalse(service.isActiveBootstrapKey("   "));
        }

        @Test
        @DisplayName("isBootstrapKeyValue(correct token) returns true")
        void valueMatchesCorrectToken() {
            assertTrue(service.isBootstrapKeyValue(BOOTSTRAP_KEY));
        }

        @Test
        @DisplayName("isBootstrapKeyValue(wrong token) returns false")
        void valueRejectsWrongToken() {
            assertFalse(service.isBootstrapKeyValue("wrong-key"));
        }

        @Test
        @DisplayName("isBootstrapKeyValue(null) returns false")
        void valueRejectsNull() {
            assertFalse(service.isBootstrapKeyValue(null));
        }

        @Test
        @DisplayName("isBootstrapKeyValue(blank) returns false")
        void valueRejectsBlank() {
            assertFalse(service.isBootstrapKeyValue(""));
            assertFalse(service.isBootstrapKeyValue("   "));
        }
    }

    @Nested
    @DisplayName("Auto-disable after API key creation")
    class AutoDisable {

        @BeforeEach
        void setup() {
            givenBootstrapKeyConfigured();
        }

        @Test
        @DisplayName("markBootstrapConsumed() sets isBootstrapConsumed to true")
        void consumedAfterMark() {
            assertFalse(service.isBootstrapConsumed());
            service.markBootstrapConsumed();
            assertTrue(service.isBootstrapConsumed());
        }

        @Test
        @DisplayName("isBootstrapKeyActive() returns false after consumption")
        void inactiveAfterConsumption() {
            service.markBootstrapConsumed();
            assertFalse(service.isBootstrapKeyActive());
        }

        @Test
        @DisplayName(
            "isActiveBootstrapKey(correct token) returns false after consumption"
        )
        void tokenRejectedAfterConsumption() {
            service.markBootstrapConsumed();
            assertFalse(service.isActiveBootstrapKey(BOOTSTRAP_KEY));
        }

        @Test
        @DisplayName(
            "isBootstrapKeyValue(correct token) still returns true after consumption"
        )
        void valueStillMatchesAfterConsumption() {
            service.markBootstrapConsumed();
            // isBootstrapKeyValue checks the value regardless of active state
            assertTrue(service.isBootstrapKeyValue(BOOTSTRAP_KEY));
        }

        @Test
        @DisplayName("markBootstrapConsumed() is idempotent")
        void idempotentConsumption() {
            service.markBootstrapConsumed();
            assertTrue(service.isBootstrapConsumed());

            // Second call should not throw or change state
            assertDoesNotThrow(() -> service.markBootstrapConsumed());
            assertTrue(service.isBootstrapConsumed());
            assertFalse(service.isBootstrapKeyActive());
        }

        @Test
        @DisplayName("monotonic state: consumed never reverts to active")
        void monotonicState() {
            assertTrue(service.isBootstrapKeyActive());
            service.markBootstrapConsumed();
            assertFalse(service.isBootstrapKeyActive());
            // No API to un-consume — stays consumed
            assertFalse(service.isBootstrapKeyActive());
            assertTrue(service.isBootstrapConsumed());
        }
    }

    @Nested
    @DisplayName("Force override (OPERIO_FORCE_BOOTSTRAP=true)")
    class ForceOverride {

        @BeforeEach
        void setup() {
            givenForceBootstrapEnabled();
        }

        @Test
        @DisplayName("isBootstrapKeyActive() returns true even when consumed")
        void activeWhenConsumedWithForce() {
            service.markBootstrapConsumed();
            assertTrue(service.isBootstrapConsumed());
            assertTrue(
                service.isBootstrapKeyActive(),
                "Force override should keep bootstrap key active"
            );
        }

        @Test
        @DisplayName(
            "isActiveBootstrapKey(correct token) returns true when consumed + force"
        )
        void tokenAcceptedWhenConsumedWithForce() {
            service.markBootstrapConsumed();
            assertTrue(service.isActiveBootstrapKey(BOOTSTRAP_KEY));
        }

        @Test
        @DisplayName(
            "isActiveBootstrapKey(wrong token) returns false even with force"
        )
        void wrongTokenRejectedWithForce() {
            assertFalse(service.isActiveBootstrapKey("wrong-key"));
        }

        @Test
        @DisplayName("isBootstrapKeyActive() returns true initially with force")
        void activeInitiallyWithForce() {
            assertTrue(service.isBootstrapKeyActive());
        }

        @Test
        @DisplayName(
            "isBootstrapConsumed() reflects consumed state independent of force"
        )
        void consumedStateIndependentOfForce() {
            assertFalse(service.isBootstrapConsumed());
            service.markBootstrapConsumed();
            assertTrue(service.isBootstrapConsumed());
        }
    }

    @Nested
    @DisplayName("Startup check — onStartup()")
    class StartupCheck {

        @Test
        @DisplayName("no bootstrap key configured — startup is a no-op")
        void noKeyConfigured() {
            givenNoBootstrapKeyConfigured();
            assertDoesNotThrow(() -> invokeOnStartup(service));
            assertFalse(service.isBootstrapConsumed());
        }

        @Test
        @DisplayName("no tenants, no API keys — bootstrap key stays active")
        void noTenantsKeepsActive() throws Exception {
            givenBootstrapKeyConfigured();
            when(coreQueryService.listTenants()).thenReturn(
                Collections.emptyList()
            );

            invokeOnStartup(service);

            assertFalse(service.isBootstrapConsumed());
            assertTrue(service.isBootstrapKeyActive());
        }

        @Test
        @DisplayName(
            "tenants exist but no API keys — bootstrap key stays active"
        )
        void tenantsNoKeysStaysActive() throws Exception {
            givenBootstrapKeyConfigured();
            TenantInfo tenant = tenantInfo("acme");
            when(coreQueryService.listTenants()).thenReturn(
                List.of(tenant)
            );
            when(iamQueryService.listApiKeys("acme")).thenReturn(
                Collections.emptyList()
            );

            invokeOnStartup(service);

            assertFalse(service.isBootstrapConsumed());
            assertTrue(service.isBootstrapKeyActive());
        }

        @Test
        @DisplayName(
            "tenants with only revoked API keys — bootstrap key stays active"
        )
        void onlyRevokedKeysStaysActive() throws Exception {
            givenBootstrapKeyConfigured();
            TenantInfo tenant = tenantInfo("acme");
            when(coreQueryService.listTenants()).thenReturn(
                List.of(tenant)
            );
            when(iamQueryService.listApiKeys("acme")).thenReturn(
                List.of(revokedApiKey("acme"))
            );

            invokeOnStartup(service);

            assertFalse(service.isBootstrapConsumed());
            assertTrue(service.isBootstrapKeyActive());
        }

        @Test
        @DisplayName(
            "tenants with active API key — bootstrap key auto-disabled"
        )
        void activeKeyAutoDisables() throws Exception {
            givenBootstrapKeyConfigured();
            TenantInfo tenant = tenantInfo("acme");
            when(coreQueryService.listTenants()).thenReturn(
                List.of(tenant)
            );
            when(iamQueryService.listApiKeys("acme")).thenReturn(
                List.of(activeApiKey("acme"))
            );

            invokeOnStartup(service);

            assertTrue(service.isBootstrapConsumed());
            assertFalse(service.isBootstrapKeyActive());
        }

        @Test
        @DisplayName(
            "active key in second tenant — auto-disables (short-circuit)"
        )
        void activeKeyInSecondTenant() throws Exception {
            givenBootstrapKeyConfigured();
            TenantInfo t1 = tenantInfo("tenant-1");
            TenantInfo t2 = tenantInfo("tenant-2");
            when(coreQueryService.listTenants()).thenReturn(
                List.of(t1, t2)
            );
            when(iamQueryService.listApiKeys("tenant-1")).thenReturn(
                Collections.emptyList()
            );
            when(iamQueryService.listApiKeys("tenant-2")).thenReturn(
                List.of(activeApiKey("tenant-2"))
            );

            invokeOnStartup(service);

            assertTrue(service.isBootstrapConsumed());
        }

        @Test
        @DisplayName(
            "platform admin throws exception — bootstrap key stays in default state"
        )
        void exceptionDuringCheckKeepsDefault() {
            givenBootstrapKeyConfigured();
            when(coreQueryService.listTenants()).thenThrow(
                new RuntimeException("DB connection failed")
            );

            assertDoesNotThrow(() -> invokeOnStartup(service));
            assertFalse(service.isBootstrapConsumed());
            assertTrue(service.isBootstrapKeyActive());
        }

        @Test
        @DisplayName(
            "null query services — startup skips check gracefully"
        )
        @SuppressWarnings("unchecked")
        void nullQueryServices() throws Exception {
            givenBootstrapKeyConfigured();

            // Simulate unresolvable instances (embedded mode)
            Instance<CoreQueryService> nullCqs = mock(Instance.class);
            Instance<IamQueryService> nullIqs = mock(Instance.class);
            lenient().when(nullCqs.isResolvable()).thenReturn(false);
            lenient().when(nullIqs.isResolvable()).thenReturn(false);
            injectField(service, "coreQueryServiceInstance", nullCqs);
            injectField(service, "iamQueryServiceInstance", nullIqs);

            assertDoesNotThrow(() -> invokeOnStartup(service));
            assertFalse(service.isBootstrapConsumed());
        }

        @Test
        @DisplayName("listTenants returns null — treated as no tenants")
        void nullTenantsList() throws Exception {
            givenBootstrapKeyConfigured();
            when(coreQueryService.listTenants()).thenReturn(null);

            invokeOnStartup(service);

            assertFalse(service.isBootstrapConsumed());
            assertTrue(service.isBootstrapKeyActive());
        }

        @Test
        @DisplayName(
            "listApiKeys returns null — treated as no keys for that tenant"
        )
        void nullApiKeysList() throws Exception {
            givenBootstrapKeyConfigured();
            TenantInfo tenant = tenantInfo("acme");
            when(coreQueryService.listTenants()).thenReturn(
                List.of(tenant)
            );
            when(iamQueryService.listApiKeys("acme")).thenReturn(null);

            invokeOnStartup(service);

            assertFalse(service.isBootstrapConsumed());
            assertTrue(service.isBootstrapKeyActive());
        }

        @Test
        @DisplayName(
            "force override with existing keys — consumed but still active"
        )
        void forceOverrideWithExistingKeys() throws Exception {
            givenForceBootstrapEnabled();
            TenantInfo tenant = tenantInfo("acme");
            when(coreQueryService.listTenants()).thenReturn(
                List.of(tenant)
            );
            when(iamQueryService.listApiKeys("acme")).thenReturn(
                List.of(activeApiKey("acme"))
            );

            invokeOnStartup(service);

            assertTrue(
                service.isBootstrapConsumed(),
                "Should be marked consumed even with force override"
            );
            assertTrue(
                service.isBootstrapKeyActive(),
                "Force override should keep bootstrap key active"
            );
        }
    }

    @Nested
    @DisplayName("Token matching edge cases")
    class TokenMatching {

        @BeforeEach
        void setup() {
            givenBootstrapKeyConfigured();
        }

        @Test
        @DisplayName("token with extra whitespace does not match")
        void extraWhitespaceNoMatch() {
            assertFalse(service.isActiveBootstrapKey(" " + BOOTSTRAP_KEY));
            assertFalse(service.isActiveBootstrapKey(BOOTSTRAP_KEY + " "));
        }

        @Test
        @DisplayName("token must be exact match (case-sensitive)")
        void caseSensitive() {
            assertFalse(
                service.isActiveBootstrapKey(BOOTSTRAP_KEY.toUpperCase())
            );
        }

        @Test
        @DisplayName("empty string token returns false")
        void emptyStringToken() {
            assertFalse(service.isActiveBootstrapKey(""));
            assertFalse(service.isBootstrapKeyValue(""));
        }

        @Test
        @DisplayName("token with only whitespace returns false")
        void whitespaceOnlyToken() {
            assertFalse(service.isActiveBootstrapKey("   \t\n"));
            assertFalse(service.isBootstrapKeyValue("   \t\n"));
        }

        @Test
        @DisplayName("partial key prefix does not match")
        void partialKeyNoMatch() {
            String partial = BOOTSTRAP_KEY.substring(0, 10);
            assertFalse(service.isActiveBootstrapKey(partial));
            assertFalse(service.isBootstrapKeyValue(partial));
        }
    }

    @Nested
    @DisplayName("Consumed state transitions")
    class ConsumedStateTransitions {

        @BeforeEach
        void setup() {
            givenBootstrapKeyConfigured();
        }

        @Test
        @DisplayName("fresh → consumed: single transition")
        void freshToConsumed() {
            assertFalse(service.isBootstrapConsumed());
            assertTrue(service.isBootstrapKeyActive());

            service.markBootstrapConsumed();

            assertTrue(service.isBootstrapConsumed());
            assertFalse(service.isBootstrapKeyActive());
        }

        @Test
        @DisplayName("consumed → consumed: idempotent")
        void consumedToConsumed() throws Exception {
            setBootstrapConsumed(service, true);

            service.markBootstrapConsumed();

            assertTrue(service.isBootstrapConsumed());
            assertFalse(service.isBootstrapKeyActive());
        }

        @Test
        @DisplayName(
            "startup auto-disable → markBootstrapConsumed: no double-log issue"
        )
        void startupThenExplicitMark() throws Exception {
            givenBootstrapKeyConfigured();
            TenantInfo tenant = tenantInfo("acme");
            when(coreQueryService.listTenants()).thenReturn(
                List.of(tenant)
            );
            when(iamQueryService.listApiKeys("acme")).thenReturn(
                List.of(activeApiKey("acme"))
            );

            invokeOnStartup(service);
            assertTrue(service.isBootstrapConsumed());

            // Subsequent explicit call should be safe
            assertDoesNotThrow(() -> service.markBootstrapConsumed());
            assertTrue(service.isBootstrapConsumed());
        }
    }

    @Nested
    @DisplayName("API key status matching")
    class ApiKeyStatusMatching {

        @Test
        @DisplayName("ACTIVE status (uppercase) triggers auto-disable")
        void activeUppercase() throws Exception {
            givenBootstrapKeyConfigured();
            TenantInfo tenant = tenantInfo("acme");
            when(coreQueryService.listTenants()).thenReturn(
                List.of(tenant)
            );

            IamQueryService.ApiKeyInfo key = new IamQueryService.ApiKeyInfo(
                "key-id",
                "pfx",
                "key-name",
                "acme",
                "production",
                "admin",
                "admin",
                "ACTIVE",
                Instant.now(),
                null
            );
            when(iamQueryService.listApiKeys("acme")).thenReturn(
                List.of(key)
            );

            invokeOnStartup(service);
            assertTrue(service.isBootstrapConsumed());
        }

        @Test
        @DisplayName(
            "active status (lowercase) triggers auto-disable (case-insensitive)"
        )
        void activeLowercase() throws Exception {
            givenBootstrapKeyConfigured();
            TenantInfo tenant = tenantInfo("acme");
            when(coreQueryService.listTenants()).thenReturn(
                List.of(tenant)
            );

            IamQueryService.ApiKeyInfo key = new IamQueryService.ApiKeyInfo(
                "key-id",
                "pfx",
                "key-name",
                "acme",
                "production",
                "admin",
                "admin",
                "active",
                Instant.now(),
                null
            );
            when(iamQueryService.listApiKeys("acme")).thenReturn(
                List.of(key)
            );

            invokeOnStartup(service);
            assertTrue(service.isBootstrapConsumed());
        }

        @Test
        @DisplayName("REVOKED status does not trigger auto-disable")
        void revokedDoesNotTrigger() throws Exception {
            givenBootstrapKeyConfigured();
            TenantInfo tenant = tenantInfo("acme");
            when(coreQueryService.listTenants()).thenReturn(
                List.of(tenant)
            );
            when(iamQueryService.listApiKeys("acme")).thenReturn(
                List.of(revokedApiKey("acme"))
            );

            invokeOnStartup(service);
            assertFalse(service.isBootstrapConsumed());
        }

        @Test
        @DisplayName("SUSPENDED status does not trigger auto-disable")
        void suspendedDoesNotTrigger() throws Exception {
            givenBootstrapKeyConfigured();
            TenantInfo tenant = tenantInfo("acme");
            when(coreQueryService.listTenants()).thenReturn(
                List.of(tenant)
            );

            IamQueryService.ApiKeyInfo key = new IamQueryService.ApiKeyInfo(
                "key-id",
                "pfx",
                "key-name",
                "acme",
                "production",
                "admin",
                "admin",
                "SUSPENDED",
                Instant.now(),
                null
            );
            when(iamQueryService.listApiKeys("acme")).thenReturn(
                List.of(key)
            );

            invokeOnStartup(service);
            assertFalse(service.isBootstrapConsumed());
        }

        @Test
        @DisplayName("mix of revoked and active — active triggers auto-disable")
        void mixedStatuses() throws Exception {
            givenBootstrapKeyConfigured();
            TenantInfo tenant = tenantInfo("acme");
            when(coreQueryService.listTenants()).thenReturn(
                List.of(tenant)
            );
            when(iamQueryService.listApiKeys("acme")).thenReturn(
                List.of(revokedApiKey("acme"), activeApiKey("acme"))
            );

            invokeOnStartup(service);
            assertTrue(service.isBootstrapConsumed());
        }
    }

    @Nested
    @DisplayName("Thread safety considerations")
    class ThreadSafety {

        @Test
        @DisplayName("concurrent markBootstrapConsumed() calls are safe")
        void concurrentConsumption() throws Exception {
            givenBootstrapKeyConfigured();

            // Run multiple threads calling markBootstrapConsumed concurrently
            Thread[] threads = new Thread[10];
            for (int i = 0; i < threads.length; i++) {
                threads[i] = Thread.ofVirtual().unstarted(() -> {
                    service.markBootstrapConsumed();
                });
            }
            for (Thread t : threads) t.start();
            for (Thread t : threads) t.join(5000);

            assertTrue(service.isBootstrapConsumed());
            assertFalse(service.isBootstrapKeyActive());
        }

        @Test
        @DisplayName("read during concurrent consume sees consistent state")
        void readDuringConsume() throws Exception {
            givenBootstrapKeyConfigured();

            // After markBootstrapConsumed, all subsequent reads must see consumed=true
            service.markBootstrapConsumed();

            Thread[] readers = new Thread[10];
            boolean[] results = new boolean[10];
            for (int i = 0; i < readers.length; i++) {
                final int idx = i;
                readers[i] = Thread.ofVirtual().unstarted(() -> {
                    results[idx] = service.isBootstrapConsumed();
                });
            }
            for (Thread t : readers) t.start();
            for (Thread t : readers) t.join(5000);

            for (int i = 0; i < results.length; i++) {
                assertTrue(
                    results[i],
                    "Reader " + i + " should see consumed=true"
                );
            }
        }
    }

    @Nested
    @DisplayName("Integration scenario: full lifecycle")
    class FullLifecycle {

        @Test
        @DisplayName(
            "fresh deploy → bootstrap active → create key → auto-disabled → force re-enable"
        )
        void fullLifecycleScenario() throws Exception {
            // Phase 1: Fresh deployment with bootstrap key configured
            givenBootstrapKeyConfigured();
            when(coreQueryService.listTenants()).thenReturn(
                Collections.emptyList()
            );

            invokeOnStartup(service);

            assertTrue(service.isBootstrapKeyActive(), "Phase 1: active");
            assertTrue(
                service.isActiveBootstrapKey(BOOTSTRAP_KEY),
                "Phase 1: token match"
            );
            assertFalse(service.isBootstrapConsumed(), "Phase 1: not consumed");

            // Phase 2: After first API key is created
            service.markBootstrapConsumed();

            assertFalse(service.isBootstrapKeyActive(), "Phase 2: inactive");
            assertFalse(
                service.isActiveBootstrapKey(BOOTSTRAP_KEY),
                "Phase 2: token rejected"
            );
            assertTrue(service.isBootstrapConsumed(), "Phase 2: consumed");
            // But the value still matches (for 403 vs 401 differentiation)
            assertTrue(
                service.isBootstrapKeyValue(BOOTSTRAP_KEY),
                "Phase 2: value still matches"
            );

            // Phase 3: Force override re-enables (simulated by swapping config)
            givenForceBootstrapEnabled();

            assertTrue(service.isBootstrapKeyActive(), "Phase 3: force-active");
            assertTrue(
                service.isActiveBootstrapKey(BOOTSTRAP_KEY),
                "Phase 3: token accepted"
            );
            assertTrue(
                service.isBootstrapConsumed(),
                "Phase 3: still consumed"
            );
        }
    }
}
