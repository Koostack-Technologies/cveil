package com.koostack.operio.core.models;

public record PlatformEvent<T>(
    String tenantId,
    String eventType,
    T payload
) {
}
