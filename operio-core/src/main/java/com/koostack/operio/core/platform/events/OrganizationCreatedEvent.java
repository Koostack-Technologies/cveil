package com.koostack.operio.core.platform.events;

import com.koostack.operio.core.event.DomainEvent;

/**
 * Domain event published when a Keycloak Organization (tenant) is created.
 *
 * @param orgId the Keycloak-assigned organization ID
 * @param orgName the organization name (used as tenant identifier)
 * @param displayName human-readable display name
 */
public record OrganizationCreatedEvent(String orgId, String orgName, String displayName)
        implements DomainEvent {}
