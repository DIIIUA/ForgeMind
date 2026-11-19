package com.forgemind.app.license

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * Decoded and cryptographically verified payload of an SF1 license token.
 *
 * `seats` is informational only in v1 — device counting is intentionally not enforced here.
 */
data class LicensePayload(
    val version: Int,
    val kid: String,
    val id: String,
    val email: String,
    val product: String,
    val sku: String,
    val seats: Int?,
    val features: List<String>,
    val iat: Long?,
    val exp: Long?
) {
    fun isExpiredAt(nowEpochSeconds: Long): Boolean =
        exp != null && exp <= nowEpochSeconds

    fun hasFeature(feature: String): Boolean {
        val target = feature.trim()
        if (target.isEmpty()) return false
        return features.any { it == "*" || it == target }
    }
}

enum class LicenseRejectReason {
    EMPTY,
    MALFORMED_FORMAT,
    BAD_PREFIX,
    BAD_ENCODING,
    INVALID_SIGNATURE,
    INVALID_PAYLOAD,
    UNSUPPORTED_VERSION,
    WRONG_PRODUCT,
    UNKNOWN_KEY_ID,
    EMPTY_FEATURES,
    EXPIRED
}

sealed interface LicenseVerificationResult {
    data class Valid(
        val token: String,
        val payload: LicensePayload
    ) : LicenseVerificationResult

    data class Invalid(
        val reason: LicenseRejectReason,
        val detail: String = ""
    ) : LicenseVerificationResult
}

/**
 * Fully offline Ed25519 license verifier for tokens of the form:
 * `SF1.<base64url-json-payload>.<base64url-ed25519-signature>`.
 *
 * Signature is verified over the raw US-ASCII bytes of the base64url middle segment itself,
 * NOT over the decoded JSON bytes.
 */
class LicenseVerifier(
    private val trustedPublicKeysB64Url: Map<String, String> = mapOf(
        DEFAULT_KID to DEFAULT_PUBLIC_KEY_B64URL
    ),
    private val expectedProduct: String = EXPECTED_PRODUCT,
    private val expectedVersion: Int = EXPECTED_VERSION,
    private val clockEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000L }
) {
    private val parsedPublicKeys: Map<String, Ed25519PublicKeyParameters> =
        trustedPublicKeysB64Url.mapValues { (kid, b64) ->
            val raw = decodeBase64UrlStrict(b64)
                ?: throw IllegalArgumentException("Invalid base64url public key for kid=$kid")
            require(raw.size == ED25519_PUBLIC_KEY_BYTES) {
                "Ed25519 public key for kid=$kid must be $ED25519_PUBLIC_KEY_BYTES bytes, got ${raw.size}"
            }
            Ed25519PublicKeyParameters(raw, 0)
        }

    fun verify(rawToken: String, nowEpochSeconds: Long = clockEpochSeconds()): LicenseVerificationResult {
        val token = normalizeToken(rawToken)
        if (token.isEmpty()) {
            return LicenseVerificationResult.Invalid(LicenseRejectReason.EMPTY)
        }

        val parts = token.split('.')
        if (parts.size != 3) {
            return LicenseVerificationResult.Invalid(
                LicenseRejectReason.MALFORMED_FORMAT,
                "Expected 3 dot-separated parts"
            )
        }

        val (prefix, middle, sigPart) = parts
        if (prefix != TOKEN_PREFIX) {
            return LicenseVerificationResult.Invalid(
                LicenseRejectReason.BAD_PREFIX,
                "Expected prefix $TOKEN_PREFIX"
            )
        }
        if (middle.isEmpty() || sigPart.isEmpty()) {
            return LicenseVerificationResult.Invalid(
                LicenseRejectReason.MALFORMED_FORMAT,
                "Empty token segment"
            )
        }

        val payloadBytes = decodeBase64UrlStrict(middle)
            ?: return LicenseVerificationResult.Invalid(
                LicenseRejectReason.BAD_ENCODING,
                "Invalid base64url payload"
            )
        val signatureBytes = decodeBase64UrlStrict(sigPart)
            ?: return LicenseVerificationResult.Invalid(
                LicenseRejectReason.BAD_ENCODING,
                "Invalid base64url signature"
            )
        if (signatureBytes.size != ED25519_SIGNATURE_BYTES) {
            return LicenseVerificationResult.Invalid(
                LicenseRejectReason.INVALID_SIGNATURE,
                "Signature must be $ED25519_SIGNATURE_BYTES bytes"
            )
        }

        // Verify cryptographic signature against the raw ASCII bytes of the middle segment first,
        // before trusting any decoded JSON fields.
        val middleAsciiBytes = middle.toByteArray(Charsets.US_ASCII)
        val verifiedKid = parsedPublicKeys.entries.firstOrNull { (_, keyParams) ->
            verifyEd25519(keyParams, middleAsciiBytes, signatureBytes)
        }?.key ?: return LicenseVerificationResult.Invalid(
            LicenseRejectReason.INVALID_SIGNATURE,
            "Ed25519 signature verification failed"
        )

        val jsonText = runCatching { payloadBytes.toString(Charsets.UTF_8) }.getOrNull()
            ?: return LicenseVerificationResult.Invalid(
                LicenseRejectReason.INVALID_PAYLOAD,
                "Payload is not valid UTF-8"
            )

        val root = runCatching { json.parseToJsonElement(jsonText) as? JsonObject }.getOrNull()
            ?: return LicenseVerificationResult.Invalid(
                LicenseRejectReason.INVALID_PAYLOAD,
                "Payload is not a JSON object"
            )

        val version = strictInt(root["v"])
            ?: return LicenseVerificationResult.Invalid(
                LicenseRejectReason.UNSUPPORTED_VERSION,
                "Missing or non-integer v"
            )
        if (version != expectedVersion) {
            return LicenseVerificationResult.Invalid(
                LicenseRejectReason.UNSUPPORTED_VERSION,
                "Expected v=$expectedVersion, got $version"
            )
        }

        val kid = strictString(root["kid"])
            ?: return LicenseVerificationResult.Invalid(
                LicenseRejectReason.UNKNOWN_KEY_ID,
                "Missing kid"
            )
        if (kid != verifiedKid) {
            return LicenseVerificationResult.Invalid(
                LicenseRejectReason.UNKNOWN_KEY_ID,
                "Payload kid '$kid' does not match verified key '$verifiedKid'"
            )
        }

        val product = strictString(root["product"])
            ?: return LicenseVerificationResult.Invalid(
                LicenseRejectReason.WRONG_PRODUCT,
                "Missing product"
            )
        if (product != expectedProduct) {
            return LicenseVerificationResult.Invalid(
                LicenseRejectReason.WRONG_PRODUCT,
                "Expected product=$expectedProduct, got $product"
            )
        }

        val featuresElement = root["features"] as? JsonArray
            ?: return LicenseVerificationResult.Invalid(
                LicenseRejectReason.EMPTY_FEATURES,
                "Missing features array"
            )
        val features = mutableListOf<String>()
        for (item in featuresElement) {
            val featureName = strictString(item)?.trim()
                ?: return LicenseVerificationResult.Invalid(
                    LicenseRejectReason.INVALID_PAYLOAD,
                    "Feature entries must be strings"
                )
            if (featureName.isNotEmpty()) {
                features += featureName
            }
        }
        if (features.isEmpty()) {
            return LicenseVerificationResult.Invalid(
                LicenseRejectReason.EMPTY_FEATURES,
                "features must not be empty"
            )
        }

        val expElement = root["exp"]
        val exp: Long? = when {
            expElement == null || expElement is JsonNull -> null
            else -> strictLong(expElement)
                ?: return LicenseVerificationResult.Invalid(
                    LicenseRejectReason.INVALID_PAYLOAD,
                    "exp must be null or integer epoch seconds"
                )
        }

        val iatElement = root["iat"]
        val iat: Long? = when {
            iatElement == null || iatElement is JsonNull -> null
            else -> strictLong(iatElement)
                ?: return LicenseVerificationResult.Invalid(
                    LicenseRejectReason.INVALID_PAYLOAD,
                    "iat must be null or integer epoch seconds"
                )
        }

        val seatsElement = root["seats"]
        val seats: Int? = when {
            seatsElement == null || seatsElement is JsonNull -> null
            else -> strictInt(seatsElement)
                ?: return LicenseVerificationResult.Invalid(
                    LicenseRejectReason.INVALID_PAYLOAD,
                    "seats must be null or integer"
                )
        }

        val id = root["id"]?.let { el ->
            if (el is JsonNull) "" else strictString(el)
                ?: return LicenseVerificationResult.Invalid(LicenseRejectReason.INVALID_PAYLOAD, "id must be a string")
        } ?: ""

        val email = root["email"]?.let { el ->
            if (el is JsonNull) "" else strictString(el)
                ?: return LicenseVerificationResult.Invalid(LicenseRejectReason.INVALID_PAYLOAD, "email must be a string")
        } ?: ""

        val sku = root["sku"]?.let { el ->
            if (el is JsonNull) "" else strictString(el)
                ?: return LicenseVerificationResult.Invalid(LicenseRejectReason.INVALID_PAYLOAD, "sku must be a string")
        } ?: ""

        val payload = LicensePayload(
            version = version,
            kid = kid,
            id = id,
            email = email,
            product = product,
            sku = sku,
            seats = seats,
            features = features.distinct(),
            iat = iat,
            exp = exp
        )

        if (payload.isExpiredAt(nowEpochSeconds)) {
            return LicenseVerificationResult.Invalid(
                LicenseRejectReason.EXPIRED,
                "License expired at $exp"
            )
        }

        return LicenseVerificationResult.Valid(token = token, payload = payload)
    }

    companion object {
        const val TOKEN_PREFIX = "SF1"
        const val EXPECTED_VERSION = 1
        const val EXPECTED_PRODUCT = "forgemind"
        const val DEFAULT_KID = "sf-2026-11"
        const val DEFAULT_PUBLIC_KEY_B64URL = "Mly6UK5Ql46pxsg67mjZRTLp-cposgTRsy89g24Ku4g"

        private const val ED25519_PUBLIC_KEY_BYTES = 32
        private const val ED25519_SIGNATURE_BYTES = 64

        private val BASE64URL_REGEX = Regex("^[A-Za-z0-9_-]+={0,2}$")
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Strips surrounding whitespace and accidental line breaks inserted by email/chat copy-paste.
         */
        fun normalizeToken(raw: String): String =
            raw.trim().replace("\r", "").replace("\n", "").replace(" ", "")

        internal fun decodeBase64UrlStrict(input: String): ByteArray? {
            if (input.isEmpty() || !BASE64URL_REGEX.matches(input)) return null
            if (input.length % 4 == 1) return null
            return runCatching {
                Base64.getUrlDecoder().decode(input)
            }.getOrNull()
        }

        private fun verifyEd25519(
            publicKey: Ed25519PublicKeyParameters,
            messageBytes: ByteArray,
            signatureBytes: ByteArray
        ): Boolean = runCatching {
            val signer = Ed25519Signer()
            signer.init(false, publicKey)
            signer.update(messageBytes, 0, messageBytes.size)
            signer.verifySignature(signatureBytes)
        }.getOrDefault(false)

        private fun strictString(el: kotlinx.serialization.json.JsonElement?): String? {
            val prim = el as? JsonPrimitive ?: return null
            return if (prim.isString) prim.content else null
        }

        private fun strictInt(el: kotlinx.serialization.json.JsonElement?): Int? {
            val prim = el as? JsonPrimitive ?: return null
            if (prim.isString) return null
            val content = prim.content
            if (content == "true" || content == "false" || '.' in content || 'e' in content || 'E' in content) {
                return null
            }
            return prim.intOrNull
        }

        private fun strictLong(el: kotlinx.serialization.json.JsonElement?): Long? {
            val prim = el as? JsonPrimitive ?: return null
            if (prim.isString) return null
            val content = prim.content
            if (content == "true" || content == "false" || '.' in content || 'e' in content || 'E' in content) {
                return null
            }
            return prim.longOrNull
        }
    }
}
