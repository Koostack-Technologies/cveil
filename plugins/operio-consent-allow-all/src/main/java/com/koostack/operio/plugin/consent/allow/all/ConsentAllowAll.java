package com.koostack.operio.plugin.consent.allow.all;

import com.koostack.operio.core.service.SensitiveFieldResolver;

public class ConsentAllowAll implements ConsentService {

    @Override
    public String getName() {
        return "allow-all";
    }

    @Override
    public ConsentResponse evaluateConsent(String subject, Set<ConsentScope> accessRequests) {
        // Automatically grant all requested access scopes
        return ConsentResponse.to(
            subject, 
            accessRequests,           // Granted scopes
            Collections.emptySet(),   // Denied scopes (None)
            Collections.emptyMap()    // Denial reasons (None)
        );
    }

    @Override
    public void grantConsent(String subject, ConsentScope accessRequest) {
        // No-op: Everything is already allowed by default
    }

    @Override
    public void revokeConsent(String subject, ConsentScope accessRequest) {
        // No-op: Revocation isn't supported in a purely permissive implementation
    }
}
