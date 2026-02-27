package com.koostack.operio.core.data.consent.invalidation;

/**
 * Subscribes to cache invalidation messages for one or more tenants and
 * routes them to registered {@link InvalidationSink}s.
 *
 * <p>Implementations are responsible for managing the underlying transport
 * (e.g., Redis Pub/Sub channels) and for reconnecting after failures.
 *
 * <p>Thread safety: all methods must be safe for concurrent invocation.
 */
public interface InvalidationSubscriber extends AutoCloseable {

    /**
     * Subscribe to invalidation messages for {@code tenantId} and route them
     * to the given sink.
     *
     * @param tenantId the tenant whose invalidation channel to subscribe to
     * @param sink     the consumer that will receive each message
     * @return a subscription handle that can be used to cancel the subscription
     */
    Subscription subscribe(String tenantId, InvalidationSink sink);

    /**
     * Cancel a specific subscription (convenience — equivalent to
     * {@code subscription.cancel()}).
     */
    void unsubscribe(Subscription subscription);

    /**
     * Handle returned by {@link #subscribe} representing a single
     * tenant+sink binding.
     */
    interface Subscription {

        /** The tenant this subscription is bound to. */
        String tenantId();

        /**
         * Cancel this subscription.  If this was the last sink for the
         * tenant's channel, the implementation may unsubscribe from the
         * underlying transport.
         */
        void cancel();
    }
}
