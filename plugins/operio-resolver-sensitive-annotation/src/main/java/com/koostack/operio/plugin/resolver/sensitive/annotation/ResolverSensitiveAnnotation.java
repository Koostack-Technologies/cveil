package com.koostack.operio.plugin.resolver.sensitive.annotation;

import com.koostack.operio.core.service.SensitiveFieldResolver;
import com.koostack.operio.core.Models.ConsentScope;
import java.util.Set;
import java.util.Collections;

public class ResolverSensitiveAnnotation implements SensitiveFieldResolver {

    private final ObjectMapper mapper;

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
        
        // Pass a visited set to prevent infinite loops on circular dependencies
        return extractScopesFromClass(obj.getClass(), new HashSet<>());
    }

    private Set<ConsentScope> extractScopesFromClass(Class<?> clazz, Set<Class<?>> visitedClasses) {
        Set<ConsentScope> requiredScopes = new HashSet<>();

        // Stop conditions: nulls, primitives, standard Java classes, or already visited classes
        if (clazz == null || clazz.isPrimitive() || clazz.getName().startsWith("java.") || visitedClasses.contains(clazz)) {
            return requiredScopes;
        }
        visitedClasses.add(clazz);

        // Use Jackson to introspect the class properties
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
                // Determine the logical field name (use annotation override if present, else Jackson property name)
                String targetFieldName = sensitiveAnnotation.fieldName() != null && !sensitiveAnnotation.fieldName().isBlank()
                        ? sensitiveAnnotation.fieldName()
                        : prop.getName();

                requiredScopes.add(new ConsentScope(targetFieldName, sensitiveAnnotation.purpose()));
            }

            // Recursively inspect complex nested types (e.g., a User object containing an Address object)
            Class<?> propertyType = prop.getRawPrimaryType();
            requiredScopes.addAll(extractScopesFromClass(propertyType, visitedClasses));
        }

        return requiredScopes;
    }
}
