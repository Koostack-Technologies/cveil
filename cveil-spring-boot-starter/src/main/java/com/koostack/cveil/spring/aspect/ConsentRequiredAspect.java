package com.koostack.cveil.spring.aspect;

import com.koostack.cveil.core.Cveil;
import com.koostack.cveil.core.annotation.ConsentRequired;
import com.koostack.cveil.core.exception.ConsentDeniedException;
import com.koostack.cveil.core.models.ConsentResponse;
import com.koostack.cveil.core.models.ConsentScope;
import com.koostack.cveil.core.models.IdentityContext;
import com.koostack.cveil.core.sdk.IdentityResolver;
import com.koostack.cveil.core.service.AuditLogger;
import com.koostack.cveil.core.service.ConsentDecisionService;
import java.util.Set;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * AOP aspect for {@code @ConsentRequired} annotation-driven consent enforcement.
 *
 * <p>Intercepts methods annotated with {@link ConsentRequired} and delegates consent evaluation to
 * the {@link Cveil} instance. If the current subject has not granted consent for the requested
 * scope, an {@link ConsentDeniedException} is thrown and the method is <strong>not</strong>
 * executed.
 *
 * <h2>Evaluation Flow</h2>
 *
 * <ol>
 *   <li>Resolve the current subject's identity via {@link Cveil#identityResolver()}
 *   <li>Build a {@link ConsentScope} from the annotation attributes ({@code dataType} → category,
 *       {@code fieldName}, {@code entityName}, {@code purpose} → purposeName)
 *   <li>Call {@link ConsentDecisionService#evaluateConsent(String, Set)
 *       cveil.consentService().evaluateConsent(subject, scopes)}
 *   <li>If the scope appears in the {@code denied} set of the response, log the denial via the
 *       {@link AuditLogger} and throw {@link ConsentDeniedException}
 *   <li>If consent is granted, log the grant and proceed with the original method invocation
 * </ol>
 *
 * <h2>Graceful Degradation</h2>
 *
 * <p>If no {@link IdentityResolver} or {@link ConsentDecisionService} is configured on the {@link
 * Cveil} instance, the aspect logs a warning and allows the method to proceed unconditionally.
 * This prevents hard failures during development or when the consent infrastructure is not yet
 * wired.
 */
@Aspect
@Component
public class ConsentRequiredAspect {

    private static final Logger LOG = LoggerFactory.getLogger(ConsentRequiredAspect.class);

    private final Cveil cveil;

    public ConsentRequiredAspect(Cveil cveil) {
        this.cveil = cveil;
    }

    @Around("@annotation(consentRequired)")
    public Object checkConsent(ProceedingJoinPoint joinPoint, ConsentRequired consentRequired)
            throws Throwable {
        // ----- Guard: ensure required services are available -----
        IdentityResolver<?> identityResolver = cveil.identityResolver();
        ConsentDecisionService<?> consentService = cveil.consentService();

        if (identityResolver == null) {
            LOG.warn(
                    "Cveil @ConsentRequired: no IdentityResolver configured — "
                            + "skipping consent check for {}.{}()",
                    joinPoint.getSignature().getDeclaringType().getSimpleName(),
                    joinPoint.getSignature().getName());
            return joinPoint.proceed();
        }

        if (consentService == null) {
            LOG.warn(
                    "Cveil @ConsentRequired: no ConsentDecisionService configured — "
                            + "skipping consent check for {}.{}()",
                    joinPoint.getSignature().getDeclaringType().getSimpleName(),
                    joinPoint.getSignature().getName());
            return joinPoint.proceed();
        }

        // ----- Resolve identity -----
        IdentityContext identity = identityResolver.resolve();
        String subject = identity.subject();

        // ----- Build ConsentScope from annotation attributes -----
        ConsentScope scope =
                ConsentScope.to(
                        nonBlankOrNull(consentRequired.dataType()),
                        nonBlankOrNull(consentRequired.fieldName()),
                        nonBlankOrNull(consentRequired.entityName()),
                        nonBlankOrNull(consentRequired.purpose()));

        Set<ConsentScope> requestedScopes = Set.of(scope);

        // ----- Evaluate consent -----
        ConsentResponse response = consentService.evaluateConsent(subject, requestedScopes);

        AuditLogger<?> auditLogger = cveil.auditLogger();

        if (response.denied() != null && response.denied().contains(scope)) {
            // Log the denial
            if (auditLogger != null) {
                String reason =
                        response.reasons() != null
                                ? response.reasons()
                                        .getOrDefault(scope.fieldName(), "consent not granted")
                                : "consent not granted";
                auditLogger.logAccessDenied(subject, scope, reason);
            }

            String denialMessage =
                    String.format(
                            "Consent denied for subject '%s' on scope [category=%s, field=%s, "
                                    + "entity=%s, purpose=%s] — method %s.%s() blocked",
                            subject,
                            scope.category(),
                            scope.fieldName(),
                            scope.entityName(),
                            scope.purposeName(),
                            joinPoint.getSignature().getDeclaringType().getSimpleName(),
                            joinPoint.getSignature().getName());

            LOG.debug(denialMessage);
            throw new ConsentDeniedException(denialMessage, subject, scope);
        }

        // Consent granted — log and proceed
        if (auditLogger != null) {
            auditLogger.logAccessGranted(subject, scope);
        }

        LOG.debug(
                "Cveil @ConsentRequired: consent granted for subject '{}' on "
                        + "scope [category={}, field={}, entity={}, purpose={}] — "
                        + "proceeding with {}.{}()",
                subject,
                scope.category(),
                scope.fieldName(),
                scope.entityName(),
                scope.purposeName(),
                joinPoint.getSignature().getDeclaringType().getSimpleName(),
                joinPoint.getSignature().getName());

        return joinPoint.proceed();
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
