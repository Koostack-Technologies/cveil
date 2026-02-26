package com.koostack.operio.plugin.event.jooq;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Caffeine-backed cache of valid event type names loaded from the
 * {@code event_type} table.  The cache is keyed by event type name and
 * holds a boolean indicating whether that name exists.
 *
 * <p>A single {@code "ALL"} key is used internally to load the full set
 * of valid names, which is then consulted for membership checks.
 */
public class EventTypeCache {

    private static final Logger log = LoggerFactory.getLogger(EventTypeCache.class);

    private final LoadingCache<String, Set<String>> cache;

    public EventTypeCache(EventRepository eventRepository) {
        this.cache = Caffeine.newBuilder()
            .refreshAfterWrite(5, TimeUnit.MINUTES)
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .build(key -> {
                Set<String> types = eventRepository.fetchAllEventTypeNames();
                log.debug("Loaded {} event types from database", types.size());
                return types;
            });
    }

    /**
     * Returns {@code true} if the given event type name is registered in the
     * {@code event_type} table.
     */
    public boolean isValid(String eventTypeName) {
        Set<String> validTypes = cache.get("ALL");
        return validTypes != null && validTypes.contains(eventTypeName);
    }

    /**
     * Invalidates and synchronously reloads the cached event types from the
     * database.  This ensures callers see the latest state immediately after
     * a {@code registerEventType} call — no async race.
     */
    public void refresh() {
        cache.invalidate("ALL");
        cache.get("ALL");
    }
}
