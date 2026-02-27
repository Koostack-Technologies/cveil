package com.koostack.operio.core.data.consent.hydration;

import com.koostack.operio.core.data.consent.aggregates.PurposeAggregate;

/**
 * Hydration repository for {@link PurposeAggregate} lookup by natural key.
 *
 * <p>Used by the command service to validate that referenced purposes exist
 * before emitting consent events.
 */
public interface PurposeHydrationRepository {

    /**
     * Finds a purpose aggregate by its natural key (purpose name).
     *
     * @param purposeName the purpose name to look up
     * @return the hydrated aggregate, or {@code null} if no events exist
     */
    PurposeAggregate findByName(String purposeName);
}
