package com.koostack.cveil.core.cqrs;

/**
 * Generic query dispatcher that routes query objects to their registered {@link Actuator}.
 *
 * <p>Implementations maintain an internal registry of {@code (QueryType → Actuator)} mappings.
 * Callers submit a query record and receive the actuator's typed result. This is the read-side
 * counterpart to {@link CommandDispatcher}.
 *
 * <p>This is the SPI that concrete dispatchers (e.g. {@code KeycloakIamQueryDispatcher}) implement.
 * Each domain may have its own dispatcher instance with its own set of registered actuators.
 */
public interface QueryDispatcher {

    /**
     * Dispatch a query to its registered actuator and return the result.
     *
     * <p>The dispatcher looks up the actuator by the runtime class of {@code input}, invokes {@link
     * Actuator#execute(Object)}, and returns the result cast to {@code O}.
     *
     * @param input the query record (must match a registered actuator's input type)
     * @param <I> the query input type
     * @param <O> the result type produced by the actuator
     * @return the actuator's result
     * @throws IllegalArgumentException if no actuator is registered for the query type
     */
    <I, O> O dispatch(I input);
}
