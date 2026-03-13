package com.koostack.cveil.core.cqrs;

/**
 * Generic command dispatcher that routes command objects to their registered {@link Actuator}.
 *
 * <p>Implementations maintain an internal registry of {@code (CommandType → Actuator)} mappings.
 * Callers submit a command record and receive the actuator's typed result. Infrastructure services
 * such as {@link com.koostack.cveil.core.event.EventService} and {@link
 * com.koostack.cveil.core.metrics.MetricPublisherService} are supplied to each actuator via its
 * constructor at registration time.
 *
 * <p>This is the SPI that concrete dispatchers (e.g. {@code KeycloakIamDispatcher}) implement. Each
 * domain may have its own dispatcher instance with its own set of registered actuators.
 */
public interface CommandDispatcher {

    /**
     * Dispatch a command to its registered actuator and return the result.
     *
     * <p>The dispatcher looks up the actuator by the runtime class of {@code input}, invokes {@link
     * Actuator#execute(Object)}, and returns the result cast to {@code O}.
     *
     * @param input the command record (must match a registered actuator's input type)
     * @param <I> the command input type
     * @param <O> the result type produced by the actuator
     * @return the actuator's result
     * @throws IllegalArgumentException if no actuator is registered for the command type
     */
    <I, O> O dispatch(I input);
}
