package com.koostack.operio.plugin.audit.noop;

import com.koostack.operio.core.Models.ConsentScope;
import com.koostack.operio.core.service.AuditLogger;
import com.koostack.operio.core.service.SensitiveFieldResolver;
import com.koostack.operio.core.spi.NamedPlugin;

public class AuditNoop implements NamedPlugin, Pr {

    @Override
    public String getName() {
        return "noop";
    }

    @Override
    public void logAccessGranted(String user, ConsentScope scope) {
        // Intentionally left blank
    }

    @Override
    public void logAccessDenied(String user, ConsentScope scope, String reason) {
        // Intentionally left blank
    }

    @Override
    public void logAlert(String message, Throwable throwable) {
        // Intentionally left blank
    }

	@Override
	public Class getConfigurationType() {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'getConfigurationType'");
	}

	@Override
	public void configure(Object config) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'configure'");
	}
}