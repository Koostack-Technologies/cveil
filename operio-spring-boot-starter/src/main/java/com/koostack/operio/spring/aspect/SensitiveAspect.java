package com.koostack.operio.spring.aspect;

import com.koostack.operio.core.Operio;
import com.koostack.operio.core.annotation.Sensitive;
import com.koostack.operio.core.models.ConsentResponse;
import com.koostack.operio.core.models.ConsentScope;
import com.koostack.operio.core.models.IdentityContext;
import com.koostack.operio.core.sdk.IdentityResolver;
import com.koostack.operio.core.service.AuditLogger;
import com.koostack.operio.core.service.ConsentDecisionService;
import com.koostack.operio.core.service.MaskingStrategy;
import java.util.Set;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * AOP aspect for {@code @Sensitive} annotation-driven field masking.
 *
 * <p>Intercepts methods annotated with {@link Sensitive} and delegates consent evaluation and
 * return-value masking to the {@link Operio} instance. When the current subject has
 * <strong>not</strong> granted consent for the annotated field's category/scope, the method's
 * return value is passed through the configured {@link MaskingStrategy} before being returned to
 * the caller. When consent <em>is</em> granted, the return value passes through unmodified.
 *
 * <h2>Evaluation Flow</h2>
 *
 * <ol>
 *   <li>Resolve the current subject's identity via {@link Operio#identityResolver()}
 *   <li>Build a {@link ConsentScope} from the annotation attributes ({@code category}, {@code
 *       fieldName}, {@code purpose})
 *   <li>Call {@link ConsentDecisionService#evaluateConsent(String, Set)
 *       operio.consentService().evaluateConsent(subject, scopes)}
 *   <li>If the scope appears in the {@code denied} set of the response, log the denial via the
 *       {@link AuditLogger} and apply the {@link MaskingStrategy} to the return value
 *   <li>If consent is granted, log the grant and return the value unmodified
 * </ol>
 *
 * <h2>Graceful Degradation</h2>
 *
 * <p>If no {@link IdentityResolver}, {@link ConsentDecisionService}, or {@link MaskingStrategy} is
 * configured on the {@link Operio} instance, the aspect logs a warning and allows the method to
 * proceed unconditionally without masking. This prevents hard failures during development or when
 * the consent infrastructure is not yet wired.
 *
 * @see Sensitive
 * @see ConsentRequiredAspect
 */
@Aspect
@Component
public class SensitiveAspect {

    private static final Logger LOG = LoggerFactory.getLogger(SensitiveAspect.class);

    private final Operio operio;

    public SensitiveAspect(Operio operio) {
        this.operio = operio;
    }

    @Around("@annotation(sensitive)")
    public Object sensitive(ProceedingJoinPoint joinPoint, Sensitive sensitive) throws Throwable {
        // ----- Guard: ensure required services are available -----
        IdentityResolver<?> identityResolver = operio.identityResolver();
        ConsentDecisionService<?> consentService = operio.consentService();
        MaskingStrategy<?> maskingStrategy = operio.maskingStrategy();

        String targetMethod =
                joinPoint.getSignature().getDeclaringType().getSimpleName()
                        + "."
                        + joinPoint.getSignature().getName()
                        + "()";

        if (identityResolver == null) {
            LOG.warn(
                    "Operio @Sensitive: no IdentityResolver configured — "
                            + "skipping masking check for {}",
                    targetMethod);
            return joinPoint.proceed();
        }

        if (consentService == null) {
            LOG.warn(
                    "Operio @Sensitive: no ConsentDecisionService configured — "
                            + "skipping masking check for {}",
                    targetMethod);
            return joinPoint.proceed();
        }

        if (maskingStrategy == null) {
            LOG.warn(
                    "Operio @Sensitive: no MaskingStrategy configured — "
                            + "skipping masking for {}",
                    targetMethod);
            return joinPoint.proceed();
        }

        // ----- Resolve identity -----
        IdentityContext identity = identityResolver.resolve();
        String subject = identity.subject();

        // ----- Build ConsentScope from annotation attributes -----
        ConsentScope scope =
                ConsentScope.to(
                        nonBlankOrNull(sensitive.category()),
                        nonBlankOrNull(sensitive.fieldName()),
                        nonBlankOrNull(sensitive.purpose()));

        Set<ConsentScope> requestedScopes = Set.of(scope);

        // ----- Evaluate consent -----
        ConsentResponse response = consentService.evaluateConsent(subject, requestedScopes);

        // ----- Always proceed — masking is applied to the return value -----
        Object returnValue = joinPoint.proceed();

        AuditLogger<?> auditLogger = operio.auditLogger();

        if (response.denied() != null && response.denied().contains(scope)) {
            // Consent denied — mask the return value
            if (auditLogger != null) {
                String reason =
                        response.reasons() != null
                                ? response.reasons()
                                        .getOrDefault(scope.fieldName(), "consent not granted")
                                : "consent not granted";
                auditLogger.logAccessDenied(subject, scope, reason);
            }

            LOG.debug(
                    "Operio @Sensitive: consent denied for subject '{}' on "
                            + "scope [category={}, field={}, purpose={}] — "
                            + "masking return value from {}",
                    subject,
                    scope.category(),
                    scope.fieldName(),
                    scope.purposeName(),
                    targetMethod);

            // Apply masking — the strategy receives the raw object and the
            // set of denied scopes, returning a masked copy.
            return maskingStrategy.mask(returnValue, response.denied());
        }

        // Consent granted — log and return unmodified
        if (auditLogger != null) {
            auditLogger.logAccessGranted(subject, scope);
        }

        LOG.debug(
                "Operio @Sensitive: consent granted for subject '{}' on "
                        + "scope [category={}, field={}, purpose={}] — "
                        + "returning unmasked value from {}",
                subject,
                scope.category(),
                scope.fieldName(),
                scope.purposeName(),
                targetMethod);

        return returnValue;
    }

    // ------------------------------------------------------------------ Utils

    /**
     * Normalises empty / blank annotation attribute strings to {@code null} so that {@link
     * ConsentScope} fields are cleanly unset rather than carrying empty strings.
     */
    private static String nonBlankOrNull(String value) {
        return (value != null && !value.isBlank()) ? value : null;
    }
}
