package com.koostack.operio.rest.config;

import com.koostack.operio.core.cqrs.CommandDispatcher;
import com.koostack.operio.core.platform.billing.BillingCommandService;
import com.koostack.operio.core.platform.core.CoreCommandService;
import com.koostack.operio.core.platform.core.CoreQueryService;
import com.koostack.operio.core.platform.iam.IamCommandService;
import com.koostack.operio.core.platform.iam.IamQueryService;
import com.koostack.operio.core.platform.iam.TokenValidationService;
import com.koostack.operio.core.platform.plan.PlanQueryService;

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
    private final BillingCommandService<?> billingCommandService;
    private final PlanQueryService<?> planQueryService;
    private final TokenValidationService<?> tokenValidationService;

    @SuppressWarnings("rawtypes")
    public PlatformCqrsHolder(
            IamCommandService iamCommandService,
            IamQueryService iamQueryService,
            CoreQueryService coreQueryService,
            CoreCommandService coreCommandService,
            BillingCommandService billingCommandService,
            PlanQueryService planQueryService,
            TokenValidationService tokenValidationService) {
        this.iamCommandService = iamCommandService;
        this.iamQueryService = iamQueryService;
        this.coreQueryService = coreQueryService;
        this.coreCommandService = coreCommandService;
        this.billingCommandService = billingCommandService;
        this.planQueryService = planQueryService;
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

    /**
     * Returns the IAM command dispatcher for direct command-record dispatch.
     *
     * @throws IllegalStateException if the underlying service is not a CommandDispatcher
     */
    public CommandDispatcher iamDispatcher() {
        if (iamCommandService instanceof CommandDispatcher cd) {
            return cd;
        }
        throw new IllegalStateException("IAM command service does not implement CommandDispatcher");
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

    /**
     * Returns the core command dispatcher for direct command-record dispatch.
     *
     * @throws IllegalStateException if the underlying service is not a CommandDispatcher
     */
    public CommandDispatcher coreDispatcher() {
        if (coreCommandService instanceof CommandDispatcher cd) {
            return cd;
        }
        throw new IllegalStateException(
                "Core command service does not implement CommandDispatcher");
    }

    @SuppressWarnings("unchecked")
    public <T> BillingCommandService<T> billingCommandService() {
        return (BillingCommandService<T>) billingCommandService;
    }

    /**
     * Returns the billing command dispatcher for direct command-record dispatch.
     *
     * @throws IllegalStateException if the underlying service is not a CommandDispatcher
     */
    public CommandDispatcher billingDispatcher() {
        if (billingCommandService instanceof CommandDispatcher cd) {
            return cd;
        }
        throw new IllegalStateException(
                "Billing command service does not implement CommandDispatcher");
    }

    @SuppressWarnings("unchecked")
    public <T> PlanQueryService<T> planQueryService() {
        return (PlanQueryService<T>) planQueryService;
    }

    @SuppressWarnings("unchecked")
    public <T> TokenValidationService<T> tokenValidationService() {
        return (TokenValidationService<T>) tokenValidationService;
    }
}
