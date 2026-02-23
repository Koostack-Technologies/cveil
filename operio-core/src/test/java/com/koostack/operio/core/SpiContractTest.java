package com.koostack.operio.core;

import static org.junit.jupiter.api.Assertions.*;

import com.koostack.operio.core.Models.ApiKeyInfo;
import com.koostack.operio.core.Models.IdentityContext;
import com.koostack.operio.core.Models.IdpConfigInfo;
import com.koostack.operio.core.Models.TenantInfo;
import com.koostack.operio.core.service.PlatformAdminService;
import com.koostack.operio.core.service.TokenVerifier;
import com.koostack.operio.core.spi.Plugin;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for the core SPI interfaces: {@link TokenVerifier} and
 * {@link PlatformAdminService}.
 *
 * <p>These tests verify the structural contracts of the SPI interfaces:
 * <ul>
 *   <li>Both are interfaces (not abstract classes)</li>
 *   <li>Both extend {@link Plugin}</li>
 *   <li>{@code verifyToken(String)} on {@code TokenVerifier} is abstract (no default)</li>
 *   <li>All domain methods on {@code PlatformAdminService} are abstract (no defaults)</li>
 *   <li>Stub implementations can be created and wired into {@code Operio}</li>
 *   <li>The {@code Plugin} super-interface provides sensible defaults for
 *       {@code isReady()}, {@code name()}, {@code priority()}, and
 *       {@code configure()}</li>
 * </ul>
 *
 * <p>These tests do NOT verify runtime behaviour of concrete implementations
 * (that belongs in Phase 7.3 / 7.5). They verify the <em>shape</em> of the
 * SPI so that implementors know what they must provide.
 */
@DisplayName("SPI contract tests")
class SpiContractTest {

    // -----------------------------------------------------------------------
    // TokenVerifier
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("TokenVerifier contract")
    class TokenVerifierContract {

        @Test
        @DisplayName("TokenVerifier is an interface")
        void isInterface() {
            assertTrue(
                TokenVerifier.class.isInterface(),
                "TokenVerifier must be an interface, not an abstract class"
            );
        }

        @Test
        @DisplayName("TokenVerifier extends Plugin")
        void extendsPlugin() {
            assertTrue(
                Plugin.class.isAssignableFrom(TokenVerifier.class),
                "TokenVerifier must extend Plugin"
            );
        }

        @Test
        @DisplayName("verifyToken(String) is declared on TokenVerifier")
        void verifyTokenDeclared() throws NoSuchMethodException {
            Method method = TokenVerifier.class.getMethod(
                "verifyToken",
                String.class
            );
            assertNotNull(method);
            assertEquals(
                IdentityContext.class,
                method.getReturnType(),
                "verifyToken must return IdentityContext"
            );
        }

        @Test
        @DisplayName(
            "verifyToken(String) is abstract (no default implementation)"
        )
        void verifyTokenIsAbstract() throws NoSuchMethodException {
            Method method = TokenVerifier.class.getMethod(
                "verifyToken",
                String.class
            );
            assertFalse(
                method.isDefault(),
                "verifyToken must be abstract — implementations MUST provide verification logic"
            );
        }

        @Test
        @DisplayName("verifyToken takes exactly one String parameter")
        void verifyTokenParameterTypes() throws NoSuchMethodException {
            Method method = TokenVerifier.class.getMethod(
                "verifyToken",
                String.class
            );
            Class<?>[] params = method.getParameterTypes();
            assertEquals(1, params.length);
            assertEquals(String.class, params[0]);
        }

        @Test
        @DisplayName("Plugin defaults are inherited: isReady() returns true")
        void isReadyDefault() {
            TokenVerifier<?> stub = new StubTokenVerifier();
            assertTrue(
                stub.isReady(),
                "Default isReady() from Plugin should return true"
            );
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
        @DisplayName("stub can be wired into Operio via builder")
        void stubCanBeWiredIntoOperio() {
            StubTokenVerifier stub = new StubTokenVerifier();
            Operio operio = Operio.builder().withTokenVerifier(stub).build();

            assertNotNull(operio.tokenVerifier());
            assertSame(stub, operio.tokenVerifier());
        }
    }

    // -----------------------------------------------------------------------
    // PlatformAdminService
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("PlatformAdminService contract")
    class PlatformAdminServiceContract {

        @Test
        @DisplayName("PlatformAdminService is an interface")
        void isInterface() {
            assertTrue(
                PlatformAdminService.class.isInterface(),
                "PlatformAdminService must be an interface, not an abstract class"
            );
        }

        @Test
        @DisplayName("PlatformAdminService extends Plugin")
        void extendsPlugin() {
            assertTrue(
                Plugin.class.isAssignableFrom(PlatformAdminService.class),
                "PlatformAdminService must extend Plugin"
            );
        }

        // -- Tenant lifecycle methods --

        @Test
        @DisplayName("createTenant(String, String, String) is abstract")
        void createTenantIsAbstract() throws NoSuchMethodException {
            Method method = PlatformAdminService.class.getMethod(
                "createTenant",
                String.class,
                String.class,
                String.class
            );
            assertFalse(method.isDefault(), "createTenant must be abstract");
            assertEquals(void.class, method.getReturnType());
        }

        @Test
        @DisplayName("deleteTenant(String) is abstract")
        void deleteTenantIsAbstract() throws NoSuchMethodException {
            Method method = PlatformAdminService.class.getMethod(
                "deleteTenant",
                String.class
            );
            assertFalse(method.isDefault(), "deleteTenant must be abstract");
            assertEquals(void.class, method.getReturnType());
        }

        @Test
        @DisplayName("getTenant(String) is abstract and returns TenantInfo")
        void getTenantIsAbstract() throws NoSuchMethodException {
            Method method = PlatformAdminService.class.getMethod(
                "getTenant",
                String.class
            );
            assertFalse(method.isDefault(), "getTenant must be abstract");
            assertEquals(TenantInfo.class, method.getReturnType());
        }

        @Test
        @DisplayName("listTenants() is abstract and returns List")
        void listTenantsIsAbstract() throws NoSuchMethodException {
            Method method = PlatformAdminService.class.getMethod("listTenants");
            assertFalse(method.isDefault(), "listTenants must be abstract");
            assertEquals(List.class, method.getReturnType());
        }

        // -- API key management methods --

        @Test
        @DisplayName(
            "createApiKey(String, String, Set) is abstract and returns String"
        )
        void createApiKeyIsAbstract() throws NoSuchMethodException {
            Method method = PlatformAdminService.class.getMethod(
                "createApiKey",
                String.class,
                String.class,
                Set.class
            );
            assertFalse(method.isDefault(), "createApiKey must be abstract");
            assertEquals(String.class, method.getReturnType());
        }

        @Test
        @DisplayName("revokeApiKey(String) is abstract")
        void revokeApiKeyIsAbstract() throws NoSuchMethodException {
            Method method = PlatformAdminService.class.getMethod(
                "revokeApiKey",
                String.class
            );
            assertFalse(method.isDefault(), "revokeApiKey must be abstract");
            assertEquals(void.class, method.getReturnType());
        }

        @Test
        @DisplayName("listApiKeys(String) is abstract and returns List")
        void listApiKeysIsAbstract() throws NoSuchMethodException {
            Method method = PlatformAdminService.class.getMethod(
                "listApiKeys",
                String.class
            );
            assertFalse(method.isDefault(), "listApiKeys must be abstract");
            assertEquals(List.class, method.getReturnType());
        }

        // -- IdP configuration methods --

        @Test
        @DisplayName("configureIdp(String, String, Map) is abstract")
        void configureIdpIsAbstract() throws NoSuchMethodException {
            Method method = PlatformAdminService.class.getMethod(
                "configureIdp",
                String.class,
                String.class,
                Map.class
            );
            assertFalse(method.isDefault(), "configureIdp must be abstract");
            assertEquals(void.class, method.getReturnType());
        }

        @Test
        @DisplayName(
            "getIdpConfig(String, String) is abstract and returns IdpConfigInfo"
        )
        void getIdpConfigIsAbstract() throws NoSuchMethodException {
            Method method = PlatformAdminService.class.getMethod(
                "getIdpConfig",
                String.class,
                String.class
            );
            assertFalse(method.isDefault(), "getIdpConfig must be abstract");
            assertEquals(IdpConfigInfo.class, method.getReturnType());
        }

        @Test
        @DisplayName("listIdpConfigs(String) is abstract and returns List")
        void listIdpConfigsIsAbstract() throws NoSuchMethodException {
            Method method = PlatformAdminService.class.getMethod(
                "listIdpConfigs",
                String.class
            );
            assertFalse(method.isDefault(), "listIdpConfigs must be abstract");
            assertEquals(List.class, method.getReturnType());
        }

        // -- Exhaustive check: all declared methods are abstract --

        @Test
        @DisplayName(
            "ALL methods declared directly on PlatformAdminService are abstract (no defaults)"
        )
        void allDeclaredMethodsAreAbstract() {
            Method[] declaredMethods =
                PlatformAdminService.class.getDeclaredMethods();

            assertTrue(
                declaredMethods.length > 0,
                "PlatformAdminService should declare at least one method"
            );

            for (Method method : declaredMethods) {
                assertFalse(
                    method.isDefault(),
                    "Method " +
                        method.getName() +
                        " must be abstract — " +
                        "PlatformAdminService should not provide default implementations " +
                        "for its domain methods"
                );
            }
        }

        @Test
        @DisplayName("PlatformAdminService declares exactly 10 domain methods")
        void declaresExactly10Methods() {
            Method[] declaredMethods =
                PlatformAdminService.class.getDeclaredMethods();
            assertEquals(
                10,
                declaredMethods.length,
                "PlatformAdminService should declare 10 methods: " +
                    "createTenant, deleteTenant, getTenant, listTenants, " +
                    "createApiKey, revokeApiKey, listApiKeys, " +
                    "configureIdp, getIdpConfig, listIdpConfigs. " +
                    "Found: " +
                    Arrays.toString(
                        Stream.of(declaredMethods)
                            .map(Method::getName)
                            .toArray()
                    )
            );
        }

        @Test
        @DisplayName("Plugin defaults are inherited: isReady() returns true")
        void isReadyDefault() {
            PlatformAdminService<?> stub = new StubPlatformAdminService();
            assertTrue(
                stub.isReady(),
                "Default isReady() from Plugin should return true"
            );
        }

        @Test
        @DisplayName(
            "stub implementation can be instantiated and wired into Operio"
        )
        void stubCanBeWiredIntoOperio() {
            StubPlatformAdminService stub = new StubPlatformAdminService();
            Operio operio = Operio.builder().withPlatformAdmin(stub).build();

            assertNotNull(operio.platformAdminService());
            assertSame(stub, operio.platformAdminService());
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
            assertTrue(
                method.isDefault(),
                "isReady() should be a default method on Plugin"
            );
        }

        @Test
        @DisplayName(
            "isReady() default returns true (optimistic local-resource assumption)"
        )
        void isReadyDefaultReturnsTrue() {
            // Both stubs inherit the default
            assertTrue(new StubTokenVerifier().isReady());
            assertTrue(new StubPlatformAdminService().isReady());
        }
    }

    // -----------------------------------------------------------------------
    // Cross-wiring: both can coexist in the same Operio instance
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Cross-wiring in Operio")
    class CrossWiring {

        @Test
        @DisplayName(
            "TokenVerifier and PlatformAdminService can coexist in Operio"
        )
        void bothCanCoexist() {
            StubTokenVerifier tv = new StubTokenVerifier();
            StubPlatformAdminService pas = new StubPlatformAdminService();

            Operio operio = Operio.builder()
                .withTokenVerifier(tv)
                .withPlatformAdmin(pas)
                .build();

            assertSame(tv, operio.tokenVerifier());
            assertSame(pas, operio.platformAdminService());
        }

        @Test
        @DisplayName(
            "Operio can be built without TokenVerifier or PlatformAdminService (embedded mode)"
        )
        void canBuildWithoutPlatformPlugins() {
            Operio operio = Operio.builder().build();

            assertNull(
                operio.tokenVerifier(),
                "TokenVerifier should be null in embedded mode (no platform plugins)"
            );
            assertNull(
                operio.platformAdminService(),
                "PlatformAdminService should be null in embedded mode (no platform plugins)"
            );
        }
    }

    // =======================================================================
    // Stub implementations (minimal, for contract verification only)
    // =======================================================================

    /**
     * Minimal {@link TokenVerifier} implementation for contract testing.
     * Returns a fixed {@link IdentityContext} for any token.
     */
    private static class StubTokenVerifier implements TokenVerifier<Void> {

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

    /**
     * Minimal {@link PlatformAdminService} implementation for contract testing.
     * All methods throw {@link UnsupportedOperationException} — they exist
     * only to prove the interface can be implemented.
     */
    private static class StubPlatformAdminService
        implements PlatformAdminService<Void>
    {

        @Override
        public void createTenant(
            String tenantId,
            String displayName,
            String plan
        ) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public void deleteTenant(String tenantId) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public TenantInfo getTenant(String tenantId) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public List<TenantInfo> listTenants() {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public String createApiKey(
            String tenantId,
            String subject,
            Set<String> roles
        ) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public void revokeApiKey(String keyPrefix) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public List<ApiKeyInfo> listApiKeys(String tenantId) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public void configureIdp(
            String tenantId,
            String provider,
            Map<String, Object> config
        ) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public IdpConfigInfo getIdpConfig(String tenantId, String provider) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public List<IdpConfigInfo> listIdpConfigs(String tenantId) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public String getName() {
            return "stub-platform-admin";
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
