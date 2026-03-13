package com.koostack.cveil.core.models;

import java.util.Map;
import java.util.Set;
/**
 * Identity information resolved from the current request or execution context.
 *
 * @param subject   unique user ID (OIDC terminology, replaces former {@code user})
 * @param token     raw bearer token, nullable
 * @param email     user email, nullable
 * @param roles     authorities/roles from the IdP
 * @param claims    arbitrary IdP claims (OIDC, SAML, etc.)
 * @param tenantId  nullable — for multi-tenancy routing
 * @param provider  which IdP issued this (e.g. {@code "spring-security"}, {@code "okta"})
 */
public record IdentityContext(
    String subject,
    String token,
    String email,
    Set<String> roles,
    Map<String, Object> claims,
    String tenantId,
    String provider
) {
    public static IdentityContext of(String subject, String token) {
        return new IdentityContext(
            subject,
            token,
            null,
            Set.of(),
            Map.of(),
            null,
            null
        );
    }

    public static IdentityContext anonymous() {
        return new IdentityContext(
            "anonymous",
            null,
            null,
            Set.of(),
            Map.of(),
            null,
            null
        );
    }
}
