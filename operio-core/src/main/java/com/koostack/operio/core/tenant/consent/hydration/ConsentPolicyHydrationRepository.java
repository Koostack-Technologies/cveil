package com.koostack.operio.core.tenant.consent.hydration;

import com.koostack.operio.core.tenant.consent.aggregates.ConsentPolicyAggregate;

/**
 * Hydration repository for {@link ConsentPolicyAggregate} lookup by subject.
 *
 * <p>Used by the command service and sweeper to inspect consent state
 * for a given data subject.
 */
public interface ConsentPolicyHydrationRepository {

    /**
     * Finds a consent policy aggregate by subject ID.
     *
     * @param subjectId the data subject identifier
     * @return the hydrated aggregate, or {@code null} if no events exist
     */
    ConsentPolicyAggregate findBySubject(String subjectId);
}
