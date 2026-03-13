package com.koostack.operio.core.cqrs;

/**
 * Base actuator SPI — a single-responsibility unit of work that processes an input and returns a
 * result.
 *
 * <p>Concrete sub-interfaces ({@link CommandActuator}, {@link QueryActuator}) add additional
 * contracts for accessing infrastructure services such as event publishing and metrics.
 *
 * @param <I> the input type
 * @param <O> the result type produced by the actuator
 */
public interface Actuator<I, O> {
    O execute(I input);
}
