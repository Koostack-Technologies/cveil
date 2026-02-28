package com.koostack.operio.spring.config;

import com.koostack.operio.core.DefaultTenantService;
import com.koostack.operio.core.Operio;
import com.koostack.operio.core.sdk.IdentityResolver;
import com.koostack.operio.core.spi.PluginBundle;
import com.koostack.operio.core.tenant.consent.ConsentAdminCommandService;
import com.koostack.operio.core.tenant.consent.ConsentAdminQueryService;
import com.koostack.operio.spring.aspect.ConsentRequiredAspect;
import com.koostack.operio.spring.aspect.SensitiveAspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot auto-configuration for the Operio consent SDK.
 *
 * <p>This starter is a <strong>client SDK</strong> for developers embedding
 * Operio in their Spring Boot applications. It auto-wires an {@link Operio}
 * instance from Spring properties ({@code operio.*}) and provides AOP aspects
 * for annotation-driven consent enforcement ({@code @ConsentRequired},
 * {@code @Sensitive}).
 *
 * <p>The {@code operio.mode} property controls which consent bundle is used:
 * <ul>
 *   <li>{@code embedded} (default) — {@code EmbeddedConsentBundle}
 *       (H2 + Caffeine, zero external infra, ideal for dev/testing)</li>
 *   <li>{@code production} — {@code CoreConsentBundle}
 *       (Postgres + Redis, server-grade infrastructure)</li>
 * </ul>
 *
 * <p><strong>What is NOT in this starter:</strong> REST controllers, auth
 * filters, rate limiting, CORS, DTOs, exception handlers, platform admin
 * wiring, bootstrap key logic. All of that lives in the Quarkus REST API
 * module ({@code operio-rest-api}).
 */
@AutoConfiguration
@EnableConfigurationProperties(OperioProperties.class)
public class OperioAutoConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(
        OperioAutoConfiguration.class
    );

    // ------------------------------------------------------------------ Bundles

    /**
     * Embedded bundle (H2 + Caffeine) — activated when {@code operio.mode}
     * is {@code "embedded"} or absent (the default for development/testing).
     *
     * <p>Uses reflection to instantiate {@code EmbeddedConsentBundle} so that
     * the starter module only needs a {@code compileOnly} dependency on the
     * bundle. If the bundle is not on the runtime classpath the bean is
     * simply not created, and the user must provide their own
     * {@link PluginBundle} or {@link Operio} bean.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(
        name = "operio.mode",
        havingValue = "embedded",
        matchIfMissing = true
    )
    static class EmbeddedBundleConfiguration {

        @Bean
        @ConditionalOnMissingBean(PluginBundle.class)
        PluginBundle embeddedConsentBundle(OperioProperties props) {
            LOG.info("Operio SDK: configuring embedded bundle (H2 + Caffeine)");
            try {
                Class<?> bundleClass = Class.forName(
                    "com.koostack.operio.bundle.embedded.EmbeddedConsentBundle"
                );

                if (
                    props.getAppName() != null && !props.getAppName().isBlank()
                ) {
                    // EmbeddedConsentBundle(String appName, AnnotationDiscoveryConfig discoveryConfig)
                    // We use the (String) constructor via the two-arg form with null discovery
                    var ctor = bundleClass.getConstructor(String.class);
                    return (PluginBundle) ctor.newInstance(props.getAppName());
                }

                // Default no-arg constructor
                return (PluginBundle) bundleClass
                    .getConstructor()
                    .newInstance();
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(
                    "operio.mode=embedded but 'operio-bundle-embedded' is not on the classpath. " +
                        "Add a runtime dependency on operio-bundle-embedded, or switch to " +
                        "operio.mode=production with operio-bundle-core on the classpath.",
                    e
                );
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(
                    "Failed to instantiate EmbeddedConsentBundle",
                    e
                );
            }
        }
    }

    /**
     * Production bundle (Postgres + Redis) — activated when
     * {@code operio.mode=production}.
     *
     * <p>Uses reflection to instantiate {@code CoreConsentBundle} so that
     * the starter module only needs a {@code compileOnly} dependency on the
     * bundle. Requires {@code operio.db.url} and {@code operio.redis.host}
     * to be configured.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "operio.mode", havingValue = "production")
    static class ProductionBundleConfiguration {

        @Bean
        @ConditionalOnMissingBean(PluginBundle.class)
        PluginBundle coreConsentBundle(OperioProperties props) {
            LOG.info(
                "Operio SDK: configuring production bundle (Postgres + Redis)"
            );

            OperioProperties.DbProperties db = props.getDb();
            OperioProperties.RedisProperties redis = props.getRedis();

            if (db == null || db.getUrl() == null || db.getUrl().isBlank()) {
                throw new IllegalStateException(
                    "operio.mode=production requires operio.db.url to be set"
                );
            }
            if (
                redis == null ||
                redis.getHost() == null ||
                redis.getHost().isBlank()
            ) {
                throw new IllegalStateException(
                    "operio.mode=production requires operio.redis.host to be set"
                );
            }

            try {
                // Build JooqConsentAdminServiceConfig
                Class<?> jooqConfigClass = Class.forName(
                    "com.koostack.operio.plugin.consent.jooq.JooqConsentAdminServiceConfig"
                );
                Object jooqConfig = jooqConfigClass
                    .getConstructor(String.class, String.class)
                    .newInstance(db.getUrl(), db.getDialect());

                // Build RedisConsentDecisionServiceConfig
                Class<?> redisConfigClass = Class.forName(
                    "com.koostack.operio.plugin.consent.redis.RedisConsentDecisionServiceConfig"
                );
                Object redisConfig;
                if (
                    redis.getPassword() != null &&
                    !redis.getPassword().isBlank()
                ) {
                    redisConfig = redisConfigClass
                        .getConstructor(String.class, int.class, String.class)
                        .newInstance(
                            redis.getHost(),
                            redis.getPort(),
                            redis.getPassword()
                        );
                } else {
                    redisConfig = redisConfigClass
                        .getConstructor(String.class, int.class)
                        .newInstance(redis.getHost(), redis.getPort());
                }

                // Build CoreConsentBundle
                Class<?> bundleClass = Class.forName(
                    "com.koostack.operio.bundle.core.CoreConsentBundle"
                );

                if (
                    props.getAppName() != null && !props.getAppName().isBlank()
                ) {
                    // CoreConsentBundle(String, JooqConsentAdminServiceConfig, RedisConsentDecisionServiceConfig)
                    var ctor = bundleClass.getConstructor(
                        String.class,
                        jooqConfigClass,
                        redisConfigClass
                    );
                    return (PluginBundle) ctor.newInstance(
                        props.getAppName(),
                        jooqConfig,
                        redisConfig
                    );
                }

                // CoreConsentBundle(JooqConsentAdminServiceConfig, RedisConsentDecisionServiceConfig)
                var ctor = bundleClass.getConstructor(
                    jooqConfigClass,
                    redisConfigClass
                );
                return (PluginBundle) ctor.newInstance(jooqConfig, redisConfig);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(
                    "operio.mode=production but 'operio-bundle-core' is not on the classpath. " +
                        "Add a runtime dependency on operio-bundle-core, or switch to " +
                        "operio.mode=embedded with operio-bundle-embedded on the classpath.",
                    e
                );
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(
                    "Failed to instantiate CoreConsentBundle",
                    e
                );
            }
        }
    }

    // ------------------------------------------------------------------ Operio

    /**
     * The primary {@link Operio} bean — wired from the auto-configured (or
     * user-provided) {@link PluginBundle} and any {@link IdentityResolver}
     * found on the classpath.
     *
     * <p>If the user has already defined their own {@code Operio} bean this
     * one backs off via {@code @ConditionalOnMissingBean}.
     *
     * <p>Identity resolver auto-discovery: if exactly one
     * {@link IdentityResolver} bean exists in the context it is wired into
     * the builder. If none are found, Operio's built-in SPI ServiceLoader
     * discovery is used (e.g. {@code IdentitySpringSecurity} registered via
     * {@code META-INF/services}). If multiple are found, a warning is logged
     * and SPI discovery is used as a fallback.
     */
    @Bean
    @ConditionalOnMissingBean
    public Operio operio(
        ObjectProvider<PluginBundle> bundles,
        ObjectProvider<IdentityResolver<?>> identityResolvers,
        ObjectProvider<ConsentAdminCommandService<?>> commandServices,
        ObjectProvider<ConsentAdminQueryService<?>> queryServices,
        OperioProperties props
    ) {
        Operio.Builder builder = Operio.builder();

        // Apply app name if set (bundle may also set it; explicit property wins
        // because withAppName is called after withBundle)
        boolean hasAppName =
            props.getAppName() != null && !props.getAppName().isBlank();

        // Apply all available bundles (typically exactly one from the
        // conditional configurations above, but the user could provide extras)
        bundles
            .orderedStream()
            .forEach(bundle -> {
                LOG.debug("Operio SDK: applying bundle '{}'", bundle.name());
                builder.withBundle(bundle);
            });

        // Override app name after bundles so the property takes precedence
        if (hasAppName) {
            builder.withAppName(props.getAppName());
        }

        // Auto-wire identity resolver
        var resolverList = identityResolvers.orderedStream().toList();
        if (resolverList.size() == 1) {
            IdentityResolver<?> resolver = resolverList.getFirst();
            LOG.info(
                "Operio SDK: using IdentityResolver bean '{}'",
                resolver.getClass().getSimpleName()
            );
            builder.withIdentityProvider(resolver.getName());
        } else if (resolverList.isEmpty()) {
            LOG.debug(
                "Operio SDK: no IdentityResolver bean found — " +
                    "falling back to SPI ServiceLoader discovery"
            );
            // Operio.Builder will discover via ServiceLoader if nothing is set
        } else {
            LOG.warn(
                "Operio SDK: found {} IdentityResolver beans — " +
                    "falling back to SPI ServiceLoader discovery. " +
                    "Define a single IdentityResolver bean or configure one explicitly.",
                resolverList.size()
            );
        }

        // Resolve command + query services (from Spring beans or SPI ServiceLoader)
        @SuppressWarnings("rawtypes")
        ConsentAdminCommandService commandService = commandServices
            .orderedStream().findFirst()
            .orElseGet(() -> java.util.ServiceLoader
                .load(ConsentAdminCommandService.class)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                    "No ConsentAdminCommandService found on classpath")));
        @SuppressWarnings("rawtypes")
        ConsentAdminQueryService queryService = queryServices
            .orderedStream().findFirst()
            .orElseGet(() -> java.util.ServiceLoader
                .load(ConsentAdminQueryService.class)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                    "No ConsentAdminQueryService found on classpath")));

        DefaultTenantService operio = builder.buildTenantService(commandService, queryService);
        LOG.info(
            "Operio SDK: initialized (mode={}, appName={})",
            props.getMode(),
            operio.appName()
        );
        return operio;
    }

    // ------------------------------------------------------------------ Aspects

    /**
     * AOP aspect for {@code @ConsentRequired} annotation-driven consent
     * enforcement. Injected with the auto-configured {@link Operio} bean.
     */
    @Bean
    @ConditionalOnMissingBean
    public ConsentRequiredAspect consentRequiredAspect(Operio operio) {
        return new ConsentRequiredAspect(operio);
    }

    /**
     * AOP aspect for {@code @Sensitive} annotation-driven field masking.
     * Injected with the auto-configured {@link Operio} bean.
     */
    @Bean
    @ConditionalOnMissingBean
    public SensitiveAspect sensitiveAspect(Operio operio) {
        return new SensitiveAspect(operio);
    }
}
