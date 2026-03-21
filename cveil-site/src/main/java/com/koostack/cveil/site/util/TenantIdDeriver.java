package com.koostack.cveil.site.util;

/**
 * Derives a tenant ID from a username. The tenant ID is a sanitized, lowercase string suitable for
 * use as a database identifier (max 63 chars, starts with a letter, only [a-z0-9_]).
 */
public final class TenantIdDeriver {

    private TenantIdDeriver() {}

    /**
     * Derive a tenant ID from the given username.
     *
     * @param username the user's unique login name (must not be blank)
     * @return sanitized tenant ID
     * @throws IllegalArgumentException if username is null or blank
     */
    public static String derive(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username must not be blank");
        }
        String result =
                username.toLowerCase()
                        .replaceAll("[^a-z0-9]", "_")
                        .replaceAll("^[0-9_]+", "")
                        .replaceAll("_+", "_");
        if (result.isEmpty()) {
            throw new IllegalArgumentException(
                    "username '" + username + "' produces an empty tenant ID after sanitization");
        }
        return result.substring(0, Math.min(result.length(), 63));
    }
}
