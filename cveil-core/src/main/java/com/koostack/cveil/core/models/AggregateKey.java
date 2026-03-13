package com.koostack.cveil.core.models;

import java.util.UUID;

public record AggregateKey(
    UUID aggregateId,
    String aggregateType,
    String tenantId
) {}
