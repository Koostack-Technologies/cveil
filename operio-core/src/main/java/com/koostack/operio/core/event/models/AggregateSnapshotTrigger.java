package com.koostack.operio.core.event.models;

import com.koostack.operio.core.models.AggregateKey;

public record AggregateSnapshotTrigger(
    AggregateKey aggregateKey,
    Long targetVersion
) {}
