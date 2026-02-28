package com.koostack.operio.core.models;

import java.util.Objects;
/**
 * A single sensitive field discovered by a {@link
 * com.koostack.operio.core.sdk.SensitiveDiscovery} plugin.
 *
 * <p>Captures all the metadata needed to register the field in the
 * consent admin schema: the field name, its owning entity (optional),
 * the category it belongs to (optional), a default purpose, and a
 * human-readable description.
 *
 * @param fieldName   the name of the sensitive field (e.g. {@code "email"},
 *                    {@code "home.street"} for nested paths)
 * @param entityName  the logical entity that owns this field (e.g.
 *                    {@code "User"}, {@code "Patient"}), or {@code null}
 *                    for entity-less fields
 * @param category    the category this field belongs to (e.g.
 *                    {@code "PII"}, {@code "HEALTH"}), or {@code null}
 *                    if no category is declared
 * @param purpose     the default purpose associated with this field
 *                    (e.g. {@code "marketing"}), or {@code null} if no
 *                    purpose is declared
 * @param description optional human-readable description of the field,
 *                    or {@code null}
 */
public record DiscoveredField(
    String fieldName,
    String entityName,
    String category,
    String purpose,
    String description
) {
    public DiscoveredField {
        Objects.requireNonNull(fieldName, "fieldName must not be null");
        if (fieldName.isBlank()) {
            throw new IllegalArgumentException(
                "fieldName must not be blank"
            );
        }
    }

    /**
     * Convenience factory for the common case where only the field name,
     * entity, and category are known.
     */
    public static DiscoveredField of(
        String fieldName,
        String entityName,
        String category
    ) {
        return new DiscoveredField(
            fieldName,
            entityName,
            category,
            null,
            null
        );
    }

    /**
     * Full factory including purpose and description.
     */
    public static DiscoveredField of(
        String fieldName,
        String entityName,
        String category,
        String purpose,
        String description
    ) {
        return new DiscoveredField(
            fieldName,
            entityName,
            category,
            purpose,
            description
        );
    }
}
