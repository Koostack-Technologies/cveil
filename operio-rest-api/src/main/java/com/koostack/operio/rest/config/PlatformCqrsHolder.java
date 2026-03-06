package com.koostack.operio.rest.config;

import com.koostack.operio.core.platform.core.CoreCommandService;
import com.koostack.operio.core.platform.core.CoreQueryService;
import com.koostack.operio.core.platform.iam.IamCommandService;
import com.koostack.operio.core.platform.iam.IamQueryService;
import com.koostack.operio.core.platform.iam.TokenValidationService;

/**
 * Concrete non-generic holder for platform CQRS services.
 *
 * <p>Quarkus ArC cannot resolve raw parameterized types (e.g., {@code IamCommandService}) through
 * {@code Instance<T>}. This holder wraps nullable service references in a concrete type that ArC
 * can produce and inject normally.
 *
 * <p>Services may be {@code null} in embedded mode when the platform CQRS stack is not configured.
 */
public class PlatformCqrsHolder {

    private final IamCommandService<?> iamCommandService;
    private final IamQueryService<?> iamQueryService;
    private final CoreQueryService<?> coreQueryService;
    private final CoreCommandService<?> coreCommandService;
    private final TokenValidationService<?> tokenValidationService;

    @SuppressWarnings("rawtypes")
    public PlatformCqrsHolder(
            IamCommandService iamCommandService,
            IamQueryService iamQueryService,
            CoreQueryService coreQueryService,
            CoreCommandService coreCommandService,
            TokenValidationService tokenValidationService) {
        this.iamCommandService = iamCommandService;
        this.iamQueryService = iamQueryService;
        this.coreQueryService = coreQueryService;
        this.coreCommandService = coreCommandService;
        this.tokenValidationService = tokenValidationService;
    }

    /**
     * Returns {@code true} if IAM command, IAM query, and core query services are all available.
     */
    public boolean isCqrsAvailable() {
        return iamCommandService != null && iamQueryService != null && coreQueryService != null;
    }

    /** Returns {@code true} if API key token validation is available. */
    public boolean isTokenValidationAvailable() {
        return tokenValidationService != null;
    }

    @SuppressWarnings("unchecked")
    public <T> IamCommandService<T> iamCommandService() {
        return (IamCommandService<T>) iamCommandService;
    }

    @SuppressWarnings("unchecked")
    public <T> IamQueryService<T> iamQueryService() {
        return (IamQueryService<T>) iamQueryService;
    }

    @SuppressWarnings("unchecked")
    public <T> CoreQueryService<T> coreQueryService() {
        return (CoreQueryService<T>) coreQueryService;
    }

    @SuppressWarnings("unchecked")
    public <T> CoreCommandService<T> coreCommandService() {
        return (CoreCommandService<T>) coreCommandService;
    }

    @SuppressWarnings("unchecked")
    public <T> TokenValidationService<T> tokenValidationService() {
        return (TokenValidationService<T>) tokenValidationService;
    }
}
