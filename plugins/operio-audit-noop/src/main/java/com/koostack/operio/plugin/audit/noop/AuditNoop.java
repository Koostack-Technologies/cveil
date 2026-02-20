package com.koostack.operio.plugin.audit.noop;

import com.koostack.operio.core.Models.ConsentScope;
import com.koostack.operio.core.service.AuditLogger;

public class AuditNoop implements AuditLogger<AuditNoopConfig> {

    @Override
    public String getName() {
        return "noop";
    }

    @Override
    public void logAccessGranted(String user, ConsentScope scope) {
        // Intentionally left blank
    }

    @Override
    public void logAccessDenied(
        String user,
        ConsentScope scope,
        String reason
    ) {
        // Intentionally left blank
    }

    @Override
    public void logAlert(String message, Throwable throwable) {
        // Intentionally left blank
    }

    @Override
    public int getPriority() {
        return 0;
    }

	@Override
	public Class<AuditNoopConfig> getConfigurationType() {
	    return AuditNoopConfig.class;
	}

	@Override
	public void configure(AuditNoopConfig config) {
	    // Intentionally left blank
	}
}
