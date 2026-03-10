package com.koostack.operio.core.platform.events;

import com.koostack.operio.core.event.DomainEvent;

/**
 * Domain event published when a user is added as a member of a Keycloak Organization.
 *
 * @param orgId the Keycloak organization ID
 * @param orgName the organization name (tenant identifier)
 * @param userId the Keycloak user ID
 * @param username the user's login name
 */
public record OrganizationMemberAddedEvent(
        String orgId, String orgName, String userId, String username) implements DomainEvent {}
