package com.koostack.operio.plugin.discovery.sensitive.annotation;

import com.koostack.operio.core.spi.PluginConfig;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;

/**
 * Configuration for the annotation-based {@link SensitiveAnnotationDiscovery} plugin.
 *
 * <p>Specifies the set of root classes to scan for {@link
 * com.koostack.operio.core.annotation.Sensitive @Sensitive} annotations. The plugin introspects
 * each class (and its reachable nested types) via Jackson bean introspection — the same mechanism
 * used by the runtime {@code ResolverSensitiveAnnotation} plugin.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * var config = new AnnotationDiscoveryConfig(
 *     Set.of(User.class, Patient.class, Order.class)
 * );
 * }</pre>
 *
 * @param scanClasses the root classes to scan for {@code @Sensitive} annotations; must not be
 *     {@code null} or empty
 */
public record AnnotationDiscoveryConfig(Set<Class<?>> scanClasses) implements PluginConfig {

    public AnnotationDiscoveryConfig {
        Objects.requireNonNull(scanClasses, "scanClasses must not be null");
        scanClasses = Collections.unmodifiableSet(scanClasses);
    }

    /**
     * Convenience varargs factory for inline construction.
     *
     * <pre>{@code
     * var config = AnnotationDiscoveryConfig.of(User.class, Patient.class);
     * }</pre>
     *
     * @param classes the root classes to scan
     * @return a new config containing the given classes
     */
    public static AnnotationDiscoveryConfig of(Class<?>... classes) {
        return new AnnotationDiscoveryConfig(Set.of(classes));
    }
}
