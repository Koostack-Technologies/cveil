package com.koostack.operio.core.platform.core.events;

import com.koostack.operio.core.event.DomainEvent;

public record TenantProvisionedEvent(
    String tenantId,
    String schemaPrefix,
    String redisAclUser
) implements DomainEvent {}
