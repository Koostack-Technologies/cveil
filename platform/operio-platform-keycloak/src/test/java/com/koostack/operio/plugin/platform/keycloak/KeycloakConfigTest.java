package com.koostack.operio.plugin.platform.keycloak;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("KeycloakConfig")
class KeycloakConfigTest {

    // -----------------------------------------------------------------------
    // Record validation
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Validation")
    class Validation {

        @Test
        @DisplayName("accepts valid configuration with all fields")
        void validConfigAllFields() {
            var config =
                    new KeycloakConfig(
                            "http://localhost:8180",
                            "master",
                            "admin-cli",
                            "admin",
                            "admin",
                            "operio-backend",
                            "secret");

            assertEquals("http://localhost:8180", config.serverUrl());
            assertEquals("master", config.adminRealm());
            assertEquals("admin-cli", config.adminClientId());
            assertEquals("admin", config.adminUsername());
            assertEquals("admin", config.adminPassword());
            assertEquals("operio-backend", config.operioClientId());
            assertEquals("secret", config.operioClientSecret());
        }

        @Test
        @DisplayName("allows null client secret (auto-generated during provisioning)")
        void allowsNullClientSecret() {
            var config =
                    new KeycloakConfig(
                            "http://localhost:8180",
                            "master",
                            "admin-cli",
                            "admin",
                            "admin",
                            "operio-backend",
                            null);

            assertNull(config.operioClientSecret());
        }

        @Test
        @DisplayName("rejects null server URL")
        void rejectsNullServerUrl() {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new KeycloakConfig(
                                    null,
                                    "master",
                                    "admin-cli",
                                    "admin",
                                    "admin",
                                    "operio-backend",
                                    null));
        }

        @Test
        @DisplayName("rejects blank server URL")
        void rejectsBlankServerUrl() {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new KeycloakConfig(
                                    "  ",
                                    "master",
                                    "admin-cli",
                                    "admin",
                                    "admin",
                                    "operio-backend",
                                    null));
        }

        @Test
        @DisplayName("rejects empty server URL")
        void rejectsEmptyServerUrl() {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new KeycloakConfig(
                                    "",
                                    "master",
                                    "admin-cli",
                                    "admin",
                                    "admin",
                                    "operio-backend",
                                    null));
        }
    }

    // -----------------------------------------------------------------------
    // Factory method
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Factory method of()")
    class FactoryMethod {

        @Test
        @DisplayName("uses sensible defaults for admin realm, client, and operio client")
        void defaults() {
            var config = KeycloakConfig.of("http://kc:8180", "admin", "pass");

            assertEquals("http://kc:8180", config.serverUrl());
            assertEquals("master", config.adminRealm());
            assertEquals("admin-cli", config.adminClientId());
            assertEquals("admin", config.adminUsername());
            assertEquals("pass", config.adminPassword());
            assertEquals("operio-backend", config.operioClientId());
            assertNull(config.operioClientSecret());
        }
    }

    // -----------------------------------------------------------------------
    // Record contract (equals, hashCode, toString)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Record contract")
    class RecordContract {

        @Test
        @DisplayName("equal configs are equal")
        void equality() {
            var a = KeycloakConfig.of("http://kc:8180", "admin", "pass");
            var b = KeycloakConfig.of("http://kc:8180", "admin", "pass");

            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("different configs are not equal")
        void inequality() {
            var a = KeycloakConfig.of("http://kc:8180", "admin", "pass");
            var b = KeycloakConfig.of("http://kc:9090", "admin", "pass");

            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("toString contains server URL")
        void toStringContainsUrl() {
            var config = KeycloakConfig.of("http://kc:8180", "admin", "pass");

            assertTrue(config.toString().contains("http://kc:8180"));
        }
    }
}
