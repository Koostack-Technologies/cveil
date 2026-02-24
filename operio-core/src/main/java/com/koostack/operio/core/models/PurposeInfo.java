package com.koostack.operio.core.models;

/**
 * Immutable snapshot of a registered consent purpose.
 *
 * @param purposeName the purpose identifier (e.g. {@code "marketing"},
 *                    {@code "analytics"})
 * @param description optional human-readable description, or {@code null}
 */
public record PurposeInfo(String purposeName, String description) {}
