package com.koostack.operio.aspects;

import com.operio.annotations.ConsentRequired;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class ConsentRequiredAspect {

    @Around("@annotation(consentRequired)")
    public Object checkConsent(ProceedingJoinPoint joinPoint, ConsentRequired consentRequired) throws Throwable {
        String dataType = consentRequired.dataType();

        // TODO: Implement consent check logic here
        // if (!consentService.hasConsent(dataType)) { throw new SecurityException(...); }

        return joinPoint.proceed();
    }
}
