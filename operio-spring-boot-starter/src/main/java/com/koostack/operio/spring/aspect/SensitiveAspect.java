package com.koostack.operio.spring.aspect;

import com.koostack.operio.core.annotation.Sensitive;
import com.koostack.operio.core.registry.PluginRegistry;
import com.koostack.operio.core.service.ConsentService;
import com.koostack.operio.core.service.MaskingStrategy;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Aspect
@Component
@RequiredArgsConstructor
public class SensitiveAspect {

    private final MaskingStrategy maskingService;
    private final ConsentService consentService;

    @Around("@annotation(sensitive)")
    public Object sensitive(ProceedingJoinPoint joinPoint, Sensitive sensitive) throws Throwable {
        return joinPoint.proceed();
    }
}