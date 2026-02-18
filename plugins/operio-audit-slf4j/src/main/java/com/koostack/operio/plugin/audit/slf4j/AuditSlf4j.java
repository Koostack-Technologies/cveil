package com.koostack.operio.plugin.audit.slf4j;

import com.koostack.operio.core.service.SensitiveFieldResolver;

public class AuditSlf4j implements AuditLogger {
    
    private static final Logger log = LoggerFactory.getLogger(AuditSlf4j.class);

    @Override
    public String getName() {
        return "slf4j";
    }

    @Override
    public void logAccessGranted(String user, ConsentScope scope) {
        log.debug("Access GRANTED - User: [{}], Field: [{}], Purpose: [{}]", 
                user, scope.fieldName(), scope.purposeName());
    }

    @Override
    public void logAccessDenied(String user, ConsentScope scope, String reason) {
        log.warn("Access DENIED - User: [{}], Field: [{}], Purpose: [{}], Reason: [{}]", 
                user, scope.fieldName(), scope.purposeName(), reason);
    }

    @Override
    public void logAlert(String message, Throwable throwable) {
        log.error("OPERIO SECURITY ALERT: {}", message, throwable);
    }
}