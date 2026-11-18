package com.forgemind.termuxbridge

/** Pure policy; the service supplies identities obtained from Android, never from IPC arguments. */
object CallerPolicy {
    fun allows(packages: Set<String>, releaseSigned: Boolean, debugBuild: Boolean, debugSigned: Boolean): Boolean =
        ("com.forgemind.app" in packages && releaseSigned) ||
            (debugBuild && debugSigned && packages.any { it == "com.forgemind.app" || it == "com.forgemind.bridgeprobe" })
}
