package com.koostack.operio.core.platform.events;

import com.koostack.operio.core.event.DomainEvent;

/**
 * Domain event published when Redis infrastructure (stream, ACL, pub/sub) is provisioned for a
 * tenant/organization.
 *
 * @param tenantId the tenant identifier
 * @param streamKey the Redis stream key created (e.g., {@code operio:stream:tenant:{tenantId}})
 * @param pubSubChannel the pub/sub channel created (e.g., {@code
 *     consent-cache-invalidation:{tenantId}})
 * @param aclUser the Redis ACL user created for the tenant (nullable if ACLs are disabled)
 * @param aclPassword the Redis ACL password (nullable if ACLs are disabled)
 */
public record RedisProvisionedEvent(
        String tenantId, String streamKey, String pubSubChannel, String aclUser, String aclPassword)
        implements DomainEvent {

    /** Backward-compatible constructor without password. */
    public RedisProvisionedEvent(
            String tenantId, String streamKey, String pubSubChannel, String aclUser) {
        this(tenantId, streamKey, pubSubChannel, aclUser, null);
    }
}
