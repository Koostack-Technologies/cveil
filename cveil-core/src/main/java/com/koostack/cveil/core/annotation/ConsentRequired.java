package com.koostack.cveil.core.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as requiring consent before execution.
 *
 * <p>When a method is annotated with {@code @ConsentRequired}, the AOP aspect
 * ({@code ConsentRequiredAspect} in the Spring Boot starter) will intercept the
 * call, resolve the current subject's identity, and evaluate whether consent has
 * been granted for the specified scope. If consent is not granted, an
 * {@code CveilAuthException} is thrown and the method is not executed.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * @ConsentRequired(dataType = "PII", fieldName = "email", purpose = "marketing")
 * public String getUserEmail(String userId) {
 *     return userRepository.findEmail(userId);
 * }
 * }</pre>
 *
 * <h3>Scope Resolution</h3>
 * <p>The annotation attributes map to a {@code ConsentScope} as follows:
 * <ul>
 *   <li>{@link #dataType()} → {@code ConsentScope.category}</li>
 *   <li>{@link #fieldName()} → {@code ConsentScope.fieldName}</li>
 *   <li>{@link #entityName()} → {@code ConsentScope.entityName}</li>
 *   <li>{@link #purpose()} → {@code ConsentScope.purposeName}</li>
 * </ul>
 *
 * @see com.koostack.cveil.core.annotation.Sensitive
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
public @interface ConsentRequired {

    /**
     * The data type / consent category (e.g. {@code "PII"}, {@code "HEALTH"},
     * {@code "FINANCIAL"}). Maps to {@code ConsentScope.category}.
     *
     * @return the consent category; defaults to {@code "PII"}
     */
    String dataType() default "PII";

    /**
     * The specific field name requiring consent (e.g. {@code "email"},
     * {@code "ssn"}). Maps to {@code ConsentScope.fieldName}.
     *
     * <p>When left empty, consent is checked at the category level only.
     *
     * @return the field name; defaults to empty string
     */
    String fieldName() default "";

    /**
     * The entity name the field belongs to (e.g. {@code "User"},
     * {@code "Patient"}). Maps to {@code ConsentScope.entityName}.
     *
     * <p>When left empty, entity-level scoping is not applied.
     *
     * @return the entity name; defaults to empty string
     */
    String entityName() default "";

    /**
     * The purpose for which consent is required (e.g. {@code "marketing"},
     * {@code "analytics"}). Maps to {@code ConsentScope.purposeName}.
     *
     * <p>When left empty, the consent check is purpose-agnostic.
     *
     * @return the purpose name; defaults to empty string
     */
    String purpose() default "";
}
