package com.forgemind.app.bridge

import org.junit.Assert.*
import org.junit.Test

class TermuxCallbackStatusTest {
    @Test fun activityResultOkIsSuccessful() { assertFalse(TermuxCallbackStatus.failed(-1, null)) }
    @Test fun omittedErrorCodeIsSuccessful() { assertFalse(TermuxCallbackStatus.failed(0, "")) }
    @Test fun actualErrorCodeIsPreserved() { assertTrue(TermuxCallbackStatus.failed(7, null)) }
    @Test fun explicitErrorMessageIsPreservedEvenWithOkCode() { assertTrue(TermuxCallbackStatus.failed(-1, "denied")) }
}
