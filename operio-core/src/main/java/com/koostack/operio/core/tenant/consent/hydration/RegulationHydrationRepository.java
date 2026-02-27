package com.koostack.operio.core.tenant.consent.hydration;

import com.koostack.operio.core.tenant.consent.aggregates.RegulationAggregate;

/**
 * Hydration repository for {@link RegulationAggregate} lookup by natural key.
 *
 * <p>Used by the command service to validate that referenced regulations exist
 * before emitting consent events.
 */
public interface RegulationHydrationRepository {

    /**
     * Finds a regulation aggregate by its natural key (regulation name).
     *
     * @param regulationName the regulation name to look up
     * @return the hydrated aggregate, or {@code null} if no events exist
     */
    RegulationAggregate findByName(String regulationName);
}
