package com.koostack.operio.common.registry;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.koostack.operio.core.platform.core.CoreEventTypes;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TenantRegistryEventHandlingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void initialTenantsAreActive() {
        var registry = new TenantRegistry(Set.of("t1", "t2"), null);
        assertEquals(Set.of("t1", "t2"), registry.activeTenants());
        assertTrue(registry.isActive("t1"));
        assertTrue(registry.isActive("t2"));
        assertFalse(registry.isActive("t3"));
    }

    @Test
    void emptyInitialSet() {
        var registry = new TenantRegistry(Set.of(), null);
        assertTrue(registry.activeTenants().isEmpty());
    }

    @Test
    void tenantProvisionedAddsToActiveSet() throws Exception {
        var registry = new TenantRegistry(Set.of(), null);
        registry.onPlatformEvent(
                CoreEventTypes.TENANT_PROVISIONED, payload("new_tenant"));
        assertTrue(registry.isActive("new_tenant"));
        assertEquals(1, registry.activeTenants().size());
    }

    @Test
    void tenantActivatedAddsToActiveSet() throws Exception {
        var registry = new TenantRegistry(Set.of(), null);
        registry.onPlatformEvent(
                CoreEventTypes.TENANT_ACTIVATED, payload("reactivated"));
        assertTrue(registry.isActive("reactivated"));
    }

    @Test
    void tenantSuspendedRemovesFromActiveSet() throws Exception {
        var registry = new TenantRegistry(Set.of("victim"), null);
        assertTrue(registry.isActive("victim"));
        registry.onPlatformEvent(
                CoreEventTypes.TENANT_SUSPENDED, payload("victim"));
        assertFalse(registry.isActive("victim"));
        assertTrue(registry.activeTenants().isEmpty());
    }

    @Test
    void tenantDeletedRemovesFromActiveSet() throws Exception {
        var registry = new TenantRegistry(Set.of("doomed"), null);
        registry.onPlatformEvent(
                CoreEventTypes.TENANT_DELETED, payload("doomed"));
        assertFalse(registry.isActive("doomed"));
    }

    @Test
    void suspendThenReactivate() throws Exception {
        var registry = new TenantRegistry(Set.of("flipflop"), null);
        registry.onPlatformEvent(
                CoreEventTypes.TENANT_SUSPENDED, payload("flipflop"));
        assertFalse(registry.isActive("flipflop"));
        registry.onPlatformEvent(
                CoreEventTypes.TENANT_ACTIVATED, payload("flipflop"));
        assertTrue(registry.isActive("flipflop"));
    }

    @Test
    void unknownEventTypeIsIgnored() throws Exception {
        var registry = new TenantRegistry(Set.of("stable"), null);
        registry.onPlatformEvent("core.tenant.display-name-updated", payload("stable"));
        assertTrue(registry.isActive("stable"));
        assertEquals(1, registry.activeTenants().size());
    }

    @Test
    void nullEventTypeIsIgnored() {
        var registry = new TenantRegistry(Set.of("stable"), null);
        registry.onPlatformEvent(null, "{}");
        assertTrue(registry.isActive("stable"));
    }

    @Test
    void nullPayloadDoesNotCrash() {
        var registry = new TenantRegistry(Set.of(), null);
        registry.onPlatformEvent(CoreEventTypes.TENANT_PROVISIONED, null);
        assertTrue(registry.activeTenants().isEmpty());
    }

    @Test
    void malformedPayloadDoesNotCrash() {
        var registry = new TenantRegistry(Set.of(), null);
        registry.onPlatformEvent(CoreEventTypes.TENANT_PROVISIONED, "not json");
        assertTrue(registry.activeTenants().isEmpty());
    }

    @Test
    void payloadMissingTenantIdFieldIsIgnored() {
        var registry = new TenantRegistry(Set.of(), null);
        registry.onPlatformEvent(CoreEventTypes.TENANT_PROVISIONED, "{\"other\": \"value\"}");
        assertTrue(registry.activeTenants().isEmpty());
    }

    @Test
    void suspendingUnknownTenantIsNoOp() throws Exception {
        var registry = new TenantRegistry(Set.of("only_one"), null);
        registry.onPlatformEvent(
                CoreEventTypes.TENANT_SUSPENDED, payload("nonexistent"));
        assertEquals(Set.of("only_one"), registry.activeTenants());
    }

    @Test
    void duplicateProvisioningIsIdempotent() throws Exception {
        var registry = new TenantRegistry(Set.of("existing"), null);
        registry.onPlatformEvent(
                CoreEventTypes.TENANT_PROVISIONED, payload("existing"));
        assertEquals(1, registry.activeTenants().size());
        assertTrue(registry.isActive("existing"));
    }

    @Test
    void activeTenantSetIsUnmodifiable() {
        var registry = new TenantRegistry(Set.of("t1"), null);
        Set<String> result = registry.activeTenants();
        assertThrows(UnsupportedOperationException.class, () -> result.add("hacked"));
    }

    @Test
    void concurrentUpdatesAreThreadSafe() throws Exception {
        var registry = new TenantRegistry(Set.of(), null);
        var threads = new Thread[10];
        for (int i = 0; i < threads.length; i++) {
            final int idx = i;
            threads[i] = new Thread(() -> {
                try {
                    registry.onPlatformEvent(
                            CoreEventTypes.TENANT_PROVISIONED, payload("tenant_" + idx));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();
        assertEquals(10, registry.activeTenants().size());
    }

    private static String payload(String tenantId) throws Exception {
        return MAPPER.writeValueAsString(java.util.Map.of("tenantId", tenantId));
    }
}
