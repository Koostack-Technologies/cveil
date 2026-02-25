package com.koostack.operio.core.models;

import java.util.UUID;

public interface AggregateRoot {

    UUID aggregateId();

    String aggregateType();
}
