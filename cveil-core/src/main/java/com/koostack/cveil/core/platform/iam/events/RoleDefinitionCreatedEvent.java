package com.koostack.cveil.core.platform.iam.events;

import com.koostack.cveil.core.event.DomainEvent;
import java.util.List;

/** Emitted when a custom role definition is created for a tenant. */
public record RoleDefinitionCreatedEvent(
        String roleId,
        String tenantId,
        String scope,
        String displayName,
        String description,
        boolean isSystem,
        boolean isAssignable,
        List<String> permissions)
        implements DomainEvent {}
