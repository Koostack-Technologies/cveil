package com.koostack.cveil.core.event.models;

import com.koostack.cveil.core.models.AggregateKey;

public record AggregateSnapshotTrigger(
    AggregateKey aggregateKey,
    Long targetVersion
) {}
