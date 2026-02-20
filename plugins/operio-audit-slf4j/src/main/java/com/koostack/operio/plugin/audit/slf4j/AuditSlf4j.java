package com.koostack.operio.plugin.audit.slf4j;

import com.koostack.operio.core.Models.ConsentScope;
import com.koostack.operio.core.service.AuditLogger;
import com.koostack.operio.core.service.SensitiveFieldResolver;

import lombok.extern.log4j.Log4j2;
import lombok.extern.slf4j.Slf4j;

@Log4j2
public class AuditSlf4j implements AuditLogger<AuditSlf4jConfig> {

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

	@Override
	public Class<AuditSlf4jConfig> getConfigurationType() {
	    return AuditSlf4jConfig.class;
	}

	@Override
	public void configure(AuditSlf4jConfig config) {
		// Intentionally left blank
	}
}
