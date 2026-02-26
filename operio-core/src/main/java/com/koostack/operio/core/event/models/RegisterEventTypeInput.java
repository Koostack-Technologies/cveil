package com.koostack.operio.core.event.models;

public record RegisterEventTypeInput<U>(
    String name,
    String displayName,
    Class<U> eventClass
) {}
