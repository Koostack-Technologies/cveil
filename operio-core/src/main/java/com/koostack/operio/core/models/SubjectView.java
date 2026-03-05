package com.koostack.operio.core.models;

import java.time.Instant;

public record SubjectView(
    long subjectId,
    String subjectName,
    String description,
    Instant createdAt,
    String createdBy,
    Instant updatedAt,
    String updatedBy
) {}
