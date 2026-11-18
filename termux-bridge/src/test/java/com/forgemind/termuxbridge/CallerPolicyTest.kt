package com.forgemind.termuxbridge

import org.junit.Assert.*
import org.junit.Test

class CallerPolicyTest {
    @Test fun releaseAcceptsOwnerCertificate() = assertTrue(CallerPolicy.allows(setOf("com.forgemind.app"), true, false, false))
    @Test fun releaseRejectsDebugCertificate() = assertFalse(CallerPolicy.allows(setOf("com.forgemind.app"), false, false, true))
    @Test fun releaseRejectsProbeEvenWithOwnerCertificate() = assertFalse(CallerPolicy.allows(setOf("com.forgemind.bridgeprobe"), true, false, false))
    @Test fun wrongCertificateIsDenied() = assertFalse(CallerPolicy.allows(setOf("com.forgemind.app"), false, true, false))
    @Test fun arbitraryPackageIsDenied() = assertFalse(CallerPolicy.allows(setOf("com.other.app"), true, true, true))
    @Test fun termuxSharedUidIsNotTrusted() = assertFalse(CallerPolicy.allows(setOf("com.termux", "com.forgemind.termuxbridge"), false, true, false))
    @Test fun debugProbeRequiresDebugBuild() = assertTrue(CallerPolicy.allows(setOf("com.forgemind.bridgeprobe"), false, true, true))
    @Test fun unknownUidIsDenied() = assertFalse(CallerPolicy.allows(emptySet(), false, false, false))
}
