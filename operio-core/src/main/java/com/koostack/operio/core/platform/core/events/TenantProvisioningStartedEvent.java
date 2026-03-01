package com.koostack.operio.core.platform.core.events;

import com.koostack.operio.core.event.DomainEvent;

/**
 * Emitted after {@code createTenant} successfully creates the tenant,
 * billing profile, and subscription. Signals that async infrastructure
 * provisioning (schemas, Redis ACL, API keys) should begin.
 */
public record TenantProvisioningStartedEvent(
    String tenantId,
    String displayName,
    String plan
) implements DomainEvent {}
