package com.forgemind.app

import com.forgemind.app.license.LicenseManager
import com.forgemind.app.license.LicenseNetworkClient
import com.forgemind.app.license.LicenseRejectReason
import com.forgemind.app.license.LicenseStatus
import com.forgemind.app.license.LicenseTokenStorage
import com.forgemind.app.license.LicenseVerificationResult
import com.forgemind.app.license.LicenseVerifier
import com.forgemind.app.license.OnlineKeyResult
import com.forgemind.app.license.OnlineVerifyResult
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LicenseVerifierAndManagerTest {

    private class InMemoryStorage(var saved: String? = null) : LicenseTokenStorage {
        override fun getToken(): String? = saved
        override fun setToken(token: String?) { saved = token }
    }

    private fun generateKeyPair(): Pair<Ed25519PrivateKeyParameters, String> {
        val gen = Ed25519KeyPairGenerator()
        gen.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val kp = gen.generateKeyPair()
        val priv = kp.private as Ed25519PrivateKeyParameters
        val pub = kp.public as Ed25519PublicKeyParameters
        val pubB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(pub.encoded)
        return priv to pubB64
    }

    private fun signToken(
        privateKey: Ed25519PrivateKeyParameters,
        payloadJson: String,
        prefix: String = "SF1",
        signDecodedJsonInsteadOfMiddle: Boolean = false
    ): String {
        val middle = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payloadJson.toByteArray(Charsets.UTF_8))
        val bytesToSign = if (signDecodedJsonInsteadOfMiddle) {
            payloadJson.toByteArray(Charsets.UTF_8)
        } else {
            middle.toByteArray(Charsets.US_ASCII)
        }
        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        signer.update(bytesToSign, 0, bytesToSign.size)
        val sig = Base64.getUrlEncoder().withoutPadding().encodeToString(signer.generateSignature())
        return "$prefix.$middle.$sig"
    }

    @Test
    fun `embedded production public key decodes to 32 bytes and rejects forged signature`() {
        val verifier = LicenseVerifier()
        val (otherPriv, _) = generateKeyPair()
        val token = signToken(
            otherPriv,
            """{"v":1,"kid":"sf-2026-11","id":"PAY-1","email":"buyer@example.com","product":"forgemind","sku":"alpha","seats":3,"features":["*"],"iat":1795030000,"exp":null}"""
        )

        val result = verifier.verify(token, nowEpochSeconds = 1795030100L)
        assertTrue(result is LicenseVerificationResult.Invalid)
        assertEquals(LicenseRejectReason.INVALID_SIGNATURE, (result as LicenseVerificationResult.Invalid).reason)
    }

    @Test
    fun `valid wildcard perpetual SF1 token is accepted and unlocks any feature`() {
        val (priv, pubB64) = generateKeyPair()
        var now = 1795030100L
        val verifier = LicenseVerifier(
            trustedPublicKeysB64Url = mapOf("sf-2026-11" to pubB64),
            clockEpochSeconds = { now }
        )
        val token = signToken(
            priv,
            """{"v":1,"kid":"sf-2026-11","id":"PAY-42","email":"user@example.com","product":"forgemind","sku":"alpha","seats":3,"features":["*"],"iat":1795030000,"exp":null}"""
        )

        val result = verifier.verify("  $token \n")
        assertTrue(result is LicenseVerificationResult.Valid)
        val payload = (result as LicenseVerificationResult.Valid).payload
        assertEquals(1, payload.version)
        assertEquals("sf-2026-11", payload.kid)
        assertEquals("PAY-42", payload.id)
        assertEquals("user@example.com", payload.email)
        assertEquals("forgemind", payload.product)
        assertEquals("alpha", payload.sku)
        assertEquals(3, payload.seats)
        assertEquals(listOf("*"), payload.features)
        assertEquals(1795030000L, payload.iat)
        assertNull(payload.exp)
        assertTrue(payload.hasFeature("voice_realtime"))
        assertTrue(payload.hasFeature("anything_paid"))
        assertFalse(payload.hasFeature("   "))
    }

    @Test
    fun `named features unlock only explicitly listed features`() {
        val (priv, pubB64) = generateKeyPair()
        val verifier = LicenseVerifier(trustedPublicKeysB64Url = mapOf("sf-2026-11" to pubB64))
        val token = signToken(
            priv,
            """{"v":1,"kid":"sf-2026-11","id":"PAY-7","email":"a@b.c","product":"forgemind","sku":"pro","seats":3,"features":["cloud_sync","pro_themes"],"iat":1795030000,"exp":null}"""
        )

        val result = verifier.verify(token, nowEpochSeconds = 1795030100L) as LicenseVerificationResult.Valid
        assertTrue(result.payload.hasFeature("cloud_sync"))
        assertTrue(result.payload.hasFeature("pro_themes"))
        assertFalse(result.payload.hasFeature("realtime_voice"))
    }

    @Test
    fun `signature computed over decoded JSON instead of base64url middle bytes is rejected`() {
        val (priv, pubB64) = generateKeyPair()
        val verifier = LicenseVerifier(trustedPublicKeysB64Url = mapOf("sf-2026-11" to pubB64))
        val token = signToken(
            priv,
            """{"v":1,"kid":"sf-2026-11","id":"PAY-9","email":"a@b.c","product":"forgemind","sku":"alpha","seats":3,"features":["*"],"iat":1795030000,"exp":null}""",
            signDecodedJsonInsteadOfMiddle = true
        )

        val result = verifier.verify(token, nowEpochSeconds = 1795030100L)
        assertEquals(LicenseRejectReason.INVALID_SIGNATURE, (result as LicenseVerificationResult.Invalid).reason)
    }

    @Test
    fun `rejects wrong prefix, wrong version, wrong product, wrong kid, empty features, tampered middle, and expired exp`() {
        val (priv, pubB64) = generateKeyPair()
        val verifier = LicenseVerifier(trustedPublicKeysB64Url = mapOf("sf-2026-11" to pubB64))
        val now = 1795030100L

        val wrongPrefix = signToken(
            priv,
            """{"v":1,"kid":"sf-2026-11","id":"1","email":"a@b.c","product":"forgemind","sku":"alpha","seats":3,"features":["*"],"iat":1795030000,"exp":null}""",
            prefix = "SF2"
        )
        assertEquals(LicenseRejectReason.BAD_PREFIX, (verifier.verify(wrongPrefix, now) as LicenseVerificationResult.Invalid).reason)

        val wrongVersion = signToken(
            priv,
            """{"v":2,"kid":"sf-2026-11","id":"1","email":"a@b.c","product":"forgemind","sku":"alpha","seats":3,"features":["*"],"iat":1795030000,"exp":null}"""
        )
        assertEquals(LicenseRejectReason.UNSUPPORTED_VERSION, (verifier.verify(wrongVersion, now) as LicenseVerificationResult.Invalid).reason)

        val wrongProduct = signToken(
            priv,
            """{"v":1,"kid":"sf-2026-11","id":"1","email":"a@b.c","product":"otherapp","sku":"alpha","seats":3,"features":["*"],"iat":1795030000,"exp":null}"""
        )
        assertEquals(LicenseRejectReason.WRONG_PRODUCT, (verifier.verify(wrongProduct, now) as LicenseVerificationResult.Invalid).reason)

        val wrongKid = signToken(
            priv,
            """{"v":1,"kid":"sf-2099-01","id":"1","email":"a@b.c","product":"forgemind","sku":"alpha","seats":3,"features":["*"],"iat":1795030000,"exp":null}"""
        )
        assertEquals(LicenseRejectReason.UNKNOWN_KEY_ID, (verifier.verify(wrongKid, now) as LicenseVerificationResult.Invalid).reason)

        val emptyFeatures = signToken(
            priv,
            """{"v":1,"kid":"sf-2026-11","id":"1","email":"a@b.c","product":"forgemind","sku":"alpha","seats":3,"features":[],"iat":1795030000,"exp":null}"""
        )
        assertEquals(LicenseRejectReason.EMPTY_FEATURES, (verifier.verify(emptyFeatures, now) as LicenseVerificationResult.Invalid).reason)

        val expired = signToken(
            priv,
            """{"v":1,"kid":"sf-2026-11","id":"1","email":"a@b.c","product":"forgemind","sku":"alpha","seats":3,"features":["*"],"iat":1795030000,"exp":1795030100}"""
        )
        assertEquals(LicenseRejectReason.EXPIRED, (verifier.verify(expired, now) as LicenseVerificationResult.Invalid).reason)

        val badExpType = signToken(
            priv,
            """{"v":1,"kid":"sf-2026-11","id":"1","email":"a@b.c","product":"forgemind","sku":"alpha","seats":3,"features":["*"],"iat":1795030000,"exp":"never"}"""
        )
        assertEquals(LicenseRejectReason.INVALID_PAYLOAD, (verifier.verify(badExpType, now) as LicenseVerificationResult.Invalid).reason)

        val good = signToken(
            priv,
            """{"v":1,"kid":"sf-2026-11","id":"1","email":"a@b.c","product":"forgemind","sku":"alpha","seats":3,"features":["*"],"iat":1795030000,"exp":null}"""
        )
        val parts = good.split('.').toMutableList()
        val flippedChar = if (parts[1].first() == 'A') 'B' else 'A'
        parts[1] = flippedChar + parts[1].drop(1)
        assertEquals(
            LicenseRejectReason.INVALID_SIGNATURE,
            (verifier.verify(parts.joinToString("."), now) as LicenseVerificationResult.Invalid).reason
        )
    }

    @Test
    fun `license manager persists valid token, enforces expiry live, and preserves active key on bad activation`() {
        val (priv, pubB64) = generateKeyPair()
        var now = 1795030100L
        val storage = InMemoryStorage()
        val verifier = LicenseVerifier(
            trustedPublicKeysB64Url = mapOf("sf-2026-11" to pubB64),
            clockEpochSeconds = { now }
        )
        val manager = LicenseManager(
            storage = storage,
            verifier = verifier,
            clockEpochSeconds = { now }
        )

        assertEquals(LicenseStatus.NONE, manager.state.value.status)
        assertFalse(manager.hasFeature("pro"))

        val expiringToken = signToken(
            priv,
            """{"v":1,"kid":"sf-2026-11","id":"PAY-100","email":"test@example.com","product":"forgemind","sku":"alpha","seats":3,"features":["*"],"iat":1795030000,"exp":1795030500}"""
        )
        assertTrue(manager.activate(expiringToken) is LicenseVerificationResult.Valid)
        assertEquals(expiringToken, storage.saved)
        assertTrue(manager.isActive)
        assertTrue(manager.hasFeature("pro"))

        // Bad activation must NOT overwrite the existing valid license.
        assertTrue(manager.activate("SF1.broken.sig") is LicenseVerificationResult.Invalid)
        assertEquals(expiringToken, storage.saved)
        assertTrue(manager.hasFeature("pro"))

        // Advance clock past exp — live hasFeature immediately turns false and updates state.
        now = 1795030500L
        assertFalse(manager.hasFeature("pro"))
        assertEquals(LicenseStatus.EXPIRED, manager.state.value.status)

        // Clear removes persisted key.
        manager.clear()
        assertNull(storage.saved)
        assertEquals(LicenseStatus.NONE, manager.state.value.status)
    }

    @Test
    fun `optional online verify and key endpoints work via MockWebServer without overriding local validity`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val (priv, pubB64) = generateKeyPair()
            val token = signToken(
                priv,
                """{"v":1,"kid":"sf-2026-11","id":"PAY-55","email":"buyer@example.com","product":"forgemind","sku":"alpha","seats":3,"features":["*"],"iat":1795030000,"exp":null}"""
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"ok":true,"payload":{"v":1,"kid":"sf-2026-11","product":"forgemind","sku":"alpha","features":["*"]}}""")
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"kid":"sf-2026-11","publicKey":"$pubB64"}""")
            )

            val networkClient = LicenseNetworkClient(
                verifyUrl = server.url("/api/license/verify").toString(),
                keyUrl = server.url("/api/license/key").toString()
            )
            val storage = InMemoryStorage()
            val manager = LicenseManager(
                storage = storage,
                verifier = LicenseVerifier(trustedPublicKeysB64Url = mapOf("sf-2026-11" to pubB64)),
                networkClient = networkClient
            )
            assertTrue(manager.activate(token) is LicenseVerificationResult.Valid)

            val online = manager.verifyOnline()
            assertTrue(online is OnlineVerifyResult.Verified)
            assertNotNull((online as OnlineVerifyResult.Verified).payloadJson)
            val verifyReq = server.takeRequest()
            assertEquals("POST", verifyReq.method)
            assertEquals("/api/license/verify", verifyReq.path)
            assertTrue(verifyReq.body.readUtf8().contains(token))

            val keyRes = networkClient.fetchPublicKey()
            assertTrue(keyRes is OnlineKeyResult.Success)
            assertEquals("sf-2026-11", (keyRes as OnlineKeyResult.Success).kid)
            assertEquals(pubB64, keyRes.publicKey)
            val keyReq = server.takeRequest()
            assertEquals("GET", keyReq.method)
            assertEquals("/api/license/key", keyReq.path)
        } finally {
            server.shutdown()
        }
    }
}
