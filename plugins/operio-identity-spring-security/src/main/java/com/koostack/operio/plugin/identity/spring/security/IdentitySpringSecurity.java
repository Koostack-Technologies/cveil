package com.koostack.operio.plugin.identity.spring.security;

import com.koostack.operio.core.Models.IdentityContext;
import com.koostack.operio.core.service.IdentityResolver;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public class IdentitySpringSecurity
    implements IdentityResolver<IdentitySpringSecurityConfig>
{

    @Override
    public String getName() {
        return "spring-security";
    }

    @Override
    public IdentityContext resolve() {
        Authentication auth =
            SecurityContextHolder.getContext().getAuthentication();

        // Handle unauthenticated states and Spring's default anonymous user
        if (
            auth == null ||
            !auth.isAuthenticated() ||
            "anonymousUser".equals(auth.getPrincipal())
        ) {
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

    @Override
    public Class<IdentitySpringSecurityConfig> getConfigurationType() {
        return IdentitySpringSecurityConfig.class;
    }

    @Override
    public void configure(IdentitySpringSecurityConfig config) {
        // Intentionally left blank
    }
}
