package com.multivpn.app.engine

import com.multivpn.app.model.Protocol
import org.junit.Assert.*
import org.junit.Test

class EngineRegistryTest {
    @Test fun missingEngineIsNeverClaimedAvailable() {
        val registry = EngineRegistry()
        Protocol.entries.forEach { protocol ->
            assertFalse(registry.availableFor(protocol))
        }
    }
}
