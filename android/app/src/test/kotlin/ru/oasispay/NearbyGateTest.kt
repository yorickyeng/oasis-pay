package ru.oasispay

import org.junit.Assert.*
import org.junit.Test
import ru.oasispay.data.ble.NearbyGate

class NearbyGateTest {
    @Test fun requiresFreshConsecutiveStrongObservations() {
        val gate = NearbyGate()
        assertFalse(gate.observe(-34, 100))
        assertFalse(gate.observe(-55, 200))
        assertFalse(gate.observe(-41, 300))
        assertFalse(gate.observe(-42, 401))
        assertTrue(gate.observe(-43, 520))
        assertFalse(gate.observe(-40, 2000))
        assertFalse(gate.observe(127, 2100))
        assertFalse(gate.observe(-40, 2200))
    }
}
