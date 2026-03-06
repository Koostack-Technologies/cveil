package com.koostack.operio.tenant.event.jooq;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link RedisEventPublisher} per-tenant stream routing. */
class RedisEventPublisherTest {

    @Test
    void sameInputAlwaysReturnsSameKey() {
        String tenantId = "tenant-abc-123";
        String first = RedisEventPublisher.streamKeyForTenant(tenantId);
        String second = RedisEventPublisher.streamKeyForTenant(tenantId);
        assertEquals(first, second, "Same tenant ID must always map to the same stream key");
    }

    @Test
    void keyContainsTenantId() {
        String tenantId = "acme_corp";
        String key = RedisEventPublisher.streamKeyForTenant(tenantId);
        assertEquals("operio:stream:tenant:acme_corp", key);
    }

    @Test
    void keyHasExpectedPrefix() {
        for (int i = 0; i < 100; i++) {
            String tenantId = UUID.randomUUID().toString();
            String key = RedisEventPublisher.streamKeyForTenant(tenantId);
            assertTrue(
                    key.startsWith(RedisEventPublisher.STREAM_KEY_PREFIX),
                    "Key must start with " + RedisEventPublisher.STREAM_KEY_PREFIX);
            assertEquals(
                    tenantId,
                    key.substring(RedisEventPublisher.STREAM_KEY_PREFIX.length()),
                    "Key suffix must be the tenant ID");
        }
    }

    @Test
    void nullInputThrows() {
        assertThrows(
                IllegalArgumentException.class, () -> RedisEventPublisher.streamKeyForTenant(null));
    }

    @Test
    void differentTenantsGetDifferentKeys() {
        String key1 = RedisEventPublisher.streamKeyForTenant("tenant_a");
        String key2 = RedisEventPublisher.streamKeyForTenant("tenant_b");
        assertNotEquals(key1, key2, "Different tenants must get different stream keys");
    }
}
