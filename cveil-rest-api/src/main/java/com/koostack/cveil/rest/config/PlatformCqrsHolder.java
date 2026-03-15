package com.koostack.cveil.rest.config;

import com.koostack.cveil.core.cqrs.CommandDispatcher;
import com.koostack.cveil.core.cqrs.QueryDispatcher;
import com.koostack.cveil.core.platform.billing.BillingCommandService;
import com.koostack.cveil.core.platform.billing.BillingQueryService;
import com.koostack.cveil.core.platform.billing.PaymentProcessorService;
import com.koostack.cveil.core.platform.core.CoreCommandService;
import com.koostack.cveil.core.platform.core.CoreQueryService;
import com.koostack.cveil.core.platform.iam.IamQueryService;
import com.koostack.cveil.core.platform.iam.PermissionService;
import com.koostack.cveil.core.platform.iam.TokenValidationService;
import com.koostack.cveil.core.platform.plan.PlanQueryService;

/**
 * Concrete non-generic holder for platform CQRS services.
 *
 * <p>Quarkus ArC cannot resolve raw parameterized types (e.g., {@code IamQueryService}) through
 * {@code Instance<T>}. This holder wraps nullable service references in a concrete type that ArC
 * can produce and inject normally.
 *
 * <p>Services may be {@code null} in embedded mode when the platform CQRS stack is not configured.
 */
public class PlatformCqrsHolder {

    private final CommandDispatcher iamDispatcher;
    private final IamQueryService<?> iamQueryService;
    private final CoreQueryService<?> coreQueryService;
    private final CoreCommandService<?> coreCommandService;
    private final BillingCommandService<?> billingCommandService;
    private final PlanQueryService<?> planQueryService;
    private final TokenValidationService<?> tokenValidationService;
    private final PermissionService permissionService;
    private final BillingQueryService<?> billingQueryService;
    private final PaymentProcessorService<?> paymentProcessorService;

    @SuppressWarnings("rawtypes")
    public PlatformCqrsHolder(
            CommandDispatcher iamDispatcher,
            IamQueryService iamQueryService,
            CoreQueryService coreQueryService,
            CoreCommandService coreCommandService,
            BillingCommandService billingCommandService,
            PlanQueryService planQueryService,
            TokenValidationService tokenValidationService) {
        this(
                iamDispatcher,
                iamQueryService,
                coreQueryService,
                coreCommandService,
                billingCommandService,
                planQueryService,
                tokenValidationService,
                null,
                null,
                null);
    }

    @SuppressWarnings("rawtypes")
    public PlatformCqrsHolder(
            CommandDispatcher iamDispatcher,
            IamQueryService iamQueryService,
            CoreQueryService coreQueryService,
            CoreCommandService coreCommandService,
            BillingCommandService billingCommandService,
            PlanQueryService planQueryService,
            TokenValidationService tokenValidationService,
            PermissionService permissionService) {
        this(
                iamDispatcher,
                iamQueryService,
                coreQueryService,
                coreCommandService,
                billingCommandService,
                planQueryService,
                tokenValidationService,
                permissionService,
                null,
                null);
    }

    @SuppressWarnings("rawtypes")
    public PlatformCqrsHolder(
            CommandDispatcher iamDispatcher,
            IamQueryService iamQueryService,
            CoreQueryService coreQueryService,
            CoreCommandService coreCommandService,
            BillingCommandService billingCommandService,
            PlanQueryService planQueryService,
            TokenValidationService tokenValidationService,
            PermissionService permissionService,
            BillingQueryService billingQueryService,
            PaymentProcessorService paymentProcessorService) {
        this.iamDispatcher = iamDispatcher;
        this.iamQueryService = iamQueryService;
        this.coreQueryService = coreQueryService;
        this.coreCommandService = coreCommandService;
        this.billingCommandService = billingCommandService;
        this.planQueryService = planQueryService;
        this.tokenValidationService = tokenValidationService;
        this.permissionService = permissionService;
        this.billingQueryService = billingQueryService;
        this.paymentProcessorService = paymentProcessorService;
    }

    /**
     * Returns {@code true} if IAM command, IAM query, and core query services are all available.
     */
    public boolean isCqrsAvailable() {
        return iamDispatcher != null && iamQueryService != null && coreQueryService != null;
    }

    /** Returns {@code true} if API key token validation is available. */
    public boolean isTokenValidationAvailable() {
        return tokenValidationService != null;
    }

    /** Returns the IAM command dispatcher for direct command-record dispatch. */
    public CommandDispatcher iamDispatcher() {
        return iamDispatcher;
    }

    @SuppressWarnings("unchecked")
    public <T> IamQueryService<T> iamQueryService() {
        return (IamQueryService<T>) iamQueryService;
    }

    /**
     * Returns the IAM query dispatcher for direct query-record dispatch.
     *
     * @throws IllegalStateException if the underlying service is not a QueryDispatcher
     */
    public QueryDispatcher iamQueryDispatcher() {
        if (iamQueryService instanceof QueryDispatcher qd) {
            return qd;
        }
        throw new IllegalStateException("IAM query service does not implement QueryDispatcher");
    }

    @SuppressWarnings("unchecked")
    public <T> CoreQueryService<T> coreQueryService() {
        return (CoreQueryService<T>) coreQueryService;
    }

    /**
     * Returns the core query dispatcher for direct query-record dispatch.
     *
     * @throws IllegalStateException if the underlying service is not a QueryDispatcher
     */
    public QueryDispatcher coreQueryDispatcher() {
        if (coreQueryService instanceof QueryDispatcher qd) {
            return qd;
        }
        throw new IllegalStateException("Core query service does not implement QueryDispatcher");
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

    /** Returns the permission service for resolving user permissions per tenant. */
    public PermissionService permissionService() {
        return permissionService;
    }

    /** Returns {@code true} if the permission service is available. */
    public boolean isPermissionServiceAvailable() {
        return permissionService != null;
    }

    @SuppressWarnings("unchecked")
    public <T> BillingQueryService<T> billingQueryService() {
        return (BillingQueryService<T>) billingQueryService;
    }

    public QueryDispatcher billingQueryDispatcher() {
        if (billingQueryService instanceof QueryDispatcher qd) {
            return qd;
        }
        throw new IllegalStateException("Billing query service does not implement QueryDispatcher");
    }

    @SuppressWarnings("unchecked")
    public <T> PaymentProcessorService<T> paymentProcessorService() {
        return (PaymentProcessorService<T>) paymentProcessorService;
    }
}
