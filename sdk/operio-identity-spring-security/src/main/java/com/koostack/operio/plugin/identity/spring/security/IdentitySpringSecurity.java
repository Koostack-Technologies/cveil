package com.koostack.operio.plugin.identity.spring.security;

import com.koostack.operio.core.models.IdentityContext;
import com.koostack.operio.core.sdk.IdentityResolver;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Spring Security–based {@link IdentityResolver} that extracts a rich
 * {@link IdentityContext} from the current {@link SecurityContextHolder}.
 *
 * <p>In the Operio cloud deployment this plugin runs <em>after</em> the
 * {@code ApiKeyAuthenticationFilter} has already verified the API key via
 * {@code ApiKeyTokenVerifier.verifyToken()} and populated the
 * {@code SecurityContextHolder}.  This resolver therefore only <em>reads</em>
 * the authentication — it never verifies tokens itself.
 *
 * <h3>Extraction rules</h3>
 * <ul>
 *   <li>{@code subject} — {@code auth.getName()}</li>
 *   <li>{@code token} — raw credentials if they are a {@code String}</li>
 *   <li>{@code email} — from OIDC {@code email} claim, OAuth2 attribute, or
 *       JWT claim (first match wins)</li>
 *   <li>{@code roles} — mapped from {@link GrantedAuthority} set</li>
 *   <li>{@code claims} — full claim map when the authentication carries a JWT
 *       or OAuth2/OIDC principal</li>
 *   <li>{@code tenantId} — resolved with the following priority:
 *       <ol>
 *         <li>Authentication details map entry {@code "tenantId"}</li>
 *         <li>JWT / OAuth2 claim named {@code tenant_id} or {@code tenantId}</li>
 *         <li>Granted authority matching {@code TENANT_*} (suffix is the id)</li>
 *       </ol>
 *   </li>
 *   <li>{@code provider} — always {@code "spring-security"}</li>
 * </ul>
 */
public class IdentitySpringSecurity
    implements IdentityResolver<IdentitySpringSecurityConfig>
{

    private static final String PROVIDER = "spring-security";
    private static final String TENANT_AUTHORITY_PREFIX = "TENANT_";

    // ------------------------------------------------------------------ Plugin
    @Override
    public String getName() {
        return PROVIDER;
    }

    @Override
    public Class<IdentitySpringSecurityConfig> getConfigurationType() {
        return IdentitySpringSecurityConfig.class;
    }

    @Override
    public void configure(IdentitySpringSecurityConfig config) {
        // No configuration required — this plugin reads from SecurityContextHolder.
    }

    // --------------------------------------------------------- IdentityResolver
    @Override
    public IdentityContext resolve() {
        Authentication auth =
            SecurityContextHolder.getContext().getAuthentication();

        // Handle unauthenticated / anonymous states
        if (
            auth == null ||
            !auth.isAuthenticated() ||
            "anonymousUser".equals(auth.getPrincipal())
        ) {
            return IdentityContext.anonymous();
        }

        String subject = auth.getName();
        String token = extractToken(auth);
        Set<String> roles = extractRoles(auth);
        Map<String, Object> claims = extractClaims(auth);
        String email = extractEmail(auth, claims);
        String tenantId = extractTenantId(auth, claims);

        return new IdentityContext(
            subject,
            token,
            email,
            roles,
            claims,
            tenantId,
            PROVIDER
        );
    }

    // -------------------------------------------------------- private helpers

    /**
     * Extracts the raw bearer/credential token when the credentials are a
     * plain {@code String} (common for JWT / Bearer setups).  For
     * {@link JwtAuthenticationToken} the token value is available directly.
     */
    private static String extractToken(Authentication auth) {
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            return jwtAuth.getToken().getTokenValue();
        }
        if (auth.getCredentials() instanceof String cred && !cred.isBlank()) {
            return cred;
        }
        return null;
    }

    /**
     * Maps all {@link GrantedAuthority} entries to a simple {@code Set<String>}.
     */
    private static Set<String> extractRoles(Authentication auth) {
        if (auth.getAuthorities() == null || auth.getAuthorities().isEmpty()) {
            return Set.of();
        }
        return auth
            .getAuthorities()
            .stream()
            .map(GrantedAuthority::getAuthority)
            .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Builds a combined claim map from JWT claims or OAuth2 user attributes.
     * Returns an unmodifiable map (never {@code null}).
     */
    private static Map<String, Object> extractClaims(Authentication auth) {
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            Map<String, Object> jwtClaims = jwtAuth.getToken().getClaims();
            return jwtClaims != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(jwtClaims))
                : Map.of();
        }

        if (auth instanceof OAuth2AuthenticationToken oauth2Auth) {
            OAuth2User principal = oauth2Auth.getPrincipal();
            if (principal != null) {
                Map<String, Object> attrs = new LinkedHashMap<>();

                // OIDC users carry structured claims; merge them first
                if (
                    principal instanceof OidcUser oidcUser &&
                    oidcUser.getClaims() != null
                ) {
                    attrs.putAll(oidcUser.getClaims());
                }

                // Layer on generic OAuth2 attributes (won't overwrite OIDC claims)
                if (principal.getAttributes() != null) {
                    principal.getAttributes().forEach(attrs::putIfAbsent);
                }

                return Collections.unmodifiableMap(attrs);
            }
        }

        // Fall back: check if details carries a map (e.g. custom auth filter)
        if (auth.getDetails() instanceof Map<?, ?> detailsMap) {
            Map<String, Object> safe = new HashMap<>();
            detailsMap.forEach((k, v) -> {
                if (k instanceof String key) {
                    safe.put(key, v);
                }
            });
            if (!safe.isEmpty()) {
                return Collections.unmodifiableMap(safe);
            }
        }

        return Map.of();
    }

    /**
     * Extracts an email address from (in priority order):
     * <ol>
     *   <li>OIDC/OAuth2 principal email</li>
     *   <li>{@code email} key in the claims map</li>
     * </ol>
     */
    private static String extractEmail(
        Authentication auth,
        Map<String, Object> claims
    ) {
        // OIDC user has a typed email accessor
        if (auth instanceof OAuth2AuthenticationToken oauth2Auth) {
            OAuth2User principal = oauth2Auth.getPrincipal();
            if (principal instanceof OidcUser oidcUser) {
                String email = oidcUser.getEmail();
                if (email != null && !email.isBlank()) {
                    return email;
                }
            }
        }

        // Fall back to claims map
        Object emailClaim = claims.get("email");
        if (emailClaim instanceof String email && !email.isBlank()) {
            return email;
        }
        return null;
    }

    /**
     * Resolves the tenant ID using a three-level fallback:
     * <ol>
     *   <li>Authentication details map → {@code "tenantId"}</li>
     *   <li>Claims map → {@code "tenant_id"} or {@code "tenantId"}</li>
     *   <li>Granted authority with prefix {@code TENANT_}</li>
     * </ol>
     */
    private static String extractTenantId(
        Authentication auth,
        Map<String, Object> claims
    ) {
        // 1. Details map (set by custom auth filters such as ApiKeyAuthenticationFilter)
        if (auth.getDetails() instanceof Map<?, ?> detailsMap) {
            Object detailTenant = detailsMap.get("tenantId");
            if (detailTenant instanceof String tid && !tid.isBlank()) {
                return tid;
            }
        }

        // 2. JWT / OAuth2 claims
        String fromClaims = stringClaim(claims, "tenant_id");
        if (fromClaims == null) {
            fromClaims = stringClaim(claims, "tenantId");
        }
        if (fromClaims != null) {
            return fromClaims;
        }

        // 3. Granted authority convention: TENANT_<tenantId>
        if (auth.getAuthorities() != null) {
            for (GrantedAuthority ga : auth.getAuthorities()) {
                String authority = ga.getAuthority();
                if (
                    authority != null &&
                    authority.startsWith(TENANT_AUTHORITY_PREFIX)
                ) {
                    String suffix = authority.substring(
                        TENANT_AUTHORITY_PREFIX.length()
                    );
                    if (!suffix.isBlank()) {
                        return suffix;
                    }
                }
            }
        }

        return null;
    }

    /**
     * Safely extracts a non-blank {@code String} value from a claims map.
     */
    private static String stringClaim(Map<String, Object> claims, String key) {
        Object value = claims.get(key);
        if (value instanceof String s && !s.isBlank()) {
            return s;
        }
        return null;
    }
}
