package com.koostack.operio.core.tenant.consent.hydration;

import com.koostack.operio.core.tenant.consent.aggregates.EntityAggregate;
import java.util.List;
import java.util.UUID;

/**
 * Hydration repository for {@link EntityAggregate} lookup by natural key.
 *
 * <p>Used by the command service to validate that referenced entities exist
 * before emitting consent events.
 */
public interface EntityHydrationRepository {

    /**
     * Finds an entity aggregate by its natural key (entity name).
     *
     * @param entityName the entity name to look up
     * @return the hydrated aggregate, or {@code null} if no events exist
     */
    EntityAggregate findByName(String entityName);

    /**
     * Lists all entity aggregate IDs in the current tenant.
     *
     * @return distinct aggregate IDs for all entity aggregates
     */
    List<UUID> listEntityAggregateIds();
}
