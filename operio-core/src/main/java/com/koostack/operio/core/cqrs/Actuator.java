package com.koostack.operio.core.cqrs;

import com.koostack.operio.core.event.EventService;

public interface Actuator<I, O> {
    O execute(EventService<?> publisher, I input);
}
