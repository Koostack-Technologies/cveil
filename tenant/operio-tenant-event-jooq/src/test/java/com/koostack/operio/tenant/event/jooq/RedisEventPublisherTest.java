package com.koostack.operio.tenant.event.jooq;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RedisEventPublisher} sharding logic.
 */
class RedisEventPublisherTest {

    @Test
    void sameInputAlwaysReturnsSameKey() {
        String tenantId = "tenant-abc-123";
        String first = RedisEventPublisher.streamKeyForTenant(tenantId);
        String second = RedisEventPublisher.streamKeyForTenant(tenantId);
        assertEquals(first, second, "Same tenant ID must always map to the same stream key");
    }

    @Test
    void resultAlwaysInValidRange() {
        String prefix = "operio:stream:domain:";
        for (int i = 0; i < 1_000; i++) {
            String tenantId = UUID.randomUUID().toString();
            String key = RedisEventPublisher.streamKeyForTenant(tenantId);
            assertTrue(key.startsWith(prefix), "Key must start with " + prefix);
            int index = Integer.parseInt(key.substring(prefix.length()));
            assertTrue(index >= 0 && index < RedisEventPublisher.SHARD_COUNT,
                "Partition index must be in [0, " + RedisEventPublisher.SHARD_COUNT + ")");
        }
    }

    @Test
    void nullInputThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> RedisEventPublisher.streamKeyForTenant(null));
    }

    @Test
    void keyHasExpectedPrefix() {
        assertTrue(RedisEventPublisher.streamKeyForTenant("x").startsWith("operio:stream:domain:"));
    }

    @Test
    void allStreamKeysReturnsCorrectCount() {
        List<String> keys = RedisEventPublisher.allStreamKeys();
        assertEquals(RedisEventPublisher.SHARD_COUNT, keys.size());
    }

    @Test
    void allStreamKeysAreUnique() {
        List<String> keys = RedisEventPublisher.allStreamKeys();
        Set<String> unique = new HashSet<>(keys);
        assertEquals(keys.size(), unique.size(), "All stream keys must be unique");
    }

    @Test
    void allStreamKeysHaveExpectedFormat() {
        List<String> keys = RedisEventPublisher.allStreamKeys();
        for (int i = 0; i < keys.size(); i++) {
            assertEquals("operio:stream:domain:" + i, keys.get(i));
        }
    }

    @Test
    void allStreamKeysIsUnmodifiable() {
        List<String> keys = RedisEventPublisher.allStreamKeys();
        assertThrows(UnsupportedOperationException.class, () -> keys.add("extra"));
    }
}
