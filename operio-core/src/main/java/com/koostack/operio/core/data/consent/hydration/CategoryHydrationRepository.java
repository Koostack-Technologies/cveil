package com.koostack.operio.core.data.consent.hydration;

import com.koostack.operio.core.data.consent.aggregates.CategoryAggregate;

/**
 * Hydration repository for {@link CategoryAggregate} lookup by natural key.
 *
 * <p>Used by the command service to validate that referenced categories exist
 * before emitting consent events.
 */
public interface CategoryHydrationRepository {

    /**
     * Finds a category aggregate by its natural key (category name).
     *
     * @param categoryName the category name to look up
     * @return the hydrated aggregate, or {@code null} if no events exist
     */
    CategoryAggregate findByName(String categoryName);
}
