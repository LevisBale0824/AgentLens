package com.agentlens.adapters;

import com.agentlens.core.AdapterRegistry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdapterRegistryTest {

    @Test
    void discoversMockAdapterViaSpi() {
        AdapterRegistry registry = AdapterRegistry.load();
        // The adapters module is a test dependency, so at least mock/cli/http must be discovered.
        assertTrue(registry.names().contains("mock"), "available: " + registry.names());
        assertEquals("mock", registry.get("mock").name());
    }

    @Test
    void blankNameFallsBackToDefault() {
        AdapterRegistry registry = AdapterRegistry.load();
        assertEquals(registry.defaultAdapter(), registry.get(null));
        assertEquals(registry.defaultAdapter(), registry.get(""));
    }

    @Test
    void unknownNameThrowsWithAvailableList() {
        AdapterRegistry registry = AdapterRegistry.load();
        var ex = assertThrows(IllegalArgumentException.class, () -> registry.get("nope"));
        assertTrue(ex.getMessage().contains("mock"));
    }
}
