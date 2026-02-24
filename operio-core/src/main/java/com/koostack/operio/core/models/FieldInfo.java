package com.koostack.operio.core.models;

/**
 * Immutable snapshot of a registered sensitive field.
 *
 * @param fieldName   the field name (e.g. {@code "email"}, {@code "home.street"})
 * @param entityName  the owning entity (e.g. {@code "User"}), or {@code null}
 *                    for entity-less fields
 * @param description optional human-readable description, or {@code null}
 */
public record FieldInfo(
    String fieldName,
    String entityName,
    String description
) {}
