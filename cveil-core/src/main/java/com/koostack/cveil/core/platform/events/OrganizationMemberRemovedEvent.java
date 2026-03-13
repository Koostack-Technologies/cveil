package com.koostack.cveil.core.platform.events;

import com.koostack.cveil.core.event.DomainEvent;

/**
 * Domain event published when a user is removed from a Keycloak Organization.
 *
 * @param orgId the Keycloak organization ID
 * @param orgName the organization name (tenant identifier)
 * @param userId the Keycloak user ID
 */
public record OrganizationMemberRemovedEvent(String orgId, String orgName, String userId)
        implements DomainEvent {}
