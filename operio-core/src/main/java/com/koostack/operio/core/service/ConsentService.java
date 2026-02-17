package com.koostack.operio.core.service;

import java.util.Set;

import com.koostack.operio.core.Models.ConsentResponse;
import com.koostack.operio.core.Models.ConsentScope;
import com.koostack.operio.core.spi.NamedPlugin;
import com.koostack.operio.core.spi.Prioritized;

public interface ConsentService extends NamedPlugin, Prioritized {
    ConsentResponse evaluateConsent(String subject, Set<ConsentScope> accessRequests);
    void grantConsent(String subject, ConsentScope accessRequest);
    void revokeConsent(String subject, ConsentScope accessRequest);
}