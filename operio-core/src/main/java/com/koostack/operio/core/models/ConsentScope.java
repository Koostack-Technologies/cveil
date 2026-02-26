package com.koostack.operio.core.models;

public record ConsentScope(
    String category,
    String fieldName,
    String entityName,
    String purposeName
) {
    /**
     * Backward-compatible factory — creates a scope with no entity
     * context. Suitable when the caller does not know (or does not
     * care about) which entity the field belongs to.
     */
    public static ConsentScope to(
        String category,
        String fieldName,
        String forPurpose
    ) {
        return new ConsentScope(category, fieldName, null, forPurpose);
    }

    /**
     * Full factory including entity name for unambiguous field
     * identification across entities.
     *
     * @param category   the consent category (e.g. {@code "PII"})
     * @param fieldName  the field name (e.g. {@code "email"})
     * @param entityName the owning entity (e.g. {@code "User"}), or
     *                   {@code null} for entity-less fields
     * @param forPurpose the purpose (e.g. {@code "marketing"})
     * @return a fully qualified consent scope
     */
    public static ConsentScope to(
        String category,
        String fieldName,
        String entityName,
        String forPurpose
    ) {
        return new ConsentScope(
            category,
            fieldName,
            entityName,
            forPurpose
        );
    }
}
