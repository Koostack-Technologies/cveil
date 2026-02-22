package com.koostack.operio.plugin.resolver.sensitive.annotation;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.koostack.operio.core.Models.ConsentScope;
import com.koostack.operio.core.annotation.Sensitive;
import com.koostack.operio.core.service.SensitiveFieldResolver;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Annotation-driven {@link SensitiveFieldResolver} that discovers
 * {@link Sensitive @Sensitive} annotations on fields and getters via Jackson
 * bean introspection.
 *
 * <p><strong>Path-aware resolution</strong></p>
 * <p>Field names emitted in {@link ConsentScope#fieldName()} are
 * <strong>qualified dot-paths</strong> relative to the root object passed to
 * {@link #resolveScopes(Object)}. For example, given:
 * <pre>{@code
 * class User {
 *     @Sensitive(category="PII", purpose="contact")
 *     String email;          // → fieldName = "email"
 *
 *     Address home;
 * }
 * class Address {
 *     @Sensitive(category="PII", purpose="shipping")
 *     String street;         // → fieldName = "home.street"
 * }
 * }</pre>
 *
 * <p>This ensures that identically-named fields in different entities (e.g.
 * {@code User.email} vs {@code Contact.email}) are distinguishable when they
 * appear at different positions in the object graph.
 *
 * <p><strong>Circular-reference safety</strong></p>
 * <p>An <em>ancestor stack</em> (the set of classes on the current recursion
 * path from root to the node being inspected) prevents infinite loops.
 * A class is added to the stack on entry and removed on exit, so the same
 * class can still be visited at <em>different</em> paths in the graph (e.g.
 * {@code User.home} and {@code User.work} can both be {@code Address}).
 */
public class ResolverSensitiveAnnotation
    implements SensitiveFieldResolver<ResolverSensitiveAnnotationConfig>
{

    private ObjectMapper mapper;

    public ResolverSensitiveAnnotation() {
        this.mapper = new ObjectMapper();
    }

    public ResolverSensitiveAnnotation(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String getName() {
        return "sensitive-annotation";
    }

    @Override
    public Set<ConsentScope> resolveScopes(Object obj) {
        if (obj == null) {
            return Collections.emptySet();
        }

        // Ancestor stack prevents infinite recursion on circular references.
        // Path prefix starts empty — root-level fields have bare names.
        // Entity name is the root class's simple name.
        String entityName = obj.getClass().getSimpleName();
        return extractScopesFromClass(
            obj.getClass(),
            entityName,
            new HashSet<>(),
            ""
        );
    }

    /**
     * Recursively introspects {@code clazz} for {@link Sensitive @Sensitive}
     * annotations and returns the corresponding {@link ConsentScope}s with
     * qualified dot-path field names and entity context.
     *
     * @param clazz         the class to introspect
     * @param entityName    the root entity name (simple class name of the root
     *                      object passed to {@link #resolveScopes}); carried
     *                      through recursion unchanged so that nested fields
     *                      are attributed to the root entity
     * @param ancestorStack classes on the current recursion path (root → here);
     *                      used to break circular references. A class is added
     *                      on entry and removed on exit so that the same class
     *                      can be visited at different graph positions.
     * @param pathPrefix    dot-separated prefix representing the path from the
     *                      root object to this class (empty string at root)
     * @return the set of consent scopes discovered in {@code clazz} and its
     *         reachable nested types
     */
    private Set<ConsentScope> extractScopesFromClass(
        Class<?> clazz,
        String entityName,
        Set<Class<?>> ancestorStack,
        String pathPrefix
    ) {
        Set<ConsentScope> requiredScopes = new HashSet<>();

        // Stop conditions: nulls, primitives, standard Java classes, or
        // a class that is already an ancestor on the current recursion path.
        if (
            clazz == null ||
            clazz.isPrimitive() ||
            clazz.getName().startsWith("java.") ||
            ancestorStack.contains(clazz)
        ) {
            return requiredScopes;
        }

        // Mark this class as an ancestor for the duration of this branch.
        ancestorStack.add(clazz);

        try {
            // Use Jackson to introspect the class properties
            JavaType type = mapper.constructType(clazz);
            BeanDescription beanDesc = mapper
                .getSerializationConfig()
                .introspect(type);
            List<BeanPropertyDefinition> properties = beanDesc.findProperties();

            for (BeanPropertyDefinition prop : properties) {
                Sensitive sensitiveAnnotation = null;

                // Check the field for the annotation
                if (prop.hasField()) {
                    sensitiveAnnotation = prop
                        .getField()
                        .getAnnotation(Sensitive.class);
                }
                // Fallback to checking the getter method
                if (sensitiveAnnotation == null && prop.hasGetter()) {
                    sensitiveAnnotation = prop
                        .getGetter()
                        .getAnnotation(Sensitive.class);
                }

                if (sensitiveAnnotation != null) {
                    // Read all three @Sensitive attributes
                    String category = sensitiveAnnotation.category();
                    String purpose = sensitiveAnnotation.purpose();

                    // Leaf name: annotation override if present, else the
                    // Jackson-introspected property name.
                    String leafName =
                        sensitiveAnnotation.fieldName() != null &&
                        !sensitiveAnnotation.fieldName().isBlank()
                            ? sensitiveAnnotation.fieldName()
                            : prop.getName();

                    // Qualified path: "prefix.leafName" or just "leafName"
                    // at the root level.
                    String qualifiedName = pathPrefix.isEmpty()
                        ? leafName
                        : pathPrefix + "." + leafName;

                    requiredScopes.add(
                        new ConsentScope(
                            category, // category
                            qualifiedName, // fieldName (qualified dot-path)
                            entityName, // entityName (root class)
                            purpose // purposeName
                        )
                    );
                }

                // ----------------------------------------------------------
                // Recurse into nested types.
                // The prefix for child properties is built from the Jackson
                // property name (which matches the JSON key the masker sees),
                // NOT the @Sensitive.fieldName() override.
                // ----------------------------------------------------------
                String nestedPrefix = pathPrefix.isEmpty()
                    ? prop.getName()
                    : pathPrefix + "." + prop.getName();

                // Recurse into the raw property type (e.g. Address)
                Class<?> propertyType = prop.getRawPrimaryType();
                requiredScopes.addAll(
                    extractScopesFromClass(
                        propertyType,
                        entityName,
                        ancestorStack,
                        nestedPrefix
                    )
                );

                // If the property is a Collection, Map, or array, also
                // inspect the element/value type so that @Sensitive
                // annotations on e.g. List<Address> are discovered.
                JavaType propType = prop.getPrimaryType();
                if (propType.isCollectionLikeType() || propType.isArrayType()) {
                    JavaType contentType = propType.getContentType();
                    if (contentType != null) {
                        requiredScopes.addAll(
                            extractScopesFromClass(
                                contentType.getRawClass(),
                                entityName,
                                ancestorStack,
                                nestedPrefix
                            )
                        );
                    }
                } else if (propType.isMapLikeType()) {
                    JavaType valueType = propType.getContentType();
                    if (valueType != null) {
                        requiredScopes.addAll(
                            extractScopesFromClass(
                                valueType.getRawClass(),
                                entityName,
                                ancestorStack,
                                nestedPrefix
                            )
                        );
                    }
                }
            }
        } finally {
            // Remove from ancestor stack so the same class can be visited
            // at a different position in the object graph (e.g. User.home
            // and User.work can both be Address).
            ancestorStack.remove(clazz);
        }

        return requiredScopes;
    }

    @Override
    public Class<ResolverSensitiveAnnotationConfig> getConfigurationType() {
        return ResolverSensitiveAnnotationConfig.class;
    }

    @Override
    public void configure(ResolverSensitiveAnnotationConfig config) {
        if (config != null && config.objectMapper() != null) {
            this.mapper = config.objectMapper();
        }
    }
}
