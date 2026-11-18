package com.forgemind.termuxbridge
import org.junit.Assert.*
import org.junit.Test

class PtyProcessIdentityTest {
    @Test fun foregroundJobReceivesSignal() { assertEquals(-456,PtyProcessIdentity(123,34816,456,"999").signalTarget(123,"999")) }
    @Test fun recycledPidIsRejected() { assertNull(PtyProcessIdentity(123,34816,456,"1000").signalTarget(123,"999")) }
    @Test fun foreignSessionIsRejected() { assertNull(PtyProcessIdentity(321,34816,456,"999").signalTarget(123,"999")) }
    @Test fun processWithoutTerminalIsRejected() { assertNull(PtyProcessIdentity(123,0,456,"999").signalTarget(123,"999")) }
    @Test fun procCommandWithParenthesesDoesNotShiftIdentityFields() {
        val fields=MutableList(20) { "0" }; fields[0]="S"; fields[3]="123"; fields[4]="34816"; fields[5]="456"; fields[19]="999"
        assertEquals(PtyProcessIdentity(123,34816,456,"999"),PtyProcessIdentity.parse("123 (command ) with spaces) "+fields.joinToString(" ")))
    }
}
