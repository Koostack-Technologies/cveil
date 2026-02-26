package com.koostack.operio.core.models;

/**
 * Immutable snapshot of a registered entity.
 *
 * @param entityName  the entity name (e.g. {@code "User"}, {@code "Patient"})
 * @param description optional human-readable description, or {@code null}
 */
public record EntityInfo(String entityName, String description) {}
