package com.koostack.cveil.rest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.koostack.cveil.rest.config.CveilConfig;
import com.koostack.cveil.rest.lifecycle.BootstrapKeyLifecycleService;
import java.lang.reflect.Field;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link BootstrapKeyLifecycleService} — the service that checks whether a
 * configured bootstrap super-admin key is active and whether a given token matches it.
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>No bootstrap key configured — all queries return inactive/false
 *   <li>Blank bootstrap key — treated as not configured
 *   <li>Active bootstrap key — isBootstrapKeyActive, isActiveBootstrapKey, isBootstrapKeyValue
 *   <li>Token matching edge cases — null, blank, partial, case-sensitive, extra whitespace
 * </ul>
 */
@DisplayName("BootstrapKeyLifecycleService")
@ExtendWith(MockitoExtension.class)
class BootstrapKeyLifecycleServiceTest {

    private static final String BOOTSTRAP_KEY = "super-secret-bootstrap-key-12345";

    @Mock private CveilConfig config;

    @Mock private CveilConfig.SuperAdminConfig superAdminConfig;

    private BootstrapKeyLifecycleService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new BootstrapKeyLifecycleService();
        injectField(service, "config", config);
    }

    // -----------------------------------------------------------------------
    // Reflection helpers
    // -----------------------------------------------------------------------

    private static void injectField(Object target, String fieldName, Object value)
            throws Exception {
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
        throw new RuntimeException("Field '" + name + "' not found in " + clazz);
    }

    // -----------------------------------------------------------------------
    // Config stub helpers
    // -----------------------------------------------------------------------

    private void givenBootstrapKeyConfigured() {
        lenient().when(superAdminConfig.bootstrapKey()).thenReturn(Optional.of(BOOTSTRAP_KEY));
        lenient().when(config.superAdmin()).thenReturn(Optional.of(superAdminConfig));
    }

    private void givenNoBootstrapKeyConfigured() {
        lenient().when(config.superAdmin()).thenReturn(Optional.empty());
    }

    private void givenBlankBootstrapKeyConfigured() {
        lenient().when(superAdminConfig.bootstrapKey()).thenReturn(Optional.of("   "));
        lenient().when(config.superAdmin()).thenReturn(Optional.of(superAdminConfig));
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
            when(config.superAdmin()).thenReturn(Optional.of(superAdminConfig));
        }

        @Test
        @DisplayName("isBootstrapKeyActive() returns false when key is Optional.empty")
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
    @DisplayName("Active bootstrap key")
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
            assertFalse(service.isActiveBootstrapKey(BOOTSTRAP_KEY.toUpperCase()));
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
}
