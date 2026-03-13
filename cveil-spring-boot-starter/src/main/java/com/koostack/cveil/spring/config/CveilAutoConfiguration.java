package com.koostack.cveil.spring.config;

import com.koostack.cveil.core.Cveil;
import com.koostack.cveil.core.sdk.IdentityResolver;
import com.koostack.cveil.core.spi.PluginBundle;
import com.koostack.cveil.spring.aspect.ConsentRequiredAspect;
import com.koostack.cveil.spring.aspect.SensitiveAspect;
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
 * Spring Boot auto-configuration for the Cveil consent SDK.
 *
 * <p>This starter is a <strong>client SDK</strong> for developers embedding Cveil in their Spring
 * Boot applications. It auto-wires an {@link Cveil} instance from Spring properties ({@code
 * cveil.*}) and provides AOP aspects for annotation-driven consent enforcement
 * ({@code @ConsentRequired}, {@code @Sensitive}).
 *
 * <p>The {@code cveil.mode} property controls which consent bundle is used:
 *
 * <ul>
 *   <li>{@code embedded} (default) — {@code EmbeddedConsentBundle} (H2 + Caffeine, zero external
 *       infra, ideal for dev/testing)
 *   <li>{@code production} — {@code CoreConsentBundle} (Postgres + Redis, server-grade
 *       infrastructure)
 * </ul>
 *
 * <p><strong>What is NOT in this starter:</strong> REST controllers, auth filters, rate limiting,
 * CORS, DTOs, exception handlers, platform admin wiring, bootstrap key logic. All of that lives in
 * the Quarkus REST API module ({@code cveil-rest-api}).
 */
@AutoConfiguration
@EnableConfigurationProperties(CveilProperties.class)
public class CveilAutoConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(CveilAutoConfiguration.class);

    // ------------------------------------------------------------------ Bundles

    /**
     * Embedded bundle (H2 + Caffeine) — activated when {@code cveil.mode} is {@code "embedded"} or
     * absent (the default for development/testing).
     *
     * <p>Uses reflection to instantiate {@code EmbeddedConsentBundle} so that the starter module
     * only needs a {@code compileOnly} dependency on the bundle. If the bundle is not on the
     * runtime classpath the bean is simply not created, and the user must provide their own {@link
     * PluginBundle} or {@link Cveil} bean.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "cveil.mode", havingValue = "embedded", matchIfMissing = true)
    static class EmbeddedBundleConfiguration {

        @Bean
        @ConditionalOnMissingBean(PluginBundle.class)
        PluginBundle embeddedConsentBundle(CveilProperties props) {
            LOG.info("Cveil SDK: configuring embedded bundle (H2 + Caffeine)");
            try {
                Class<?> bundleClass =
                        Class.forName("com.koostack.cveil.bundle.embedded.EmbeddedConsentBundle");

                if (props.getAppName() != null && !props.getAppName().isBlank()) {
                    // EmbeddedConsentBundle(String appName, AnnotationDiscoveryConfig
                    // discoveryConfig)
                    // We use the (String) constructor via the two-arg form with null discovery
                    var ctor = bundleClass.getConstructor(String.class);
                    return (PluginBundle) ctor.newInstance(props.getAppName());
                }

                // Default no-arg constructor
                return (PluginBundle) bundleClass.getConstructor().newInstance();
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(
                        "cveil.mode=embedded but 'cveil-bundle-embedded' is not on the classpath."
                            + " Add a runtime dependency on cveil-bundle-embedded, or switch to"
                            + " cveil.mode=production with cveil-bundle-core on the classpath.",
                        e);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Failed to instantiate EmbeddedConsentBundle", e);
            }
        }
    }

    /**
     * Production bundle (Postgres + Redis) — activated when {@code cveil.mode=production}.
     *
     * <p>Uses reflection to instantiate {@code CoreConsentBundle} so that the starter module only
     * needs a {@code compileOnly} dependency on the bundle. Requires {@code cveil.db.url} and
     * {@code cveil.redis.host} to be configured.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "cveil.mode", havingValue = "production")
    static class ProductionBundleConfiguration {

        @Bean
        @ConditionalOnMissingBean(PluginBundle.class)
        PluginBundle coreConsentBundle(CveilProperties props) {
            LOG.info("Cveil SDK: configuring production bundle (Postgres + Redis)");

            CveilProperties.DbProperties db = props.getDb();
            CveilProperties.RedisProperties redis = props.getRedis();

            if (db == null || db.getUrl() == null || db.getUrl().isBlank()) {
                throw new IllegalStateException(
                        "cveil.mode=production requires cveil.db.url to be set");
            }
            if (redis == null || redis.getHost() == null || redis.getHost().isBlank()) {
                throw new IllegalStateException(
                        "cveil.mode=production requires cveil.redis.host to be set");
            }

            try {
                // Build JooqConsentAdminServiceConfig
                Class<?> jooqConfigClass =
                        Class.forName(
                                "com.koostack.cveil.plugin.consent.jooq.JooqConsentAdminServiceConfig");
                Object jooqConfig =
                        jooqConfigClass
                                .getConstructor(String.class, String.class)
                                .newInstance(db.getUrl(), db.getDialect());

                // Build Redis config (try new ConsentGraphRedisConfig first, fall back to legacy)
                Object redisConfig;
                Class<?> redisConfigClass;
                try {
                    redisConfigClass =
                            Class.forName(
                                    "com.koostack.cveil.plugin.redis.common.ConsentGraphRedisConfig");
                } catch (ClassNotFoundException ignored) {
                    redisConfigClass =
                            Class.forName(
                                    "com.koostack.cveil.plugin.consent.redis.RedisConsentDecisionServiceConfig");
                }
                if (redis.getPassword() != null && !redis.getPassword().isBlank()) {
                    redisConfig =
                            redisConfigClass
                                    .getConstructor(String.class, int.class, String.class)
                                    .newInstance(
                                            redis.getHost(), redis.getPort(), redis.getPassword());
                } else {
                    redisConfig =
                            redisConfigClass
                                    .getConstructor(String.class, int.class)
                                    .newInstance(redis.getHost(), redis.getPort());
                }

                // Build CoreConsentBundle
                Class<?> bundleClass =
                        Class.forName("com.koostack.cveil.bundle.core.CoreConsentBundle");
                // Constructor parameter is RedisPluginConfig (interface), not the concrete class
                Class<?> redisPluginConfigClass =
                        Class.forName("com.koostack.cveil.plugin.redis.common.RedisPluginConfig");

                if (props.getAppName() != null && !props.getAppName().isBlank()) {
                    var ctor =
                            bundleClass.getConstructor(
                                    String.class, jooqConfigClass, redisPluginConfigClass);
                    return (PluginBundle)
                            ctor.newInstance(props.getAppName(), jooqConfig, redisConfig);
                }

                var ctor = bundleClass.getConstructor(jooqConfigClass, redisPluginConfigClass);
                return (PluginBundle) ctor.newInstance(jooqConfig, redisConfig);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(
                        "cveil.mode=production but 'cveil-bundle-core' is not on the classpath."
                            + " Add a runtime dependency on cveil-bundle-core, or switch to"
                            + " cveil.mode=embedded with cveil-bundle-embedded on the classpath.",
                        e);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Failed to instantiate CoreConsentBundle", e);
            }
        }
    }

    // ------------------------------------------------------------------ Cveil

    /**
     * The primary {@link Cveil} bean — wired from the auto-configured (or user-provided) {@link
     * PluginBundle} and any {@link IdentityResolver} found on the classpath.
     *
     * <p>If the user has already defined their own {@code Cveil} bean this one backs off via
     * {@code @ConditionalOnMissingBean}.
     *
     * <p>Identity resolver auto-discovery: if exactly one {@link IdentityResolver} bean exists in
     * the context it is wired into the builder. If none are found, Cveil's built-in SPI
     * ServiceLoader discovery is used (e.g. {@code IdentitySpringSecurity} registered via {@code
     * META-INF/services}). If multiple are found, a warning is logged and SPI discovery is used as
     * a fallback.
     */
    @Bean
    @ConditionalOnMissingBean
    public Cveil cveil(
            ObjectProvider<PluginBundle> bundles,
            ObjectProvider<IdentityResolver<?>> identityResolvers,
            CveilProperties props) {
        Cveil.Builder builder = Cveil.builder();

        // Apply app name if set (bundle may also set it; explicit property wins
        // because withAppName is called after withBundle)
        boolean hasAppName = props.getAppName() != null && !props.getAppName().isBlank();

        // Apply all available bundles (typically exactly one from the
        // conditional configurations above, but the user could provide extras)
        bundles.orderedStream()
                .forEach(
                        bundle -> {
                            LOG.debug("Cveil SDK: applying bundle '{}'", bundle.name());
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
                    "Cveil SDK: using IdentityResolver bean '{}'",
                    resolver.getClass().getSimpleName());
            builder.withIdentityProvider(resolver.getName());
        } else if (resolverList.isEmpty()) {
            LOG.debug(
                    "Cveil SDK: no IdentityResolver bean found — "
                            + "falling back to SPI ServiceLoader discovery");
        } else {
            LOG.warn(
                    "Cveil SDK: found {} IdentityResolver beans — "
                            + "falling back to SPI ServiceLoader discovery. "
                            + "Define a single IdentityResolver bean or configure one explicitly.",
                    resolverList.size());
        }

        Cveil cveil = builder.build();
        LOG.info(
                "Cveil SDK: initialized (mode={}, appName={})", props.getMode(), cveil.appName());
        return cveil;
    }

    // ------------------------------------------------------------------ Aspects

    /**
     * AOP aspect for {@code @ConsentRequired} annotation-driven consent enforcement. Injected with
     * the auto-configured {@link Cveil} bean.
     */
    @Bean
    @ConditionalOnMissingBean
    public ConsentRequiredAspect consentRequiredAspect(Cveil cveil) {
        return new ConsentRequiredAspect(cveil);
    }

    /**
     * AOP aspect for {@code @Sensitive} annotation-driven field masking. Injected with the
     * auto-configured {@link Cveil} bean.
     */
    @Bean
    @ConditionalOnMissingBean
    public SensitiveAspect sensitiveAspect(Cveil cveil) {
        return new SensitiveAspect(cveil);
    }
}
