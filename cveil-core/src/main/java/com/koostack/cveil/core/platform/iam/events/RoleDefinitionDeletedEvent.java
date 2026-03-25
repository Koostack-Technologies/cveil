package com.koostack.cveil.core.platform.iam.events;

import com.koostack.cveil.core.event.DomainEvent;

/** Emitted when a custom role definition is deleted. */
public record RoleDefinitionDeletedEvent(String roleId, String tenantId) implements DomainEvent {}
