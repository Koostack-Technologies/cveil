package com.koostack.operio.core.platform.events;

import com.koostack.operio.core.event.DomainEvent;

/**
 * Domain event published when a Keycloak Organization (tenant) is deleted.
 *
 * @param orgId the Keycloak organization ID
 * @param orgName the organization name (tenant identifier)
 */
public record OrganizationDeletedEvent(String orgId, String orgName) implements DomainEvent {}
