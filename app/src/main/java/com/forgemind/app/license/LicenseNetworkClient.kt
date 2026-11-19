package com.forgemind.app.license

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

sealed interface OnlineVerifyResult {
    data class Verified(val payloadJson: JsonObject?) : OnlineVerifyResult
    data class Rejected(val httpCode: Int, val message: String) : OnlineVerifyResult
    data class NetworkError(val message: String) : OnlineVerifyResult
}

sealed interface OnlineKeyResult {
    data class Success(val kid: String?, val publicKey: String?, val rawBody: String) : OnlineKeyResult
    data class Error(val message: String) : OnlineKeyResult
}

/**
 * Optional fallback network verification client against silentfish.dev.
 *
 * IMPORTANT: Never replaces local Ed25519 verification. Offline verification in [LicenseVerifier]
 * is always the authoritative check; network failures must never revoke a valid signed key.
 */
class LicenseNetworkClient(
    private val verifyUrl: String = DEFAULT_VERIFY_URL,
    private val keyUrl: String = DEFAULT_KEY_URL,
    private val client: OkHttpClient = defaultHttpClient()
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun verifyOnline(rawToken: String): OnlineVerifyResult = withContext(Dispatchers.IO) {
        val token = LicenseVerifier.normalizeToken(rawToken)
        if (token.isEmpty()) {
            return@withContext OnlineVerifyResult.Rejected(0, "Empty license token")
        }
        val bodyJson = buildJsonObject {
            put("token", JsonPrimitive(token))
        }.toString()
        val request = Request.Builder()
            .url(verifyUrl)
            .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val detail = extractErrorMessage(text).ifBlank { "HTTP ${response.code}" }
                    return@use OnlineVerifyResult.Rejected(response.code, detail)
                }
                val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                    ?: return@use OnlineVerifyResult.Rejected(response.code, "Invalid JSON response")
                val ok = (root["ok"] as? JsonPrimitive)?.booleanOrNull == true
                if (ok) {
                    OnlineVerifyResult.Verified(root["payload"] as? JsonObject)
                } else {
                    val detail = extractErrorMessage(text).ifBlank { "Server returned ok=false" }
                    OnlineVerifyResult.Rejected(response.code, detail)
                }
            }
        }.getOrElse { error ->
            OnlineVerifyResult.NetworkError(error.message ?: error.javaClass.simpleName)
        }
    }

    suspend fun fetchPublicKey(): OnlineKeyResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(keyUrl)
            .get()
            .header("Accept", "application/json, text/plain")
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty().trim()
                if (!response.isSuccessful) {
                    return@use OnlineKeyResult.Error("HTTP ${response.code}")
                }
                val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                if (root != null) {
                    val kid = (root["kid"] as? JsonPrimitive)?.content
                    val key = (root["publicKey"] as? JsonPrimitive)?.content
                        ?: (root["public_key"] as? JsonPrimitive)?.content
                        ?: (root["key"] as? JsonPrimitive)?.content
                    OnlineKeyResult.Success(kid = kid, publicKey = key, rawBody = text)
                } else {
                    OnlineKeyResult.Success(kid = null, publicKey = text.takeIf { it.isNotBlank() }, rawBody = text)
                }
            }
        }.getOrElse { error ->
            OnlineKeyResult.Error(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun extractErrorMessage(body: String): String {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return body.take(180)
        return (root["error"] as? JsonPrimitive)?.content
            ?: (root["message"] as? JsonPrimitive)?.content
            ?: (root["reason"] as? JsonPrimitive)?.content
            ?: ""
    }

    companion object {
        const val DEFAULT_VERIFY_URL = "https://silentfish.dev/api/license/verify"
        const val DEFAULT_KEY_URL = "https://silentfish.dev/api/license/key"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .build()
    }
}
