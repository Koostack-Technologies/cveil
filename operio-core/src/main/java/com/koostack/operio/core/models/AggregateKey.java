package com.koostack.operio.core.models;

import java.util.UUID;

public record AggregateKey(
    UUID aggregateId,
    String aggregateType,
    String tenantId
) {}
