package com.koostack.operio.tenant.metrics.redis;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TenantRedisMetricPublisher} stream key routing.
 */
class TenantRedisMetricPublisherTest {

    @Test
    void streamKeyContainsTenantId() {
        String key = TenantRedisMetricPublisher.streamKeyForTenant("acme_corp");
        assertEquals("operio:metrics:tenant:acme_corp", key);
    }

    @Test
    void sameInputAlwaysReturnsSameKey() {
        String tenantId = "tenant-abc-123";
        String first = TenantRedisMetricPublisher.streamKeyForTenant(tenantId);
        String second = TenantRedisMetricPublisher.streamKeyForTenant(tenantId);
        assertEquals(first, second);
    }

    @Test
    void keyHasExpectedPrefix() {
        for (int i = 0; i < 50; i++) {
            String tenantId = UUID.randomUUID().toString();
            String key = TenantRedisMetricPublisher.streamKeyForTenant(tenantId);
            assertTrue(key.startsWith(TenantRedisMetricPublisher.STREAM_KEY_PREFIX));
            assertEquals(tenantId, key.substring(TenantRedisMetricPublisher.STREAM_KEY_PREFIX.length()));
        }
    }

    @Test
    void nullInputThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> TenantRedisMetricPublisher.streamKeyForTenant(null));
    }

    @Test
    void differentTenantsGetDifferentKeys() {
        String key1 = TenantRedisMetricPublisher.streamKeyForTenant("tenant_a");
        String key2 = TenantRedisMetricPublisher.streamKeyForTenant("tenant_b");
        assertNotEquals(key1, key2);
    }

    @Test
    void metricStreamKeyIsSeparateFromEventStreamKey() {
        String metricKey = TenantRedisMetricPublisher.streamKeyForTenant("acme");
        // Metric keys use "operio:metrics:tenant:" prefix, not "operio:stream:tenant:"
        assertTrue(metricKey.startsWith("operio:metrics:tenant:"));
        assertFalse(metricKey.startsWith("operio:stream:tenant:"));
    }
}
