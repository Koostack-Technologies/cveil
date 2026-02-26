package com.koostack.operio.core.data.consent;

import com.koostack.operio.core.models.ConsentResponse;
import com.koostack.operio.core.models.ConsentScope;
import com.koostack.operio.core.spi.Plugin;
import java.util.Set;

public interface ConsentDecisionQueryService<T> extends Plugin<T> {

    ConsentResponse evaluateConsent(
        String subject,
        Set<ConsentScope> accessRequests
    );
}
