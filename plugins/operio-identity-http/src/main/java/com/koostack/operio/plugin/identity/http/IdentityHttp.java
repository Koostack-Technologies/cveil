package com.koostack.operio.plugin.identity.http;

import com.koostack.operio.core.models.IdentityContext;
import com.koostack.operio.core.service.IdentityResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public class IdentityHttp implements IdentityResolver<IdentityHttpConfig> {

    private String userHeader = "X-User-Id";
    private String tokenHeader = "Authorization";

    public IdentityHttp() {
        // Fallback to system properties if instantiated purely via SPI without builder access
        String sysUser = System.getProperty("operio.header.user");
        if (sysUser != null) this.userHeader = sysUser;

        String sysToken = System.getProperty("operio.header.token");
        if (sysToken != null) this.tokenHeader = sysToken;
    }

    // Setters allow you to configure this programmatically if you manually register the instance
    public void setUserHeader(String userHeader) {
        this.userHeader = userHeader;
    }

    public void setTokenHeader(String tokenHeader) {
        this.tokenHeader = tokenHeader;
    }

    @Override
    public String getName() {
        return "http-header";
    }

    @Override
    public IdentityContext resolve() {
        ServletRequestAttributes attributes =
            (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();

        // If we aren't in an active web request thread, default to anonymous
        if (attributes == null) {
            return IdentityContext.anonymous();
        }

        HttpServletRequest request = attributes.getRequest();
        String user = request.getHeader(userHeader);
        String token = request.getHeader(tokenHeader);

        if (user == null || user.isBlank()) {
            user = "anonymous";
        }

        // Clean up common Bearer token prefixes if present
        if (token != null && token.toLowerCase().startsWith("bearer ")) {
            token = token.substring(7);
        }

        return IdentityContext.of(user, token);
    }

    @Override
    public Class<IdentityHttpConfig> getConfigurationType() {
        return IdentityHttpConfig.class;
    }

    @Override
    public void configure(IdentityHttpConfig config) {
        // Intentionally left blank
    }
}
