package com.koostack.operio.core.platform.events;

import com.koostack.operio.core.event.DomainEvent;

/**
 * Domain event published when a personal access token is revoked.
 *
 * @param tenantId the tenant the token was scoped to
 * @param tokenPrefix the visible prefix identifying the revoked token
 */
public record AccessTokenRevokedEvent(String tenantId, String tokenPrefix) implements DomainEvent {}
