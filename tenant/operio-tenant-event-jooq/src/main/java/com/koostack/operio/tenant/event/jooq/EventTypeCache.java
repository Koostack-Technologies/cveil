package com.koostack.operio.tenant.event.jooq;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Caffeine-backed cache of valid event type names loaded from the
 * {@code event_type} table.
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

    public boolean isValid(String eventTypeName) {
        Set<String> validTypes = cache.get("ALL");
        return validTypes != null && validTypes.contains(eventTypeName);
    }

    public void refresh() {
        cache.invalidate("ALL");
        cache.get("ALL");
    }
}
