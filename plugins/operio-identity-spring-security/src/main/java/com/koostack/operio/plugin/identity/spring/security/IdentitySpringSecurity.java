package com.koostack.operio.plugin.identity.spring.security;

import com.koostack.operio.core.service.SensitiveFieldResolver;

public class IdentitySpringSecurity implements IdentityResolver {

    @Override
    public String getName() {
        return "spring-security";
    }

    @Override
    public IdentityContext resolve() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        
        // Handle unauthenticated states and Spring's default anonymous user
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return new IdentityContext("anonymous", null);
        }

        String username = auth.getName();
        String token = null;

        // If the credentials hold the raw token (common in JWT or Bearer setups)
        if (auth.getCredentials() instanceof String credentialsString) {
            token = credentialsString;
        }

        return new IdentityContext(username, token);
    }
}