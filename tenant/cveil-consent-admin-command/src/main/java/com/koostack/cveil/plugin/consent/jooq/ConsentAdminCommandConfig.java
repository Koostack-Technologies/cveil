package com.koostack.cveil.plugin.consent.jooq;

import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.tenant.consent.SubjectDsarService;
import java.util.function.UnaryOperator;

/**
 * Configuration for the event-sourced consent admin command service.
 *
 * @param eventService the event service used to publish domain events
 * @param dsarService optional DSAR key persistence service (null in embedded mode)
 * @param keyEncryptor optional envelope encryption operator for per-subject DSAR keys (null in
 *     embedded mode)
 */
public record ConsentAdminCommandConfig(
        EventService<?> eventService,
        SubjectDsarService<?> dsarService,
        UnaryOperator<String> keyEncryptor) {

    /** Primary constructor — validates required fields. */
    public ConsentAdminCommandConfig {
        if (eventService == null) {
            throw new IllegalArgumentException("eventService must not be null");
        }
    }

    /** Backward-compatible constructor for callers that do not need DSAR support. */
    public ConsentAdminCommandConfig(EventService<?> eventService) {
        this(eventService, null, null);
    }
}
