package com.koostack.operio.core.tenant.consent.invalidation;

/**
 * Pushes an invalidation message to a connected client.
 *
 * <p>Implementations may be SSE emitters, WebSocket sessions, in-memory
 * callbacks, or any other transport.  The subscriber layer never knows
 * about the transport — it only invokes {@link #send}.
 *
 * <p>Implementations <b>must</b> be safe for concurrent invocation from the
 * subscriber's message-dispatch thread.
 */
@FunctionalInterface
public interface InvalidationSink {

    /**
     * Deliver an invalidation message to the client.
     *
     * @param message the cache invalidation notification to send
     * @throws Exception if the delivery fails (the subscriber will catch and
     *                   log; a persistently-failing sink may be removed)
     */
    void send(CacheInvalidationMessage message) throws Exception;
}
