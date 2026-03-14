package com.koostack.cveil.core.models;

import java.time.Instant;
import java.util.UUID;

public record SubjectView(
        UUID subjectId,
        String subjectName,
        String description,
        Instant createdAt,
        String createdBy,
        Instant updatedAt,
        String updatedBy) {}
