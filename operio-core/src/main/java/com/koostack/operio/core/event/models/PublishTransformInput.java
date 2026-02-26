package com.koostack.operio.core.event.models;

import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.models.AggregateRoot;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Input for the atomic hydrate-transform-publish operation.
 *
 * @param tenantId     tenant owning the aggregate
 * @param eventType    registered event type name
 * @param key          aggregate key (id + type + tenant)
 * @param defaultState supplier for creating a new aggregate when no events exist
 * @param transform    function applied to the current (or default) state to
 *                     produce the updated state; runs inside the publish transaction
 * @param type         aggregate class for deserialization
 */
public record PublishTransformInput<T extends AggregateRoot>(
    String tenantId,
    String eventType,
    AggregateKey key,
    Supplier<T> defaultState,
    Function<T, T> transform,
    Class<T> type
) {
    public static <U extends AggregateRoot> PublishTransformInput<U> of(
        String tenantId,
        String eventType,
        AggregateKey key,
        Supplier<U> defaultState,
        Function<U, U> transform,
        Class<U> type
    ) {
        return new PublishTransformInput<>(tenantId, eventType, key, defaultState, transform, type);
    }
}
