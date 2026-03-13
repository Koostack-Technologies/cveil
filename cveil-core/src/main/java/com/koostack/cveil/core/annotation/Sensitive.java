package com.koostack.cveil.core.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.FIELD})
public @interface Sensitive {
   /** The category of data (e.g., "PII", "SSN", "HEALTH"). Defaults to PII. */
    String category() default "";
    String fieldName() default "";
    String purpose() default "";

}