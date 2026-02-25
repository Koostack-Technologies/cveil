package com.koostack.operio.core.models;

import java.util.UUID;

public interface AggregateRoot {
    String tenantId();
    UUID aggregateId();
    String aggregateType();
    Long version();

}
