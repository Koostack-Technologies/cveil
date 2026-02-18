package com.koostack.operio.plugin.audit.noop;

import com.koostack.operio.core.service.SensitiveFieldResolver;

public class AuditNoop implements AuditLogger {

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
}