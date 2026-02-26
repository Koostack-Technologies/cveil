package com.koostack.operio.core.models;

/**
 * Immutable snapshot of a registered regulation.
 *
 * @param regulationName short identifier (e.g. {@code "GDPR"}, {@code "CCPA"})
 * @param fullName       full legal name (e.g. {@code "General Data Protection
 *                       Regulation"})
 * @param jurisdiction   geographic jurisdiction (e.g. {@code "EU"}, {@code "US-CA"})
 * @param description    optional human-readable description, or {@code null}
 * @param url            optional URL to the regulation text, or {@code null}
 */
public record RegulationInfo(
    String regulationName,
    String fullName,
    String jurisdiction,
    String description,
    String url
) {}
