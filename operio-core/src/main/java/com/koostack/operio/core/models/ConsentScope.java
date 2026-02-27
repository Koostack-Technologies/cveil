package com.koostack.operio.core.models;

public record ConsentScope(
    String category,
    String fieldName,
    String entityName,
    String purposeName
) {
    /** Wildcard sentinel for dimensions not targeted by a scope. */
    public static final String WILDCARD = "*";

    /** Category-scoped: (category, *, *, purpose). */
    public static ConsentScope forCategory(String category, String purpose) {
        return new ConsentScope(category, WILDCARD, WILDCARD, purpose);
    }

    /** Field-scoped: (*, field, entity, purpose). */
    public static ConsentScope forField(String field, String entity, String purpose) {
        return new ConsentScope(WILDCARD, field, entity, purpose);
    }

    /** {@code true} when this scope targets a category (field and entity are wildcards). */
    public boolean isCategoryScoped() { return WILDCARD.equals(fieldName); }

    /** {@code true} when this scope targets a specific field (category is wildcard). */
    public boolean isFieldScoped() { return WILDCARD.equals(category); }

    /**
     * Backward-compatible factory — creates a scope with no entity
     * context. Suitable when the caller does not know (or does not
     * care about) which entity the field belongs to.
     *
     * @deprecated Use {@link #forCategory(String, String)} or
     *             {@link #forField(String, String, String)} instead.
     */
    @Deprecated
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
     *
     * @deprecated Use {@link #forCategory(String, String)} or
     *             {@link #forField(String, String, String)} instead.
     */
    @Deprecated
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
