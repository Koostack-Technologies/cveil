package com.koostack.operio.core.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.FIELD})
public @interface Sensitive {
   /** The category of data (e.g., "PII", "SSN", "HEALTH"). Defaults to PII. */
    Type value() default Type.PII;
    String fieldName() default "";
    String purpose() default "";

    static enum Type {
        PII,
        PARTIAL,
        FULL
    }
}