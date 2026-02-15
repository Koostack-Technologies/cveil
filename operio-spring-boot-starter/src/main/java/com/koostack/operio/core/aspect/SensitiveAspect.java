package com.koostack.operio.aspects;

import com.operio.annotations.Sensitive;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import java.lang.reflect.Parameter;

@Aspect
@Component
public class SensitiveAspect {

    @Around("execution(* *(.., @com.operio.annotations.Sensitive (*), ..))")
    public Object handleSensitiveData(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Parameter[] parameters = signature.getMethod().getParameters();
        Object[] args = joinPoint.getArgs();

        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].isAnnotationPresent(Sensitive.class)) {
                Sensitive annotation = parameters[i].getAnnotation(Sensitive.class);
                // TODO: Implement logic (e.g., mask the arg in logs or validate)
            }
        }

        return joinPoint.proceed(args);
    }
}
