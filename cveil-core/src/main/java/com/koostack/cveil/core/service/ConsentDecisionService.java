package com.koostack.cveil.core.service;

import com.koostack.cveil.core.models.ConsentResponse;
import com.koostack.cveil.core.models.ConsentScope;
import com.koostack.cveil.core.spi.Plugin;
import java.util.Set;

public interface ConsentDecisionService<T> extends Plugin<T> {
    ConsentResponse evaluateConsent(String subject, Set<ConsentScope> accessRequests);

    /**
     * Returns the number of cached consent-decision edge-property keys for the given subject.
     * Implementations backed by a cache (e.g. Redis) should override this; the default returns
     * {@code 0} (no cache).
     */
    default long countCachedKeysForSubject(String subject) {
        return 0;
    }

    /**
     * Deletes all cached consent-decision edge-property keys for the given subject and returns the
     * number of keys removed. Implementations backed by a cache (e.g. Redis) should override this;
     * the default is a no-op returning {@code 0}.
     */
    default long deleteCachedKeysForSubject(String subject) {
        return 0;
    }
}
