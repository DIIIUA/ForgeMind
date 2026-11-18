package com.forgemind.app.setup

import org.junit.Assert.*
import org.junit.Test

class LocalAdbPolicyTest {
    @Test fun preservesOtherAccessibilityServicesAndAddsOnlyOnce() {
        val own = "com.example.app/com.example.app.Service"
        val before = "com.other/.Service:com.third/.Reader"
        val after = LocalAdbPolicy.accessibilityServices(before, own)
        assertEquals("$before:$own", after)
        assertEquals(after, LocalAdbPolicy.accessibilityServices(after, own))
    }
    @Test fun handlesAndroidNullSetting() {
        assertEquals("com.example/.Service", LocalAdbPolicy.accessibilityServices("null\n", "com.example/.Service"))
    }
    @Test fun rejectsWrongPairingInputs() {
        for (p in listOf("0", "65536", "1;id", "")) assertTrue(runCatching { LocalAdbPolicy.port(p) }.isFailure)
        for (c in listOf("12345", "1234567", "12a456", "123;id")) assertTrue(runCatching { LocalAdbPolicy.pairingCode(c) }.isFailure)
        assertEquals("012345", LocalAdbPolicy.pairingCode("012345"))
    }
    @Test fun rejectsMalformedAccessibilityComponent() {
        assertTrue(runCatching { LocalAdbPolicy.accessibilityServices("", "x;id") }.isFailure)
    }
}
