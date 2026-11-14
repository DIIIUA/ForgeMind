package com.forgemind.app.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import com.forgemind.app.llm.LlmHttpFactory
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.math.floor
import kotlin.math.roundToInt

data class EdgeNeuralVoice(
    val shortName: String,
    val friendlyName: String,
    val locale: String,
    val gender: String
) {
    fun displayLabel(): String {
        if (shortName == MicrosoftEdgeTtsClient.AUTO_VOICE_ID) return friendlyName
        val genderShort = when {
            gender.equals("Female", ignoreCase = true) -> "♀"
            gender.equals("Male", ignoreCase = true) -> "♂"
            else -> ""
        }
        return buildString {
            append(shortName)
            if (genderShort.isNotEmpty()) append(" ($genderShort)")
        }
    }
}

/**
 * Keyless, zero-server Microsoft Edge Read Aloud Neural TTS client.
 * Implements the current v2 `Sec-MS-GEC` DRM signature (`CS64 + modified SHA-256`),
 * automatic server clock-skew adjustment on HTTP 403, live 300+ voice catalog discovery,
 * and streaming 24 kHz / 48 kbps MP3 synthesis over WebSocket.
 */
@OptIn(ExperimentalUnsignedTypes::class)
object MicrosoftEdgeTtsClient {
    const val AUTO_VOICE_ID = "auto"

    private const val BASE_URL = "speech.platform.bing.com/consumer/speech/synthesize/readaloud"
    private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val WSS_BASE_URL = "wss://$BASE_URL/edge/v1?TrustedClientToken=$TRUSTED_CLIENT_TOKEN"
    private const val VOICE_LIST_URL = "https://$BASE_URL/voices/list?trustedclienttoken=$TRUSTED_CLIENT_TOKEN"

    private const val CHROMIUM_FULL_VERSION = "143.0.3650.75"
    private const val CHROMIUM_MAJOR_VERSION = "143"
    const val SEC_MS_GEC_VERSION = "2-$CHROMIUM_FULL_VERSION"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/$CHROMIUM_MAJOR_VERSION.0.0.0 Safari/537.36 " +
            "Edg/$CHROMIUM_MAJOR_VERSION.0.0.0"
    private const val CHROME_EXT_ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"

    private const val WIN_EPOCH_SECONDS = 11644473600.0
    private const val U32_MASK = 0xFFFFFFFFuL

    val CURATED_VOICES: List<EdgeNeuralVoice> = listOf(
        EdgeNeuralVoice(AUTO_VOICE_ID, "Auto (RU: Svetlana • UA: Polina • EN/Multi: Ava)", "auto", ""),
        EdgeNeuralVoice("ru-RU-SvetlanaNeural", "ru-RU-SvetlanaNeural — Светлана (RU • Female)", "ru-RU", "Female"),
        EdgeNeuralVoice("ru-RU-DmitryNeural", "ru-RU-DmitryNeural — Дмитрий (RU • Male)", "ru-RU", "Male"),
        EdgeNeuralVoice("en-US-AvaMultilingualNeural", "en-US-AvaMultilingualNeural — Ava Multilingual (Female)", "en-US", "Female"),
        EdgeNeuralVoice("en-US-AndrewMultilingualNeural", "en-US-AndrewMultilingualNeural — Andrew Multilingual (Male)", "en-US", "Male"),
        EdgeNeuralVoice("en-US-EmmaMultilingualNeural", "en-US-EmmaMultilingualNeural — Emma Multilingual (Female)", "en-US", "Female"),
        EdgeNeuralVoice("en-US-BrianMultilingualNeural", "en-US-BrianMultilingualNeural — Brian Multilingual (Male)", "en-US", "Male"),
        EdgeNeuralVoice("uk-UA-PolinaNeural", "uk-UA-PolinaNeural — Поліна (UA • Female)", "uk-UA", "Female"),
        EdgeNeuralVoice("uk-UA-OstapNeural", "uk-UA-OstapNeural — Остап (UA • Male)", "uk-UA", "Male"),
        EdgeNeuralVoice("en-US-AriaNeural", "en-US-AriaNeural — Aria (US • Female)", "en-US", "Female"),
        EdgeNeuralVoice("en-GB-SoniaNeural", "en-GB-SoniaNeural — Sonia (UK • Female)", "en-GB", "Female"),
        EdgeNeuralVoice("de-DE-SeraphinaMultilingualNeural", "de-DE-SeraphinaMultilingualNeural — Seraphina (DE/Multi)", "de-DE", "Female"),
        EdgeNeuralVoice("zh-CN-XiaoxiaoNeural", "zh-CN-XiaoxiaoNeural — Xiaoxiao (ZH • Female)", "zh-CN", "Female")
    )

    @Volatile
    private var clockSkewSeconds: Double = 0.0

    @Volatile
    private var cachedLiveVoices: List<EdgeNeuralVoice>? = null

    private val secureRandom = SecureRandom()

    private val SHA256_INITIAL = ulongArrayOf(
        0x6A09E667uL, 0xBB67AE85uL, 0x3C6EF372uL, 0xA54FF53AuL,
        0x510E527FuL, 0x9B05688CuL, 0x1F83D9ABuL, 0x5BE0CD19uL
    )
    private val SHA256_STATE = ulongArrayOf(
        0xFD9FBEA2uL, 0x91243CE7uL, 0xBE499094uL, 0xCD2E1E0AuL,
        0x6C006F49uL, 0x04BD35ABuL, 0xFEB0A7E4uL, 0x03EFFBD2uL
    )
    private val SHA256_SCHEDULE = ulongArrayOf(
        0xEB24BF9EuL, 0x1558691FuL, 0xA0C09EE2uL, 0x0CFA32BAuL,
        0xA622EE8CuL, 0xD1DD9491uL, 0xC0DD25A0uL, 0xF9E70AB6uL,
        0xF0C31573uL, 0x7058CE4EuL, 0x7188E606uL, 0x00E5AB33uL,
        0xD258B060uL, 0x72766564uL
    )
    private val SHA256_ROUND_CONSTANTS = ulongArrayOf(
        0x428A2F98uL, 0x71374491uL, 0xB5C0FBCFuL, 0xE9B5DBA5uL,
        0x3956C25BuL, 0x59F111F1uL, 0x923F82A4uL, 0xAB1C5ED5uL,
        0xD807AA98uL, 0x12835B01uL, 0x243185BEuL, 0x550C7DC3uL,
        0x72BE5D74uL, 0x80DEB1FEuL, 0x9BDC06A7uL, 0xC19BF174uL,
        0xE49B69C1uL, 0xEFBE4786uL, 0x0FC19DC6uL, 0x240CA1CCuL,
        0x2DE92C6FuL, 0x4A7484AAuL, 0x5CB0A9DCuL, 0x76F988DAuL,
        0x983E5152uL, 0xA831C66DuL, 0xB00327C8uL, 0xBF597FC7uL,
        0xC6E00BF3uL, 0xD5A79147uL, 0x06CA6351uL, 0x14292967uL,
        0x27B70A85uL, 0x2E1B2138uL, 0x4D2C6DFCuL, 0x53380D13uL,
        0x650A7354uL, 0x766A0ABBuL, 0x81C2C92EuL, 0x92722C85uL,
        0xA2BFE8A1uL, 0xA81A664BuL, 0xC24B8B70uL, 0xC76C51A3uL,
        0xD192E819uL, 0xD6990624uL, 0xF40E3585uL, 0x106AA070uL,
        0x19A4C116uL, 0x1E376C08uL, 0x2748774CuL, 0x34B0BCB5uL,
        0x391C0CB3uL, 0x4ED8AA4AuL, 0x5B9CCA4FuL, 0x682E6FF3uL,
        0x748F82EEuL, 0x78A5636FuL, 0x84C87814uL, 0x8CC70208uL,
        0x90BEFFFAuL, 0xA4506CEBuL, 0xBEF9A3F7uL, 0xC67178F2uL
    )
    private const val SHA256_ADJUSTMENT = 0xC9DC06A7uL
    private val SHA256_BLOCK_CONSTANTS = ULongArray(64) { idx ->
        if (idx == 14) SHA256_ADJUSTMENT else SHA256_ROUND_CONSTANTS[idx]
    }
    private const val SHA256_SIGMA0_ADJUSTMENT = 0x059C0B80uL
    private const val SHA256_SIGMA1_ADJUSTMENT = 0x000B92C0uL

    private val CS64_COEFFICIENTS = arrayOf(
        0xE82E91B1uL to 0xB32700D0uL,
        0x44DE24C6uL to 0x88DFF51DuL,
        0x3BEB65BCuL to 0x29514038uL,
        0xC89C5933uL to 0xB328CBDFuL,
        0x8D1EC211uL to 0xD405B4D5uL,
        0x314EF5D5uL to 0x26E55F3CuL,
        0xE92491B1uL to 0x607801C6uL,
        0xF0A5B9D5uL to 0x82B2C1FBuL,
        0x06064CF3uL to 0xFA51A3DAuL,
        0xDA79FA60uL to 0x207CC464uL,
        0xBED20560uL to 0x3C3C74F3uL,
        0x89F9C9F8uL to 0xD2D14B61uL,
        0x35FA38C6uL to 0xBB04A2C1uL,
        0xCB49E3C6uL to 0xC5626167uL
    )
    private const val CS64_FACTOR = 0x82893933uL
    private const val CS64_ADJUSTMENT0 = 0xF2F90B1AuL
    private const val CS64_ADJUSTMENT1 = 0x0B1A0000uL

    fun resolveVoiceForText(selectedVoice: String, text: String, fallbackLanguage: String = "ru"): String {
        val cleanVoice = selectedVoice.trim()
        if (cleanVoice.isNotEmpty() && !cleanVoice.equals(AUTO_VOICE_ID, ignoreCase = true)) {
            return cleanVoice
        }
        var cyrillicCount = 0
        var ukSpecificCount = 0
        var latinCount = 0
        for (ch in text) {
            when (ch.lowercaseChar()) {
                'і', 'ї', 'є', 'ґ' -> {
                    cyrillicCount++
                    ukSpecificCount++
                }
                in 'а'..'я', 'ё' -> cyrillicCount++
                in 'a'..'z' -> latinCount++
            }
        }
        return when {
            ukSpecificCount > 0 -> "uk-UA-PolinaNeural"
            cyrillicCount > 0 && cyrillicCount >= latinCount / 3 -> "ru-RU-SvetlanaNeural"
            latinCount > 0 -> "en-US-AvaMultilingualNeural"
            fallbackLanguage.startsWith("uk", ignoreCase = true) -> "uk-UA-PolinaNeural"
            fallbackLanguage.startsWith("ru", ignoreCase = true) -> "ru-RU-SvetlanaNeural"
            else -> "en-US-AvaMultilingualNeural"
        }
    }

    fun formatRatePercent(speedMultiplier: Float): String {
        val pct = ((speedMultiplier.coerceIn(0.5f, 2.0f) - 1.0f) * 100f).roundToInt().coerceIn(-50, 100)
        return if (pct >= 0) "+${pct}%" else "${pct}%"
    }

    fun formatPitchHz(pitchHz: Int): String {
        val clamped = pitchHz.coerceIn(-50, 50)
        return if (clamped >= 0) "+${clamped}Hz" else "${clamped}Hz"
    }

    fun sanitizeAndEscapeForSsml(raw: String): String {
        val sb = StringBuilder(raw.length + 16)
        for (ch in raw) {
            val code = ch.code
            val clean = if ((code in 0..8) || (code in 11..12) || (code in 14..31)) ' ' else ch
            when (clean) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '\'' -> sb.append("&apos;")
                '"' -> sb.append("&quot;")
                else -> sb.append(clean)
            }
        }
        return sb.toString()
    }

    fun buildSsml(voice: String, escapedText: String, rate: String = "+0%", pitch: String = "+0Hz", volume: String = "+0%"): String {
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
            "<voice name='$voice'>" +
            "<prosody pitch='$pitch' rate='$rate' volume='$volume'>" +
            escapedText +
            "</prosody></voice></speak>"
    }

    private fun jsUtcDateString(nowMs: Long = System.currentTimeMillis()): String {
        val fmt = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return "${fmt.format(Date(nowMs))} GMT+0000 (Coordinated Universal Time)"
    }

    private fun generateMuid(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return bytes.toUpperHex()
    }

    private fun randomConnectId(): String = UUID.randomUUID().toString().replace("-", "")

    private fun getUnixTimestamp(): Double =
        (System.currentTimeMillis().toDouble() / 1000.0) + clockSkewSeconds

    private fun adjustClockSkewFromResponse(response: Response?): Boolean {
        val serverDate = response?.header("Date")?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val parsedMs = parseHttpDateMillis(serverDate) ?: return false
        val serverUnix = parsedMs.toDouble() / 1000.0
        val clientUnix = getUnixTimestamp()
        clockSkewSeconds += (serverUnix - clientUnix)
        return true
    }

    internal fun parseHttpDateMillis(dateHeader: String): Long? {
        val patterns = arrayOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEEE, dd-MMM-yy HH:mm:ss zzz",
            "EEE MMM d HH:mm:ss yyyy"
        )
        for (pattern in patterns) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("GMT")
                }.parse(dateHeader)?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    fun generateSecMsGecV2(unixTimestampSeconds: Double? = null): String {
        val baseSeconds = (unixTimestampSeconds ?: getUnixTimestamp()) + WIN_EPOCH_SECONDS
        val roundedSeconds = floor(baseSeconds / 300.0).toLong() * 300L
        val ticks100Ns = roundedSeconds * 10_000_000L
        val data = "${ticks100Ns}${TRUSTED_CLIENT_TOKEN}".toByteArray(Charsets.US_ASCII)
        val digest = sha256Digest(data)
        val (csWord0, csWord1) = cs64Digest(data, digest)
        val csBytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(csWord0.toInt())
            .putInt(csWord1.toInt())
            .array()
        return "${csBytes.toUpperHex()}-${digest.toUpperHex()}"
    }

    suspend fun fetchVoiceList(httpClient: OkHttpClient, forceRefresh: Boolean = false): List<EdgeNeuralVoice> =
        withContext(Dispatchers.IO) {
            if (!forceRefresh) {
                cachedLiveVoices?.let { if (it.isNotEmpty()) return@withContext it }
            }
            var lastError: Throwable? = null
            for (attempt in 0..1) {
                val url = "$VOICE_LIST_URL&Sec-MS-GEC=${generateSecMsGecV2()}&Sec-MS-GEC-Version=$SEC_MS_GEC_VERSION"
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "*/*")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Sec-CH-UA", "\" Not;A Brand\";v=\"99\", \"Microsoft Edge\";v=\"$CHROMIUM_MAJOR_VERSION\", \"Chromium\";v=\"$CHROMIUM_MAJOR_VERSION\"")
                    .header("Sec-CH-UA-Mobile", "?0")
                    .header("Sec-Fetch-Site", "none")
                    .header("Sec-Fetch-Mode", "cors")
                    .header("Sec-Fetch-Dest", "empty")
                    .header("Cookie", "muid=${generateMuid()};")
                    .get()
                    .build()
                try {
                    httpClient.newCall(req).execute().use { resp ->
                        if (resp.code == 403 && attempt == 0 && adjustClockSkewFromResponse(resp)) {
                            return@use
                        }
                        if (!resp.isSuccessful) {
                            error("Microsoft Voices HTTP ${resp.code}")
                        }
                        val body = resp.body?.string().orEmpty()
                        val arr = LlmHttpFactory.json.parseToJsonElement(body) as? JsonArray
                            ?: error("Invalid Microsoft voice list JSON")
                        val parsed = arr.mapNotNull { elem ->
                            val obj = elem as? JsonObject ?: return@mapNotNull null
                            val shortName = (obj["ShortName"] as? JsonPrimitive)?.contentOrNull?.trim()
                                ?: return@mapNotNull null
                            val friendly = (obj["FriendlyName"] as? JsonPrimitive)?.contentOrNull?.trim()
                                ?: shortName
                            val locale = (obj["Locale"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                            val gender = (obj["Gender"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                            EdgeNeuralVoice(
                                shortName = shortName,
                                friendlyName = friendly,
                                locale = locale,
                                gender = gender
                            )
                        }.sortedWith(
                            compareBy<EdgeNeuralVoice>(
                                { voicePriorityRank(it) },
                                { it.locale },
                                { it.shortName }
                            )
                        )
                        val fullList = buildList {
                            add(CURATED_VOICES.first())
                            addAll(parsed)
                        }
                        cachedLiveVoices = fullList
                        return@withContext fullList
                    }
                } catch (t: Throwable) {
                    lastError = t
                }
            }
            throw (lastError ?: IllegalStateException("Failed to fetch Microsoft voices"))
        }

    private fun voicePriorityRank(v: EdgeNeuralVoice): Int = when {
        v.locale.startsWith("ru-", ignoreCase = true) -> 0
        v.locale.startsWith("uk-", ignoreCase = true) -> 1
        v.shortName.contains("Multilingual", ignoreCase = true) && v.locale.startsWith("en-US", ignoreCase = true) -> 2
        v.shortName.contains("Multilingual", ignoreCase = true) -> 3
        v.locale.startsWith("en-", ignoreCase = true) -> 4
        else -> 5
    }

    suspend fun synthesizeMp3(
        httpClient: OkHttpClient,
        text: String,
        voice: String,
        rateMultiplier: Float = 1.0f,
        pitchHz: Int = 0,
        onWebSocketOpened: (WebSocket) -> Unit = {},
        onWebSocketClosed: (WebSocket) -> Unit = {},
        isStillActive: () -> Boolean = { true }
    ): ByteArray = withContext(Dispatchers.IO) {
        val escaped = sanitizeAndEscapeForSsml(text).trim()
        if (escaped.isEmpty()) return@withContext ByteArray(0)

        var lastError: Throwable? = null
        for (attempt in 0..1) {
            if (!isStillActive()) return@withContext ByteArray(0)
            try {
                return@withContext synthesizeSingleAttempt(
                    httpClient = httpClient,
                    escapedText = escaped,
                    voice = voice,
                    rate = formatRatePercent(rateMultiplier),
                    pitch = formatPitchHz(pitchHz),
                    allowSkewRetry = (attempt == 0),
                    onWebSocketOpened = onWebSocketOpened,
                    onWebSocketClosed = onWebSocketClosed,
                    isStillActive = isStillActive
                )
            } catch (skew: EdgeClockSkewRetryException) {
                lastError = skew
                continue
            } catch (t: Throwable) {
                lastError = t
                break
            }
        }
        throw (lastError ?: IllegalStateException("Microsoft Edge TTS synthesis failed"))
    }

    private class EdgeClockSkewRetryException(message: String) : RuntimeException(message)

    private suspend fun synthesizeSingleAttempt(
        httpClient: OkHttpClient,
        escapedText: String,
        voice: String,
        rate: String,
        pitch: String,
        allowSkewRetry: Boolean,
        onWebSocketOpened: (WebSocket) -> Unit,
        onWebSocketClosed: (WebSocket) -> Unit,
        isStillActive: () -> Boolean
    ): ByteArray {
        val connId = randomConnectId()
        val url = "$WSS_BASE_URL&ConnectionId=$connId&Sec-MS-GEC=${generateSecMsGecV2()}&Sec-MS-GEC-Version=$SEC_MS_GEC_VERSION"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Origin", CHROME_EXT_ORIGIN)
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", "muid=${generateMuid()};")
            .build()

        val out = ByteArrayOutputStream(32 * 1024)
        val resultDeferred = CompletableDeferred<ByteArray>()

        val ws = httpClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!isStillActive()) {
                    webSocket.cancel()
                    resultDeferred.complete(ByteArray(0))
                    return
                }
                val ts = jsUtcDateString()
                val configFrame =
                    "X-Timestamp:$ts\r\n" +
                        "Content-Type:application/json; charset=utf-8\r\n" +
                        "Path:speech.config\r\n\r\n" +
                        "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{" +
                        "\"sentenceBoundaryEnabled\":\"true\",\"wordBoundaryEnabled\":\"false\"" +
                        "},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n"
                val ssml = buildSsml(voice = voice, escapedText = escapedText, rate = rate, pitch = pitch)
                val ssmlFrame =
                    "X-RequestId:${randomConnectId()}\r\n" +
                        "Content-Type:application/ssml+xml\r\n" +
                        "X-Timestamp:${jsUtcDateString()}Z\r\n" +
                        "Path:ssml\r\n\r\n" +
                        ssml
                webSocket.send(configFrame)
                webSocket.send(ssmlFrame)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!isStillActive()) {
                    webSocket.cancel()
                    if (!resultDeferred.isCompleted) resultDeferred.complete(ByteArray(0))
                    return
                }
                val headerEnd = text.indexOf("\r\n\r\n")
                val headerBlock = if (headerEnd >= 0) text.substring(0, headerEnd) else text
                if (headerBlock.lines().any { it.trim().equals("Path:turn.end", ignoreCase = true) }) {
                    val bytes = synchronized(out) { out.toByteArray() }
                    runCatching { webSocket.close(1000, "done") }
                    if (bytes.isEmpty()) {
                        resultDeferred.completeExceptionally(
                            IllegalStateException("Microsoft TTS returned empty audio for $voice")
                        )
                    } else {
                        resultDeferred.complete(bytes)
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (!isStillActive()) {
                    webSocket.cancel()
                    if (!resultDeferred.isCompleted) resultDeferred.complete(ByteArray(0))
                    return
                }
                val raw = bytes.toByteArray()
                if (raw.size < 2) return
                val headerLen = ((raw[0].toInt() and 0xFF) shl 8) or (raw[1].toInt() and 0xFF)
                val audioOffset = 2 + headerLen
                if (audioOffset > raw.size) return
                val headerText = String(raw, 2, headerLen, Charsets.UTF_8)
                var isAudioPath = false
                var isMpeg = false
                for (line in headerText.split("\r\n")) {
                    val colon = line.indexOf(':')
                    if (colon <= 0) continue
                    val k = line.substring(0, colon).trim()
                    val v = line.substring(colon + 1).trim()
                    if (k.equals("Path", ignoreCase = true) && v.equals("audio", ignoreCase = true)) {
                        isAudioPath = true
                    } else if (k.equals("Content-Type", ignoreCase = true) && v.contains("audio/mpeg", ignoreCase = true)) {
                        isMpeg = true
                    }
                }
                val payloadLen = raw.size - audioOffset
                if (isAudioPath && isMpeg && payloadLen > 0) {
                    synchronized(out) {
                        out.write(raw, audioOffset, payloadLen)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!isStillActive()) {
                    if (!resultDeferred.isCompleted) resultDeferred.complete(ByteArray(0))
                    return
                }
                if (response?.code == 403 && allowSkewRetry && adjustClockSkewFromResponse(response)) {
                    resultDeferred.completeExceptionally(EdgeClockSkewRetryException("Adjusted clock skew after 403"))
                    return
                }
                val httpHint = response?.let { " (HTTP ${it.code})" }.orEmpty()
                resultDeferred.completeExceptionally(
                    IllegalStateException("Microsoft Edge TTS$httpHint: ${t.message ?: t::class.java.simpleName}", t)
                )
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!resultDeferred.isCompleted) {
                    val bytes = synchronized(out) { out.toByteArray() }
                    if (bytes.isNotEmpty()) {
                        resultDeferred.complete(bytes)
                    } else {
                        resultDeferred.completeExceptionally(
                            IllegalStateException("Microsoft Edge TTS closed without audio ($code $reason)")
                        )
                    }
                }
            }
        })

        onWebSocketOpened(ws)
        try {
            return resultDeferred.await()
        } finally {
            onWebSocketClosed(ws)
            runCatching { ws.cancel() }
        }
    }

    private fun rol16(value: ULong): ULong {
        val v = value and U32_MASK
        return ((v shl 16) or (v shr 16)) and U32_MASK
    }

    private fun sigma0(value: ULong): ULong {
        val v = value and U32_MASK
        return ((v shr 7) xor (v shl 25) xor (v shr 18) xor (v shl 14) xor (v shr 3)) and U32_MASK
    }

    private fun sigma1(value: ULong): ULong {
        val v = value and U32_MASK
        return ((v shr 17) xor (v shl 15) xor (v shr 19) xor (v shl 13) xor (v shr 10)) and U32_MASK
    }

    private fun expandSha256Schedule(words: ULongArray, firstWord: Int = 16) {
        for (index in firstWord until 64) {
            val x = words[index - 2]
            val y = words[index - 15]
            val s1 = (x shr 17) xor (x shl 15) xor (x shr 19) xor (x shl 13) xor (x shr 10)
            val s0 = (y shr 7) xor (y shl 25) xor (y shr 18) xor (y shl 14) xor (y shr 3)
            words[index] = (s1 + words[index - 7] + s0 + words[index - 16]) and U32_MASK
        }
    }

    private fun sha256Rounds(state: ULongArray, words: ULongArray, firstRound: Int = 0): ULongArray {
        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]
        var e = state[4]
        var f = state[5]
        var g = state[6]
        var h = state[7]
        val constants = if (firstRound == 14) SHA256_BLOCK_CONSTANTS else SHA256_ROUND_CONSTANTS

        for (index in firstRound until 64) {
            val rotE = e * 0x100000001uL
            val rotA = a * 0x100000001uL
            val t1 = h +
                ((rotE shr 6) xor (rotE shr 11) xor (rotE shr 25)) +
                (g xor (e and (f xor g))) +
                constants[index] +
                words[index]
            val t2 = ((rotA shr 2) xor (rotA shr 13) xor (rotA shr 22)) +
                ((a and b) or (c and (a or b)))
            h = g
            g = f
            f = e
            e = (d + t1) and U32_MASK
            d = c
            c = b
            b = a
            a = (t1 + t2) and U32_MASK
        }
        return ulongArrayOf(a, b, c, d, e, f, g, h)
    }

    private fun sha256Init(salt7: ByteArray): ULongArray {
        val words = ULongArray(64)
        words[14] = ((salt7[0].toUByte().toULong() shl 16) or
            (salt7[1].toUByte().toULong() shl 8) or
            salt7[2].toUByte().toULong()) and U32_MASK
        words[15] = ((salt7[3].toUByte().toULong() shl 24) or
            (salt7[4].toUByte().toULong() shl 16) or
            (salt7[5].toUByte().toULong() shl 8) or
            salt7[6].toUByte().toULong()) and U32_MASK

        for (index in 16 until 30) {
            var value = SHA256_SCHEDULE[index - 16]
            if (index - 2 >= 14) {
                value += if (index == 16) {
                    sigma1(words[index - 2]) xor SHA256_SIGMA1_ADJUSTMENT
                } else {
                    sigma1(words[index - 2])
                }
            }
            if (index - 7 >= 14) {
                value += words[index - 7]
            }
            if (index - 15 >= 14) {
                value += if (index == 29) {
                    sigma0(words[index - 15]) xor SHA256_SIGMA0_ADJUSTMENT
                } else {
                    sigma0(words[index - 15])
                }
            }
            words[index] = value and U32_MASK
        }
        words[30] = (
            sigma1(words[28]) +
                words[23] +
                sigma0(words[15]) +
                words[14] +
                SHA256_ADJUSTMENT -
                SHA256_ROUND_CONSTANTS[14]
            ) and U32_MASK
        expandSha256Schedule(words, firstWord = 31)
        val working = sha256Rounds(SHA256_STATE, words, firstRound = 14)
        return ULongArray(8) { i -> (SHA256_INITIAL[i] + working[i]) and U32_MASK }
    }

    private fun sha256Digest(data: ByteArray): ByteArray {
        require(data.size >= 7) { "Token salt must contain at least seven bytes" }
        val tail = data.copyOfRange(7, data.size)
        val length = 57 + data.size
        val zeroCount = Math.floorMod(55 - length, 64)
        val padded = ByteBuffer.allocate(tail.size + 1 + zeroCount + 8)
            .order(ByteOrder.BIG_ENDIAN)
            .apply {
                put(tail)
                put(0x80.toByte())
                repeat(zeroCount) { put(0.toByte()) }
                putLong(length.toLong() * 8L)
            }
            .array()

        var state = sha256Init(data.copyOfRange(0, 7))
        val words = ULongArray(64)
        val buf = ByteBuffer.wrap(padded).order(ByteOrder.BIG_ENDIAN)
        for (offset in padded.indices step 64) {
            buf.position(offset)
            for (i in 0 until 16) {
                words[i] = buf.int.toUInt().toULong()
            }
            expandSha256Schedule(words, firstWord = 16)
            val working = sha256Rounds(state, words, firstRound = 0)
            state = ULongArray(8) { i -> (state[i] + working[i]) and U32_MASK }
        }

        val out = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
        for (i in 0 until 8) {
            out.putInt(state[i].toUInt().toInt())
        }
        return out.array()
    }

    private fun wordsLe4Even(data: ByteArray): List<ULong> {
        if (data.isEmpty()) return emptyList()
        val pad = Math.floorMod(-data.size, 4)
        val padded = ByteArray(data.size + pad)
        System.arraycopy(data, 0, padded, 0, data.size)
        val buf = ByteBuffer.wrap(padded).order(ByteOrder.LITTLE_ENDIAN)
        val count = padded.size / 4
        val result = ArrayList<ULong>(count + 1)
        repeat(count) {
            result.add(buf.int.toUInt().toULong())
        }
        if (result.size % 2 != 0) {
            result.add(0uL)
        }
        return result
    }

    private fun cs64Digest(data: ByteArray, digest: ByteArray): Pair<UInt, UInt> {
        val dBuf = ByteBuffer.wrap(digest, 0, 8).order(ByteOrder.LITTLE_ENDIAN)
        val keyA = (dBuf.int.toUInt().toULong() or 1uL) and U32_MASK
        val keyB = (dBuf.int.toUInt().toULong() or 1uL) and U32_MASK
        val factor0 = (keyA * CS64_FACTOR) and U32_MASK
        val factor1 = (keyB * CS64_FACTOR) and U32_MASK

        val terms = ArrayList<ULong>(32)
        val offsets = ArrayList<ULong>(32)
        for ((index, pair) in CS64_COEFFICIENTS.withIndex()) {
            val f = if (index % 2 == 0) factor0 else factor1
            terms.add((pair.first * f) and U32_MASK)
            offsets.add(pair.second and U32_MASK)
        }

        val prefixed = ByteArray(1 + data.size)
        System.arraycopy(data, 0, prefixed, 1, data.size)
        for ((index, word) in wordsLe4Even(prefixed).withIndex()) {
            var term = word * (if (index % 2 == 0) keyA else keyB)
            var offset = rol16(word) * (if (index % 2 == 0) 0xEF0569FBuL else 0xC31713DBuL)
            if (index == 0) {
                term += CS64_ADJUSTMENT0 * factor0
                offset += CS64_ADJUSTMENT1
            }
            terms.add(term and U32_MASK)
            offsets.add(offset and U32_MASK)
        }

        var swapAcc = 0uL
        var swapLast = 0uL
        var state = 0uL
        var acc = 0uL
        for (index in terms.indices step 2) {
            val x = terms[index]
            val y = terms[index + 1]

            var t = rol16((offsets[index] + x) and U32_MASK)
            var q = (t * 0x76F50F34uL) and U32_MASK
            swapAcc += (rol16(q) * 0xC3EFEA97uL + q) and U32_MASK

            t = rol16((offsets[index + 1] + y) and U32_MASK)
            q = (t * 0x1881CE3CuL) and U32_MASK
            swapLast = (rol16(q) * 0x35BD1EC9uL + q) and U32_MASK
            swapAcc = (swapAcc + swapLast) and U32_MASK

            state = (state * keyA + x) and U32_MASK
            state = (rol16(state) * 0xCF98B111uL) and U32_MASK
            state = (rol16(state) * 0x87085B9FuL) and U32_MASK
            state = (rol16(state) * 0x12CEB96DuL) and U32_MASK
            state = (rol16(state) * 0x257E1D83uL) and U32_MASK
            acc += state

            t = (state * keyB + y) and U32_MASK
            t = (rol16(t) * 0xA27416F5uL) and U32_MASK
            t = (rol16(t) * 0xD38396FFuL) and U32_MASK
            state = (rol16((rol16(t) * 0x7C932B89uL) and U32_MASK) * 0xBFA49F69uL) and U32_MASK
            acc = (acc + state) and U32_MASK
        }

        val w0 = ((swapLast xor state) and U32_MASK).toUInt()
        val w1 = ((swapAcc xor acc) and U32_MASK).toUInt()
        return w0 to w1
    }

    private val HEX_CHARS = "0123456789ABCDEF".toCharArray()

    private fun ByteArray.toUpperHex(): String {
        val out = CharArray(size * 2)
        var j = 0
        for (b in this) {
            val v = b.toInt() and 0xFF
            out[j++] = HEX_CHARS[v ushr 4]
            out[j++] = HEX_CHARS[v and 0x0F]
        }
        return String(out)
    }
}
