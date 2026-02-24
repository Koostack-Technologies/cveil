package com.koostack.operio.core.models;

/**
 * Immutable snapshot of a registered consent category.
 *
 * @param categoryName the category name (e.g. {@code "PII"}, {@code "HEALTH"})
 * @param description  optional human-readable description, or {@code null}
 */
public record CategoryInfo(String categoryName, String description) {}
