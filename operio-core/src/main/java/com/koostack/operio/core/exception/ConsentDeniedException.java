package com.koostack.operio.core.exception;

import com.koostack.operio.core.models.ConsentScope;

import java.util.Set;

/**
 * Thrown when a consent policy evaluation determines that the current subject
 * has <strong>not</strong> granted consent for the requested scope(s).
 *
 * <p>Unlike {@link OperioAuthException} (which signals authentication or
 * credential failures that require configuration changes), a consent denial
 * is a <em>policy enforcement</em> outcome — the subject may resolve it by
 * granting consent through the normal consent management flow.
 *
 * <p>This exception extends {@link OperioException} directly rather than
 * {@link OperioConfigurationException} because it is not a configuration
 * problem and is potentially resolvable at runtime.
 *
 * <h2>Usage in AOP Aspects</h2>
 * <p>The {@code ConsentRequiredAspect} in the Spring Boot starter throws
 * this exception when a method annotated with {@code @ConsentRequired}
 * is invoked but the current subject lacks the required consent.
 *
 * @see com.koostack.operio.core.annotation.ConsentRequired
 */
public class ConsentDeniedException extends OperioException {

    private final String subject;
    private final Set<ConsentScope> deniedScopes;

    /**
     * Creates a new consent-denied exception.
     *
     * @param message      human-readable description of the denial
     * @param subject      the data subject whose consent was evaluated
     * @param deniedScopes the scope(s) for which consent was denied
     */
    public ConsentDeniedException(
        String message,
        String subject,
        Set<ConsentScope> deniedScopes
    ) {
        super(message);
        this.subject = subject;
        this.deniedScopes = deniedScopes != null ? Set.copyOf(deniedScopes) : Set.of();
    }

    /**
     * Creates a new consent-denied exception with a single denied scope.
     *
     * @param message     human-readable description of the denial
     * @param subject     the data subject whose consent was evaluated
     * @param deniedScope the scope for which consent was denied
     */
    public ConsentDeniedException(
        String message,
        String subject,
        ConsentScope deniedScope
    ) {
        this(message, subject, deniedScope != null ? Set.of(deniedScope) : Set.of());
    }

    /**
     * Creates a simple consent-denied exception with only a message.
     *
     * @param message human-readable description of the denial
     */
    public ConsentDeniedException(String message) {
        super(message);
        this.subject = null;
        this.deniedScopes = Set.of();
    }

    /**
     * Returns the data subject whose consent was evaluated.
     *
     * @return the subject identifier, or {@code null} if not available
     */
    public String subject() {
        return subject;
    }

    /**
     * Returns the consent scope(s) that were denied.
     *
     * @return an unmodifiable set of denied scopes; never {@code null}
     */
    public Set<ConsentScope> deniedScopes() {
        return deniedScopes;
    }
}
