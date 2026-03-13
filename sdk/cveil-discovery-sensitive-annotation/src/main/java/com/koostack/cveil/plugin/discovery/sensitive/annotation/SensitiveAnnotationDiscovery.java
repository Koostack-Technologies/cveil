package com.koostack.cveil.plugin.discovery.sensitive.annotation;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.koostack.cveil.core.annotation.Sensitive;
import com.koostack.cveil.core.models.DiscoveredField;
import com.koostack.cveil.core.models.DiscoveryResult;
import com.koostack.cveil.core.sdk.SensitiveDiscovery;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Annotation-driven {@link SensitiveDiscovery} that scans a configured set of root classes for
 * {@link Sensitive @Sensitive} annotations and returns a {@link DiscoveryResult} containing every
 * discovered field and category.
 *
 * <p><strong>How it works</strong>
 *
 * <ol>
 *   <li>For each class in {@link AnnotationDiscoveryConfig#scanClasses()}, the plugin introspects
 *       the class and all reachable nested types using Jackson bean introspection (same mechanism
 *       as the runtime {@code ResolverSensitiveAnnotation} plugin).
 *   <li>Every field or getter annotated with {@code @Sensitive} produces a {@link DiscoveredField}
 *       with the field name (qualified dot-path), the owning class's simple name as the entity
 *       name, the declared category, purpose, and an auto-generated description.
 *   <li>The set of distinct non-blank category names is collected into the result's {@code
 *       categories} set.
 * </ol>
 *
 * <p><strong>Entity name convention</strong>
 *
 * <p>The {@code entityName} for each discovered field is derived from the <em>root</em> class that
 * was being scanned when the annotation was found — not the declaring class of the field. This
 * means that if {@code User} has a nested {@code Address} with an {@code @Sensitive} field, the
 * entity name will be {@code "User"}, and the field name will be a qualified dot-path like {@code
 * "home.street"}.
 *
 * <p><strong>Circular-reference safety</strong>
 *
 * <p>An ancestor stack prevents infinite recursion, identical to the runtime resolver's approach.
 *
 * @see AnnotationDiscoveryConfig
 * @see Sensitive
 */
public class SensitiveAnnotationDiscovery implements SensitiveDiscovery<AnnotationDiscoveryConfig> {

    private ObjectMapper mapper;
    private Set<Class<?>> scanClasses;

    public SensitiveAnnotationDiscovery() {
        this.mapper = new ObjectMapper();
        this.scanClasses = Set.of();
    }

    // -----------------------------------------------------------------------
    // Plugin SPI
    // -----------------------------------------------------------------------

    @Override
    public String getName() {
        return "sensitive-annotation-discovery";
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public Class<AnnotationDiscoveryConfig> getConfigurationType() {
        return AnnotationDiscoveryConfig.class;
    }

    @Override
    public void configure(AnnotationDiscoveryConfig config) {
        if (config != null) {
            this.scanClasses = config.scanClasses();
        }
    }

    // -----------------------------------------------------------------------
    // SensitiveDiscovery
    // -----------------------------------------------------------------------

    @Override
    public DiscoveryResult discover() {
        if (scanClasses == null || scanClasses.isEmpty()) {
            return DiscoveryResult.empty();
        }

        Set<DiscoveredField> allFields = new HashSet<>();

        for (Class<?> rootClass : scanClasses) {
            String entityName = rootClass.getSimpleName();
            Set<DiscoveredField> fieldsFromClass =
                    extractFieldsFromClass(rootClass, entityName, new HashSet<>(), "");
            allFields.addAll(fieldsFromClass);
        }

        // Collect distinct non-blank category names
        Set<String> categories =
                allFields.stream()
                        .map(DiscoveredField::category)
                        .filter(c -> c != null && !c.isBlank())
                        .collect(Collectors.toUnmodifiableSet());

        return new DiscoveryResult(allFields, categories);
    }

    // -----------------------------------------------------------------------
    // Internal — recursive introspection
    // -----------------------------------------------------------------------

    /**
     * Recursively introspects {@code clazz} for {@link Sensitive @Sensitive} annotations and
     * returns corresponding {@link DiscoveredField}s.
     *
     * @param clazz the class to introspect
     * @param entityName the root entity name (simple class name of the root scan class)
     * @param ancestorStack classes on the current recursion path; used to break circular references
     * @param pathPrefix dot-separated prefix representing the path from the root object to this
     *     class (empty string at root)
     * @return the set of discovered fields in {@code clazz} and its reachable nested types
     */
    private Set<DiscoveredField> extractFieldsFromClass(
            Class<?> clazz, String entityName, Set<Class<?>> ancestorStack, String pathPrefix) {
        Set<DiscoveredField> discovered = new HashSet<>();

        // Stop conditions: nulls, primitives, standard Java classes, or
        // a class already on the current recursion path.
        if (clazz == null
                || clazz.isPrimitive()
                || clazz.getName().startsWith("java.")
                || ancestorStack.contains(clazz)) {
            return discovered;
        }

        // Mark this class as an ancestor for the duration of this branch.
        ancestorStack.add(clazz);

        try {
            JavaType type = mapper.constructType(clazz);
            BeanDescription beanDesc = mapper.getSerializationConfig().introspect(type);
            List<BeanPropertyDefinition> properties = beanDesc.findProperties();

            for (BeanPropertyDefinition prop : properties) {
                Sensitive sensitiveAnnotation = null;

                // Check the field for the annotation
                if (prop.hasField()) {
                    sensitiveAnnotation = prop.getField().getAnnotation(Sensitive.class);
                }
                // Fallback to checking the getter method
                if (sensitiveAnnotation == null && prop.hasGetter()) {
                    sensitiveAnnotation = prop.getGetter().getAnnotation(Sensitive.class);
                }

                if (sensitiveAnnotation != null) {
                    String category = sensitiveAnnotation.category();
                    String purpose = sensitiveAnnotation.purpose();

                    // Leaf name: annotation override if present, else the
                    // Jackson-introspected property name.
                    String leafName =
                            sensitiveAnnotation.fieldName() != null
                                            && !sensitiveAnnotation.fieldName().isBlank()
                                    ? sensitiveAnnotation.fieldName()
                                    : prop.getName();

                    // Qualified path: "prefix.leafName" or just "leafName"
                    // at the root level.
                    String qualifiedName =
                            pathPrefix.isEmpty() ? leafName : pathPrefix + "." + leafName;

                    // Normalise blank strings to null for clean model records
                    String normalizedCategory =
                            (category != null && !category.isBlank()) ? category : null;
                    String normalizedPurpose =
                            (purpose != null && !purpose.isBlank()) ? purpose : null;

                    // Auto-generate a description from the metadata
                    String description =
                            buildDescription(qualifiedName, entityName, normalizedCategory);

                    discovered.add(
                            new DiscoveredField(
                                    qualifiedName,
                                    entityName,
                                    normalizedCategory,
                                    normalizedPurpose,
                                    description));
                }

                // ----------------------------------------------------------
                // Recurse into nested types.
                // ----------------------------------------------------------
                String nestedPrefix =
                        pathPrefix.isEmpty() ? prop.getName() : pathPrefix + "." + prop.getName();

                Class<?> propertyType = prop.getRawPrimaryType();
                discovered.addAll(
                        extractFieldsFromClass(
                                propertyType, entityName, ancestorStack, nestedPrefix));

                // If the property is a Collection, Map, or array, also
                // inspect the element/value type.
                JavaType propType = prop.getPrimaryType();
                if (propType.isCollectionLikeType() || propType.isArrayType()) {
                    JavaType contentType = propType.getContentType();
                    if (contentType != null) {
                        discovered.addAll(
                                extractFieldsFromClass(
                                        contentType.getRawClass(),
                                        entityName,
                                        ancestorStack,
                                        nestedPrefix));
                    }
                } else if (propType.isMapLikeType()) {
                    JavaType valueType = propType.getContentType();
                    if (valueType != null) {
                        discovered.addAll(
                                extractFieldsFromClass(
                                        valueType.getRawClass(),
                                        entityName,
                                        ancestorStack,
                                        nestedPrefix));
                    }
                }
            }
        } finally {
            // Remove from ancestor stack so the same class can be visited
            // at a different position in the object graph.
            ancestorStack.remove(clazz);
        }

        return discovered;
    }

    /**
     * Builds a human-readable description for a discovered field.
     *
     * @param qualifiedName the qualified dot-path field name
     * @param entityName the owning entity name
     * @param category the category, or {@code null}
     * @return a description string
     */
    private String buildDescription(String qualifiedName, String entityName, String category) {
        var sb = new StringBuilder();
        sb.append("Sensitive field '")
                .append(qualifiedName)
                .append("' discovered on entity '")
                .append(entityName)
                .append("'");
        if (category != null) {
            sb.append(" in category '").append(category).append("'");
        }
        sb.append(" via @Sensitive annotation");
        return sb.toString();
    }
}
