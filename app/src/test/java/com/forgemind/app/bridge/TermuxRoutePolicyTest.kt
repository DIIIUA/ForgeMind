package com.forgemind.app.bridge
import org.junit.Assert.*
import org.junit.Test

class TermuxRoutePolicyTest {
    @Test fun localEnabledProfileUsesBridge() { assertTrue(TermuxRoutePolicy.localBridgeTarget("127.0.0.1", false,true)) }
    @Test fun remoteHostIsNeverRedirectedToLocalTermux() { assertFalse(TermuxRoutePolicy.localBridgeTarget("server.example",false,true)) }
    @Test fun cloudflareLoopbackTunnelRemainsSsh() { assertFalse(TermuxRoutePolicy.localBridgeTarget("localhost",true,true)) }
    @Test fun disabledProfileIsNotActivatedByBridge() { assertFalse(TermuxRoutePolicy.localBridgeTarget("127.0.0.1",false,false)) }
    @Test fun preflightFailureAllowsFallback() { assertTrue(TermuxRoutePolicy.allowFallback(false)) }
    @Test fun uncertainDispatchNeverAllowsFallback() { assertFalse(TermuxRoutePolicy.allowFallback(true)) }
    @Test fun unconfiguredExternalProfileIsNotLocal() { assertFalse(TermuxRoutePolicy.localBridgeTarget("",false,true)) }
    @Test fun blankDefaultTermuxProfileIsLocal() { assertTrue(TermuxRoutePolicy.localBridgeTarget("",false,true,true)) }
    @Test fun defaultProfileWithRemoteHostIsNotLocal() { assertFalse(TermuxRoutePolicy.localBridgeTarget("remote.example",false,true,true)) }
    @Test fun termuxNamedRemoteProfileCannotFallBackLocally() {
        val bridge = TermuxBridge(android.content.ContextWrapper(null))
        assertFalse(bridge.isLocalDeviceProfile(TermuxBridge.defaultTermuxProfile().copy(name="Termux",host="remote.example")))
    }
}
