package com.koostack.cveil.core;

import static org.junit.jupiter.api.Assertions.*;

import com.koostack.cveil.core.models.IdentityContext;
import com.koostack.cveil.core.platform.iam.TokenValidationService;
import com.koostack.cveil.core.spi.Plugin;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for the core SPI interface: {@link TokenValidationService}.
 *
 * <p>These tests verify the structural contracts of the SPI interface:
 *
 * <ul>
 *   <li>It is an interface (not an abstract class)
 *   <li>It extends {@link Plugin}
 *   <li>{@code verifyToken(String)} on {@code TokenValidationService} is abstract (no default)
 *   <li>Stub implementations can be created and wired into {@code Cveil}
 *   <li>The {@code Plugin} super-interface provides sensible defaults for {@code isReady()}, {@code
 *       name()}, {@code priority()}, and {@code configure()}
 * </ul>
 *
 * <p>These tests do NOT verify runtime behaviour of concrete implementations (that belongs in Phase
 * 7.3 / 7.5). They verify the <em>shape</em> of the SPI so that implementors know what they must
 * provide.
 */
@DisplayName("SPI contract tests")
class SpiContractTest {

    // -----------------------------------------------------------------------
    // TokenValidationService
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("TokenValidationService contract")
    class TokenValidationServiceContract {

        @Test
        @DisplayName("TokenValidationService is an interface")
        void isInterface() {
            assertTrue(
                    TokenValidationService.class.isInterface(),
                    "TokenValidationService must be an interface, not an abstract class");
        }

        @Test
        @DisplayName("TokenValidationService extends Plugin")
        void extendsPlugin() {
            assertTrue(
                    Plugin.class.isAssignableFrom(TokenValidationService.class),
                    "TokenValidationService must extend Plugin");
        }

        @Test
        @DisplayName("verifyToken(String) is declared on TokenValidationService")
        void verifyTokenDeclared() throws NoSuchMethodException {
            Method method = TokenValidationService.class.getMethod("verifyToken", String.class);
            assertNotNull(method);
            assertEquals(
                    IdentityContext.class,
                    method.getReturnType(),
                    "verifyToken must return IdentityContext");
        }

        @Test
        @DisplayName("verifyToken(String) is abstract (no default implementation)")
        void verifyTokenIsAbstract() throws NoSuchMethodException {
            Method method = TokenValidationService.class.getMethod("verifyToken", String.class);
            assertFalse(
                    method.isDefault(),
                    "verifyToken must be abstract — implementations MUST provide verification"
                            + " logic");
        }

        @Test
        @DisplayName("verifyToken takes exactly one String parameter")
        void verifyTokenParameterTypes() throws NoSuchMethodException {
            Method method = TokenValidationService.class.getMethod("verifyToken", String.class);
            Class<?>[] params = method.getParameterTypes();
            assertEquals(1, params.length);
            assertEquals(String.class, params[0]);
        }

        @Test
        @DisplayName("Plugin defaults are inherited: isReady() returns true")
        void isReadyDefault() {
            TokenValidationService<?> stub = new StubTokenVerifier();
            assertTrue(stub.isReady(), "Default isReady() from Plugin should return true");
        }

        @Test
        @DisplayName("stub implementation can be instantiated and called")
        void stubCanBeInstantiated() {
            StubTokenVerifier stub = new StubTokenVerifier();
            IdentityContext ctx = stub.verifyToken("test-token");

            assertNotNull(ctx);
            assertEquals("stub-subject", ctx.subject());
            assertEquals("test-token", ctx.token());
        }

        @Test
        @DisplayName("stub can be wired into Cveil via builder")
        void stubCanBeWiredIntoCveil() {
            StubTokenVerifier stub = new StubTokenVerifier();
            Cveil cveil = Cveil.builder().withTokenValidationService(stub).build();

            assertNotNull(cveil.tokenValidationService());
            assertSame(stub, cveil.tokenValidationService());
        }
    }

    // -----------------------------------------------------------------------
    // Plugin super-interface defaults
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Plugin interface defaults")
    class PluginDefaults {

        @Test
        @DisplayName("isReady() has a default implementation returning true")
        void isReadyHasDefault() throws NoSuchMethodException {
            Method method = Plugin.class.getMethod("isReady");
            assertTrue(method.isDefault(), "isReady() should be a default method on Plugin");
        }

        @Test
        @DisplayName("isReady() default returns true (optimistic local-resource assumption)")
        void isReadyDefaultReturnsTrue() {
            // Stub inherits the default
            assertTrue(new StubTokenVerifier().isReady());
        }
    }

    // -----------------------------------------------------------------------
    // Cross-wiring: token verifier in Cveil
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Cross-wiring in Cveil")
    class CrossWiring {

        @Test
        @DisplayName("TokenValidationService can be wired into Cveil")
        void tokenVerifierCanBeWired() {
            StubTokenVerifier tv = new StubTokenVerifier();

            Cveil cveil = Cveil.builder().withTokenValidationService(tv).build();

            assertSame(tv, cveil.tokenValidationService());
        }

        @Test
        @DisplayName("Cveil can be built without TokenValidationService (embedded mode)")
        void canBuildWithoutPlatformPlugins() {
            Cveil cveil = Cveil.builder().build();

            assertNull(
                    cveil.tokenValidationService(),
                    "TokenValidationService should be null in embedded mode (no platform plugins)");
        }
    }

    // =======================================================================
    // Stub implementations (minimal, for contract verification only)
    // =======================================================================

    /**
     * Minimal {@link TokenValidationService} implementation for contract testing. Returns a fixed
     * {@link IdentityContext} for any token.
     */
    private static class StubTokenVerifier implements TokenValidationService<Void> {

        @Override
        public IdentityContext verifyToken(String token) {
            return IdentityContext.of("stub-subject", token);
        }

        @Override
        public String getName() {
            return "stub-token-verifier";
        }

        @Override
        public Class<Void> getConfigurationType() {
            return Void.class;
        }

        @Override
        public void configure(Void config) {
            // no-op
        }
    }
}
