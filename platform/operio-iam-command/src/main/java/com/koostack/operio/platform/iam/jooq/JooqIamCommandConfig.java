package com.koostack.operio.platform.iam.jooq;

import com.koostack.operio.core.event.EventService;
import javax.sql.DataSource;

/**
 * Configuration for {@link JooqIamCommandService}.
 *
 * @param eventService the platform event service for publishing domain events
 * @param platformDataSource optional DataSource for legacy API key operations
 */
public record JooqIamCommandConfig(EventService<?> eventService, DataSource platformDataSource) {
    public JooqIamCommandConfig {
        if (eventService == null) {
            throw new IllegalArgumentException("eventService must not be null");
        }
    }

    public JooqIamCommandConfig(EventService<?> eventService) {
        this(eventService, null);
    }
}
