package com.koostack.operio.core.service;

import com.koostack.operio.core.Models.ConsentResponse;
import com.koostack.operio.core.Models.ConsentScope;
import com.koostack.operio.core.spi.Plugin;
import java.util.Set;

public interface ConsentDecisionService<T>
extends Plugin<T>
{
    ConsentResponse evaluateConsent(
        String subject,
        Set<ConsentScope> accessRequests
    );

}
