package com.forgemind.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.forgemind.app.data.model.TtsProviderType
import com.forgemind.app.data.model.VoiceSettings
import com.forgemind.app.llm.LlmHttpFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.Reader
import java.net.ProtocolException
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

data class XttsHealthStatus(
    val ok: Boolean,
    val message: String,
    val voices: List<String> = emptyList(),
    val defaultVoice: String? = null
)

data class XttsSpeakerDirectory(
    val voices: List<String>,
    val defaultVoice: String?
)

data class LoadedSpeakerLatents(
    val voiceName: String,
    val speakerEmbedding: JsonArray,
    val gptCondLatent: JsonArray
)

internal data class XttsPcmChunk(
    val pcm: ByteArray,
    val serverAppliedSpeed: Boolean,
    val synthesizedSpeed: Float
)

class TtsManager(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client = LlmHttpFactory.baseClientBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _speakingMessageId = MutableStateFlow<String?>(null)
    val speakingMessageId: StateFlow<String?> = _speakingMessageId.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var speakJob: Job? = null
    private var activeAudioTrack: AudioTrack? = null
    private var activeMediaPlayer: MediaPlayer? = null
    @Volatile
    private var activeMediaSynthSpeed: Float = 1.0f
    private var androidTts: TextToSpeech? = null
    private var androidTtsReady = false
    private var androidTtsListenerBound = false
    private val activeSpeakToken = AtomicLong(0L)
    private val liveSpeed = AtomicReference(1.0f)
    private val activeCalls: MutableSet<Call> = Collections.synchronizedSet(mutableSetOf())
    private val utteranceWaiters = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    private val legacySpeakerCacheFile: File by lazy {
        File(context.filesDir, "xtts_speakers_cache.json")
    }

    private val singleSpeakerCacheFile: File by lazy {
        File(context.filesDir, "xtts_speaker_v2.json")
    }

    fun clearError() {
        _lastError.value = null
    }

    fun updateLiveSpeed(speed: Float) {
        val clamped = clampTtsSpeed(speed)
        liveSpeed.set(clamped)
        runCatching {
            activeMediaPlayer?.let { mp ->
                if (mp.isPlaying) {
                    val ratio = (clamped / activeMediaSynthSpeed).coerceIn(0.5f, 2.0f)
                    mp.playbackParams = mp.playbackParams.setSpeed(ratio)
                }
            }
        }
    }

    fun speak(messageId: String?, rawText: String, settings: VoiceSettings) {
        val clean = sanitizeForSpeech(rawText, settings.skipCodeInTts)
        if (clean.isBlank()) {
            stop()
            return
        }
        val token = cancelPlayback(resetState = false)
        liveSpeed.set(clampTtsSpeed(settings.ttsSpeed))

        _isSpeaking.value = true
        _speakingMessageId.value = messageId
        _lastError.value = null

        speakJob = scope.launch {
            try {
                when (settings.ttsProvider) {
                    TtsProviderType.XTTS_V2, TtsProviderType.XTTS_EXTERNAL -> speakViaXtts(clean, settings, token)
                    TtsProviderType.OPENAI_TTS -> speakViaOpenAiTts(clean, settings, token)
                    TtsProviderType.ANDROID_TTS -> speakViaAndroidTts(clean, settings.xttsLanguage, token)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (activeSpeakToken.get() == token && isActive) {
                    _lastError.value = formatTtsError(t)
                }
            } finally {
                if (activeSpeakToken.get() == token) {
                    _isSpeaking.value = false
                    _speakingMessageId.value = null
                }
            }
        }
    }

    fun stop() {
        cancelPlayback(resetState = true)
    }

    private fun cancelActiveHttpCalls() {
        val snapshot = synchronized(activeCalls) { activeCalls.toList() }
        snapshot.forEach { call -> runCatching { call.cancel() } }
        synchronized(activeCalls) { activeCalls.clear() }
    }

    private fun cancelPlayback(resetState: Boolean): Long {
        val nextToken = activeSpeakToken.incrementAndGet()
        speakJob?.cancel()
        speakJob = null
        cancelActiveHttpCalls()
        utteranceWaiters.values.forEach { it.cancel() }
        utteranceWaiters.clear()
        runCatching { activeAudioTrack?.pause(); activeAudioTrack?.flush(); activeAudioTrack?.release() }
        activeAudioTrack = null
        runCatching { activeMediaPlayer?.stop(); activeMediaPlayer?.release() }
        activeMediaPlayer = null
        activeMediaSynthSpeed = 1.0f
        runCatching { androidTts?.stop() }
        if (resetState) {
            _isSpeaking.value = false
            _speakingMessageId.value = null
        }
        return nextToken
    }

    private fun formatTtsError(t: Throwable): String {
        val cls = t::class.java.simpleName
        val msg = t.message?.trim().orEmpty()
        return if (t is EOFException || t is ProtocolException || msg.contains("EOF", ignoreCase = true) || msg.contains("unexpected end of stream", ignoreCase = true)) {
            "Ошибка потока TTS ($cls): сервер прервал передачу аудио. Проверьте XTTS или повторите ещё раз."
        } else {
            "Ошибка TTS: ${msg.ifBlank { cls }}"
        }
    }

    suspend fun testAndLoadXttsVoices(settings: VoiceSettings): XttsHealthStatus =
        withContext(Dispatchers.IO) {
            runCatching {
                val base = settings.activeXttsBaseUrl().trim().trimEnd('/')
                if (base.isBlank()) {
                    error(
                        if (settings.ttsProvider == TtsProviderType.XTTS_EXTERNAL) {
                            "Укажите URL внешнего XTTS (например https://....trycloudflare.com)"
                        } else {
                            "Укажите URL локального XTTS-v2 сервера (например http://192.168.1.219:8020)"
                        }
                    )
                }
                cleanupLegacySpeakerCache()

                val hReq = buildXttsRequestBuilder("$base/health", settings).get().build()
                var healthDefaultVoice: String? = null
                val healthSummary = client.newCall(hReq).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    val ct = resp.header("Content-Type").orEmpty().lowercase()
                    if (ct.contains("text/html") || body.trimStart().startsWith("<!DOCTYPE html", ignoreCase = true) || body.contains("cloudflareaccess", ignoreCase = true)) {
                        error("Сервер вернул HTML-страницу входа (Cloudflare Access). Укажите CF-Access-Client-Id и CF-Access-Client-Secret.")
                    }
                    if (!resp.isSuccessful) error("Health HTTP ${resp.code} ($base/health): ${body.take(250)}")
                    val obj = LlmHttpFactory.json.parseToJsonElement(body).jsonObject
                    val dev = (obj["device"] as? JsonPrimitive)?.contentOrNull ?: "?"
                    val pitch = obj["pitch"]?.toString() ?: "0"
                    healthDefaultVoice = sanitizeVoiceName((obj["default_voice"] as? JsonPrimitive)?.contentOrNull)
                    "device=$dev, pitch=$pitch"
                }

                val dir = fetchSpeakerDirectory(base, settings, healthDefaultVoice)
                val voices = dir.voices
                val defVoice = dir.defaultVoice
                val defNote = defVoice?.let { ", по умолчанию: $it" } ?: ""
                val modeTag = if (settings.ttsProvider == TtsProviderType.XTTS_EXTERNAL) "XTTS внешний" else "XTTS"
                XttsHealthStatus(
                    ok = true,
                    message = "$modeTag готов ($healthSummary$defNote, голосов: ${voices.size})",
                    voices = voices,
                    defaultVoice = defVoice
                )
            }.getOrElse { e ->
                if (e is CancellationException) throw e
                XttsHealthStatus(ok = false, message = e.message ?: "Не удалось подключиться к XTTS")
            }
        }

    private suspend fun speakViaXtts(cleanText: String, settings: VoiceSettings, token: Long) =
        coroutineScope {
            val base = settings.activeXttsBaseUrl().trim().trimEnd('/')
            if (base.isBlank()) error("Не задан URL сервера XTTS-v2")
            cleanupLegacySpeakerCache()

            val latents = getCachedOrFetchSingleSpeaker(base, settings)
            if (activeSpeakToken.get() != token || !isActive) return@coroutineScope
            val spkEmb = latents.speakerEmbedding
            val gptLat = latents.gptCondLatent
            val lang = settings.xttsLanguage.ifBlank { "ru" }
            val sentences = splitIntoChunks(cleanText, maxLen = 155)
            if (sentences.isEmpty()) return@coroutineScope

            fun launchChunkFetch(sentence: String): Deferred<XttsPcmChunk> = async(Dispatchers.IO) {
                val speedAtRequest = liveSpeed.get()
                val reqJson = buildXttsStreamPayload(
                    text = sentence,
                    language = lang,
                    speakerEmbedding = spkEmb,
                    gptCondLatent = gptLat,
                    pitch = settings.xttsPitch,
                    keepTempo = settings.xttsKeepTempo,
                    speed = speedAtRequest
                )
                requestXttsChunk(
                    httpClient = client,
                    url = "$base/tts_stream",
                    reqJson = reqJson,
                    settings = settings,
                    synthesizedSpeed = speedAtRequest,
                    maxAttempts = 2,
                    onCallStarted = { activeCalls.add(it) },
                    onCallEnded = { activeCalls.remove(it) },
                    isStillActive = { activeSpeakToken.get() == token && isActive }
                )
            }

            val sampleRate = 24_000
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(9600)

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            activeAudioTrack = track

            var nextFetch: Deferred<XttsPcmChunk>? = launchChunkFetch(sentences.first())
            var writtenFrames = 0L
            var appliedTrackSpeed = 1.0f
            var startedPlayback = false

            try {
                for (idx in sentences.indices) {
                    if (activeSpeakToken.get() != token || !isActive) break
                    val currentChunk = nextFetch?.await() ?: break
                    nextFetch = if (idx + 1 < sentences.size && activeSpeakToken.get() == token && isActive) {
                        launchChunkFetch(sentences[idx + 1])
                    } else {
                        null
                    }

                    val pcm = currentChunk.pcm
                    if (pcm.isEmpty()) continue
                    if (!startedPlayback) {
                        track.play()
                        startedPlayback = true
                    }

                    var offset = 0
                    val writeSlice = 4800
                    while (offset < pcm.size && activeSpeakToken.get() == token && isActive) {
                        val targetSpeed = computeTrackPlaybackSpeed(
                            liveSpeed = liveSpeed.get(),
                            synthesizedSpeed = currentChunk.synthesizedSpeed,
                            serverAppliedSpeed = currentChunk.serverAppliedSpeed
                        )
                        if (abs(targetSpeed - appliedTrackSpeed) > 0.02f) {
                            runCatching {
                                track.playbackParams = track.playbackParams.setSpeed(targetSpeed)
                                appliedTrackSpeed = targetSpeed
                            }
                        }
                        val count = minOf(writeSlice, pcm.size - offset)
                        val written = track.write(pcm, offset, count, AudioTrack.WRITE_BLOCKING)
                        if (written < 0) error("AudioTrack write error: $written")
                        if (written == 0) break
                        offset += written
                        writtenFrames += (written / 2)
                    }
                }

                if (startedPlayback && writtenFrames > 0L && activeSpeakToken.get() == token && isActive) {
                    val targetFrames = writtenFrames.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    val drainStart = System.currentTimeMillis()
                    while (activeSpeakToken.get() == token && isActive) {
                        val head = runCatching { track.playbackHeadPosition }.getOrDefault(targetFrames)
                        if (head >= targetFrames) break
                        if (System.currentTimeMillis() - drainStart > 2500L) break
                        delay(40)
                    }
                }
            } finally {
                nextFetch?.cancel()
                runCatching {
                    track.pause()
                    track.flush()
                    track.release()
                }
                if (activeAudioTrack == track) activeAudioTrack = null
            }
        }

    private suspend fun speakViaOpenAiTts(cleanText: String, settings: VoiceSettings, token: Long) {
        val url = settings.openAiTtsUrl.ifBlank { "https://api.openai.com/v1/audio/speech" }.trim()
        val key = settings.openAiTtsKey.trim()
        if (key.isBlank() && url.contains("openai.com")) error("Укажите API-ключ OpenAI TTS")

        val chunks = splitIntoChunks(cleanText, maxLen = 3800)
        for ((idx, chunk) in chunks.withIndex()) {
            if (activeSpeakToken.get() != token || !currentCoroutineContext().isActive) break
            val reqSpeed = liveSpeed.get().coerceIn(0.25f, 4.0f)
            val body = buildOpenAiSpeechPayload(
                model = settings.openAiTtsModel.ifBlank { "gpt-4o-mini-tts" },
                input = chunk,
                voice = settings.openAiTtsVoice.ifBlank { "nova" },
                speed = reqSpeed
            )
            val req = Request.Builder()
                .url(url)
                .apply { if (key.isNotBlank()) header("Authorization", "Bearer $key") }
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val tempFile = File(context.cacheDir, "tts_${token}_${idx}_${System.currentTimeMillis()}.mp3")
            val call = client.newCall(req)
            activeCalls.add(call)
            var currentMp: MediaPlayer? = null
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) error("OpenAI TTS HTTP ${resp.code}: ${resp.body?.string().orEmpty().take(300)}")
                    resp.body?.byteStream()?.use { inp -> tempFile.outputStream().use { out -> inp.copyTo(out) } }
                }
                if (activeSpeakToken.get() != token || !currentCoroutineContext().isActive) break
                activeMediaSynthSpeed = reqSpeed
                val done = CompletableDeferred<Unit>()
                withContext(Dispatchers.Main) {
                    if (activeSpeakToken.get() != token) {
                        done.cancel()
                        return@withContext
                    }
                    val mp = MediaPlayer().apply {
                        setDataSource(tempFile.absolutePath)
                        setOnCompletionListener { done.complete(Unit) }
                        setOnErrorListener { _, what, extra ->
                            done.completeExceptionally(IllegalStateException("MediaPlayer error $what/$extra"))
                            true
                        }
                        prepare()
                        val ratio = (liveSpeed.get() / reqSpeed).coerceIn(0.5f, 2.0f)
                        if (abs(ratio - 1.0f) > 0.02f) {
                            runCatching { playbackParams = playbackParams.setSpeed(ratio) }
                        }
                        start()
                    }
                    currentMp = mp
                    activeMediaPlayer = mp
                }
                done.await()
            } finally {
                activeCalls.remove(call)
                runCatching { currentMp?.stop() }
                runCatching { currentMp?.release() }
                if (activeMediaPlayer == currentMp) activeMediaPlayer = null
                activeMediaSynthSpeed = 1.0f
                runCatching { tempFile.delete() }
            }
        }
    }

    private suspend fun speakViaAndroidTts(cleanText: String, language: String, token: Long) {
        val ready = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) {
            if (androidTts != null && androidTtsReady) {
                ready.complete(true)
            } else {
                androidTts = TextToSpeech(context.applicationContext) { status ->
                    androidTtsReady = (status == TextToSpeech.SUCCESS)
                    ready.complete(androidTtsReady)
                }
            }
        }
        if (!ready.await()) error("Системный Android TTS не инициализирован")
        val tts = androidTts ?: return
        if (!androidTtsListenerBound) {
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    utteranceId?.let { utteranceWaiters.remove(it)?.complete(Unit) }
                }
                @Suppress("OVERRIDE_DEPRECATION")
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    utteranceId?.let {
                        utteranceWaiters.remove(it)?.completeExceptionally(
                            IllegalStateException("Ошибка системного Android TTS")
                        )
                    }
                }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    utteranceId?.let {
                        utteranceWaiters.remove(it)?.completeExceptionally(
                            IllegalStateException("Ошибка системного Android TTS (код $errorCode)")
                        )
                    }
                }
                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    utteranceId?.let { utteranceWaiters.remove(it)?.cancel() }
                }
            })
            androidTtsListenerBound = true
        }

        val locale = if (language.startsWith("en")) Locale.US else Locale.Builder().setLanguage("ru").setRegion("RU").build()
        tts.language = locale
        val chunks = splitIntoChunks(cleanText, 600)
        for ((idx, chunk) in chunks.withIndex()) {
            if (activeSpeakToken.get() != token || !currentCoroutineContext().isActive) break
            tts.setSpeechRate(liveSpeed.get().coerceIn(0.5f, 2.5f))
            val uttId = "fm_${token}_$idx"
            val waiter = CompletableDeferred<Unit>()
            utteranceWaiters[uttId] = waiter
            val res = tts.speak(chunk, TextToSpeech.QUEUE_FLUSH, null, uttId)
            if (res != TextToSpeech.SUCCESS) {
                utteranceWaiters.remove(uttId)
                error("Android TTS не смог поставить фразу в очередь (код $res)")
            }
            try {
                kotlinx.coroutines.withTimeoutOrNull(25_000L) {
                    waiter.await()
                }
            } finally {
                utteranceWaiters.remove(uttId)
            }
        }
    }

    private fun cleanupLegacySpeakerCache() {
        if (legacySpeakerCacheFile.exists()) {
            runCatching { legacySpeakerCacheFile.delete() }
        }
    }

    private fun fetchSpeakerDirectory(
        baseUrl: String,
        settings: VoiceSettings,
        fallbackDefault: String? = null
    ): XttsSpeakerDirectory {
        // 1) Fast lightweight endpoint (~1.5 KB instead of ~28 MB)
        val fastReq = buildXttsRequestBuilder("$baseUrl/speakers", settings).get().build()
        val fastResult = runCatching {
            client.newCall(fastReq).execute().use { resp ->
                if (resp.code == 404 || resp.code == 405) return@use null
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("/speakers HTTP ${resp.code}: ${raw.take(300)}")
                val obj = LlmHttpFactory.json.parseToJsonElement(raw).jsonObject
                val defVoice = sanitizeVoiceName((obj["default_voice"] as? JsonPrimitive)?.contentOrNull)
                    ?: fallbackDefault
                val rawNames = (obj["voices"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .orEmpty()
                buildSpeakerDirectory(rawNames, defVoice)
            }
        }
        fastResult.getOrNull()?.let { dir ->
            if (dir.voices.isNotEmpty()) return dir
        }

        // 2) Streaming O(1)-memory fallback for legacy /studio_speakers servers
        val legacyReq = buildXttsRequestBuilder("$baseUrl/studio_speakers", settings).get().build()
        return client.newCall(legacyReq).execute().use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string().orEmpty().take(300)
                error("/studio_speakers HTTP ${resp.code}: $err")
            }
            val stream = resp.body?.byteStream() ?: error("Пустой ответ /studio_speakers")
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8), 64 * 1024).use { reader ->
                val rawNames = extractSpeakerNamesFromStream(reader)
                buildSpeakerDirectory(rawNames, fallbackDefault)
            }
        }
    }

    private fun getCachedOrFetchSingleSpeaker(
        baseUrl: String,
        settings: VoiceSettings
    ): LoadedSpeakerLatents {
        val requested = sanitizeVoiceName(settings.activeXttsVoice())

        val cached = readSingleSpeakerCache(baseUrl)
        if (cached != null) {
            if (requested == null && cached.second) {
                return cached.first
            }
            if (requested != null && cached.first.voiceName.equals(requested, ignoreCase = true)) {
                return cached.first
            }
        }

        var isDefaultTarget = false
        val targetVoice = if (requested != null) {
            requested
        } else {
            val dir = fetchSpeakerDirectory(baseUrl, settings)
            isDefaultTarget = true
            dir.defaultVoice ?: dir.voices.firstOrNull()
                ?: error("На сервере XTTS-v2 не найдены профили голосов")
        }

        // 1) Fast single-speaker endpoint (~450 KB)
        val fastUrl = "$baseUrl/speaker/${Uri.encode(targetVoice)}"
        val fastReq = buildXttsRequestBuilder(fastUrl, settings).get().build()
        val fastLatents = runCatching {
            client.newCall(fastReq).execute().use { resp ->
                if (resp.code == 404 || resp.code == 405) return@use null
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("/speaker HTTP ${resp.code}: ${raw.take(300)}")
                val obj = LlmHttpFactory.json.parseToJsonElement(raw).jsonObject
                parseLatentsObject(targetVoice, obj)
            }
        }.getOrNull()

        val loaded = fastLatents ?: fetchSingleSpeakerFromLegacyStream(baseUrl, settings, targetVoice)

        writeSingleSpeakerCache(baseUrl, loaded, isDefault = isDefaultTarget || loaded.voiceName == targetVoice)
        return loaded
    }

    private fun fetchSingleSpeakerFromLegacyStream(
        baseUrl: String,
        settings: VoiceSettings,
        targetVoice: String
    ): LoadedSpeakerLatents {
        val req = buildXttsRequestBuilder("$baseUrl/studio_speakers", settings).get().build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string().orEmpty().take(300)
                error("/studio_speakers HTTP ${resp.code}: $err")
            }
            val stream = resp.body?.byteStream() ?: error("Пустой ответ /studio_speakers")
            val match = BufferedReader(InputStreamReader(stream, Charsets.UTF_8), 64 * 1024).use { reader ->
                extractSingleSpeakerJsonFromStream(reader, targetVoice)
            } ?: error("Голос \"$targetVoice\" не найден на сервере XTTS-v2")
            val obj = LlmHttpFactory.json.parseToJsonElement(match.second).jsonObject
            parseLatentsObject(match.first, obj)
        }
    }

    private fun parseLatentsObject(voiceName: String, obj: JsonObject): LoadedSpeakerLatents {
        val spkEmb = obj["speaker_embedding"]?.jsonArray
            ?: error("Отсутствует speaker_embedding для голоса $voiceName")
        val gptLat = obj["gpt_cond_latent"]?.jsonArray
            ?: error("Отсутствует gpt_cond_latent для голоса $voiceName")
        return LoadedSpeakerLatents(
            voiceName = voiceName,
            speakerEmbedding = spkEmb,
            gptCondLatent = gptLat
        )
    }

    private fun readSingleSpeakerCache(baseUrl: String): Pair<LoadedSpeakerLatents, Boolean>? {
        if (!singleSpeakerCacheFile.exists()) return null
        return runCatching {
            val root = LlmHttpFactory.json.parseToJsonElement(singleSpeakerCacheFile.readText()).jsonObject
            if ((root["baseUrl"] as? JsonPrimitive)?.contentOrNull != baseUrl) return null
            val voice = sanitizeVoiceName((root["voice"] as? JsonPrimitive)?.contentOrNull) ?: return null
            val isDefault = (root["isDefault"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false
            val spk = root["speaker_embedding"]?.jsonArray ?: return null
            val gpt = root["gpt_cond_latent"]?.jsonArray ?: return null
            LoadedSpeakerLatents(voice, spk, gpt) to isDefault
        }.getOrNull()
    }

    private fun writeSingleSpeakerCache(baseUrl: String, latents: LoadedSpeakerLatents, isDefault: Boolean) {
        runCatching {
            val payload = buildJsonObject {
                put("baseUrl", JsonPrimitive(baseUrl))
                put("voice", JsonPrimitive(latents.voiceName))
                put("isDefault", JsonPrimitive(isDefault))
                put("speaker_embedding", latents.speakerEmbedding)
                put("gpt_cond_latent", latents.gptCondLatent)
            }
            singleSpeakerCacheFile.writeText(payload.toString())
        }
    }

    companion object {
        private const val FORBIDDEN_LEGACY_VOICE = "Venice_selected"

        fun sanitizeVoiceName(name: String?): String? =
            name?.trim()?.takeIf { it.isNotBlank() && !it.equals(FORBIDDEN_LEGACY_VOICE, ignoreCase = true) }

        fun buildSpeakerDirectory(
            rawVoices: List<String>,
            preferredDefault: String? = null
        ): XttsSpeakerDirectory {
            val filtered = rawVoices
                .mapNotNull(::sanitizeVoiceName)
                .distinct()
            val cleanPreferred = sanitizeVoiceName(preferredDefault)
            val resolvedDefault = cleanPreferred
                ?.let { pref -> filtered.firstOrNull { it.equals(pref, ignoreCase = true) } ?: pref }
                ?: filtered.firstOrNull()
            val ordered = if (resolvedDefault != null) {
                filtered.sortedWith(
                    compareByDescending<String> { it.equals(resolvedDefault, ignoreCase = true) }
                        .thenBy { it }
                )
            } else {
                filtered.sorted()
            }
            return XttsSpeakerDirectory(voices = ordered, defaultVoice = resolvedDefault)
        }

        private class JsonCharCursor(private val r: Reader) {
            private var buf: Int = -2

            fun peek(): Int {
                if (buf == -2) buf = r.read()
                return buf
            }

            fun next(): Int {
                val c = peek()
                buf = -2
                return c
            }

            fun skipWhitespace() {
                while (true) {
                    val c = peek()
                    if (c == ' '.code || c == '\t'.code || c == '\r'.code || c == '\n'.code) {
                        next()
                    } else {
                        break
                    }
                }
            }
        }

        private fun readJsonString(cur: JsonCharCursor): String {
            cur.skipWhitespace()
            if (cur.next() != '"'.code) error("Ожидалась строка JSON")
            val sb = StringBuilder(32)
            while (true) {
                val c = cur.next()
                if (c < 0) error("Неожиданный конец JSON-строки")
                when (c) {
                    '"'.code -> return sb.toString()
                    '\\'.code -> {
                        val esc = cur.next()
                        if (esc < 0) error("Незавершённый escape в JSON")
                        when (esc) {
                            '"'.code -> sb.append('"')
                            '\\'.code -> sb.append('\\')
                            '/'.code -> sb.append('/')
                            'b'.code -> sb.append('\b')
                            'f'.code -> sb.append('\u000C')
                            'n'.code -> sb.append('\n')
                            'r'.code -> sb.append('\r')
                            't'.code -> sb.append('\t')
                            'u'.code -> {
                                val hex = CharArray(4) {
                                    val h = cur.next()
                                    if (h < 0) error("Незавершённый \\uXXXX")
                                    h.toChar()
                                }
                                sb.append(String(hex).toInt(16).toChar())
                            }
                            else -> sb.append(esc.toChar())
                        }
                    }
                    else -> sb.append(c.toChar())
                }
            }
        }

        private fun consumeJsonValue(cur: JsonCharCursor, out: StringBuilder? = null) {
            cur.skipWhitespace()
            val first = cur.peek()
            if (first < 0) error("Неожиданный конец JSON-значения")
            if (first == '{'.code || first == '['.code) {
                var depth = 0
                var inString = false
                var escaped = false
                while (true) {
                    val c = cur.next()
                    if (c < 0) break
                    if (out != null) {
                        if (out.length >= 8 * 1024 * 1024) error("Объект спикера превышает 8 МБ")
                        out.append(c.toChar())
                    }
                    if (inString) {
                        if (escaped) {
                            escaped = false
                        } else if (c == '\\'.code) {
                            escaped = true
                        } else if (c == '"'.code) {
                            inString = false
                        }
                    } else {
                        when (c) {
                            '"'.code -> inString = true
                            '{'.code, '['.code -> depth++
                            '}'.code, ']'.code -> {
                                depth--
                                if (depth == 0) return
                            }
                        }
                    }
                }
            } else if (first == '"'.code) {
                cur.next()
                out?.append('"')
                var escaped = false
                while (true) {
                    val c = cur.next()
                    if (c < 0) break
                    out?.append(c.toChar())
                    if (escaped) {
                        escaped = false
                    } else if (c == '\\'.code) {
                        escaped = true
                    } else if (c == '"'.code) {
                        return
                    }
                }
            } else {
                while (true) {
                    val c = cur.peek()
                    if (c < 0 || c == ','.code || c == '}'.code || c == ']'.code ||
                        c == ' '.code || c == '\t'.code || c == '\r'.code || c == '\n'.code
                    ) {
                        return
                    }
                    cur.next()
                    out?.append(c.toChar())
                }
            }
        }

        /**
         * Reads only top-level speaker names from a potentially 30MB /studio_speakers JSON stream
         * using O(1) memory (never allocates arrays/tensors).
         */
        fun extractSpeakerNamesFromStream(reader: Reader): List<String> {
            val cur = JsonCharCursor(reader)
            cur.skipWhitespace()
            if (cur.next() != '{'.code) return emptyList()
            val names = mutableListOf<String>()
            cur.skipWhitespace()
            if (cur.peek() == '}'.code) {
                cur.next()
                return names
            }
            while (true) {
                val key = readJsonString(cur)
                cur.skipWhitespace()
                if (cur.next() != ':'.code) error("Ожидалось ':' после имени спикера")
                names += key
                consumeJsonValue(cur, out = null)
                cur.skipWhitespace()
                val sep = cur.next()
                if (sep == ','.code) continue
                if (sep == '}'.code || sep < 0) break
                error("Ожидалось ',' или '}' в /studio_speakers")
            }
            return names
        }

        /**
         * Streams /studio_speakers JSON and extracts ONLY the raw JSON object for the requested voice
         * (ignoring Venice_selected and skipping all unrelated speakers in O(1) memory).
         */
        fun extractSingleSpeakerJsonFromStream(
            reader: Reader,
            targetVoice: String?
        ): Pair<String, String>? {
            val cur = JsonCharCursor(reader)
            cur.skipWhitespace()
            if (cur.next() != '{'.code) return null
            cur.skipWhitespace()
            if (cur.peek() == '}'.code) {
                cur.next()
                return null
            }
            val cleanTarget = sanitizeVoiceName(targetVoice)
            var fallbackMatch: Pair<String, String>? = null
            while (true) {
                val key = readJsonString(cur)
                cur.skipWhitespace()
                if (cur.next() != ':'.code) error("Ожидалось ':' после имени спикера")
                val allowed = sanitizeVoiceName(key) != null
                val exactMatch = allowed && cleanTarget != null && key.equals(cleanTarget, ignoreCase = true)
                val takeAsFallback = allowed && fallbackMatch == null
                if (exactMatch || takeAsFallback) {
                    val sb = StringBuilder(64 * 1024)
                    consumeJsonValue(cur, out = sb)
                    val pair = key to sb.toString()
                    if (exactMatch || cleanTarget == null) {
                        return pair
                    }
                    fallbackMatch = pair
                } else {
                    consumeJsonValue(cur, out = null)
                }
                cur.skipWhitespace()
                val sep = cur.next()
                if (sep == ','.code) continue
                if (sep == '}'.code || sep < 0) break
                error("Ожидалось ',' или '}' в /studio_speakers")
            }
            return fallbackMatch
        }
        fun buildXttsRequestBuilder(url: String, settings: VoiceSettings): Request.Builder {
            val b = Request.Builder().url(url)
            if (settings.xttsCfClientId.isNotBlank()) {
                b.header("CF-Access-Client-Id", settings.xttsCfClientId.trim())
            }
            if (settings.xttsCfClientSecret.isNotBlank()) {
                b.header("CF-Access-Client-Secret", settings.xttsCfClientSecret.trim())
            }
            val bearer = settings.activeXttsBearerToken().trim()
            if (bearer.isNotBlank()) {
                b.header("Authorization", "Bearer $bearer")
            }
            return b
        }

        fun clampTtsSpeed(speed: Float): Float = speed.coerceIn(0.5f, 2.5f)

        fun computeTrackPlaybackSpeed(
            liveSpeed: Float,
            synthesizedSpeed: Float,
            serverAppliedSpeed: Boolean
        ): Float {
            val target = clampTtsSpeed(liveSpeed)
            return if (serverAppliedSpeed) {
                val base = clampTtsSpeed(synthesizedSpeed).coerceAtLeast(0.25f)
                (target / base).coerceIn(0.5f, 2.0f)
            } else {
                target.coerceIn(0.5f, 2.0f)
            }
        }

        fun buildXttsStreamPayload(
            text: String,
            language: String,
            speakerEmbedding: JsonArray,
            gptCondLatent: JsonArray,
            pitch: Float,
            keepTempo: Boolean,
            speed: Float = 1.0f
        ): JsonObject = buildJsonObject {
            put("text", JsonPrimitive(text))
            put("language", JsonPrimitive(language))
            put("speaker_embedding", speakerEmbedding)
            put("gpt_cond_latent", gptCondLatent)
            put("add_wav_header", JsonPrimitive(false))
            put("stream_chunk_size", JsonPrimitive(20))
            put("keep_tempo", JsonPrimitive(keepTempo))
            put("pitch", JsonPrimitive(pitch))
            put("speed", JsonPrimitive(clampTtsSpeed(speed)))
        }

        fun buildOpenAiSpeechPayload(
            model: String,
            input: String,
            voice: String,
            speed: Float = 1.0f
        ): JsonObject = buildJsonObject {
            put("model", JsonPrimitive(model))
            put("input", JsonPrimitive(input))
            put("voice", JsonPrimitive(voice))
            put("response_format", JsonPrimitive("mp3"))
            put("speed", JsonPrimitive(speed.coerceIn(0.25f, 4.0f)))
        }

        internal fun requestXttsChunk(
            httpClient: OkHttpClient,
            url: String,
            reqJson: JsonObject,
            settings: VoiceSettings,
            synthesizedSpeed: Float,
            maxAttempts: Int = 2,
            onCallStarted: (Call) -> Unit = {},
            onCallEnded: (Call) -> Unit = {},
            isStillActive: () -> Boolean = { true }
        ): XttsPcmChunk {
            val bodyBytes = reqJson.toString().toByteArray(Charsets.UTF_8)
            val mediaType = "application/json; charset=utf-8".toMediaType()
            var lastErr: IOException? = null
            for (attempt in 1..maxAttempts.coerceAtLeast(1)) {
                if (!isStillActive()) throw CancellationException("TTS playback cancelled")
                val req = buildXttsRequestBuilder(url, settings)
                    .header("Content-Type", "application/json")
                    .apply {
                        if (attempt > 1) header("Connection", "close")
                    }
                    .post(bodyBytes.toRequestBody(mediaType))
                    .build()
                val call = httpClient.newCall(req)
                onCallStarted(call)
                try {
                    call.execute().use { resp ->
                        if (!resp.isSuccessful) {
                            val err = resp.body?.string().orEmpty().take(300)
                            error("XTTS HTTP ${resp.code}: $err")
                        }
                        val serverAppliedSpeed = resp.header("X-XTTS-Speed-Applied") == "1"
                        val raw = resp.body?.bytes() ?: ByteArray(0)
                        val evenLen = raw.size and 1.inv()
                        val pcm = if (evenLen == raw.size) raw else raw.copyOf(evenLen)
                        return XttsPcmChunk(
                            pcm = pcm,
                            serverAppliedSpeed = serverAppliedSpeed,
                            synthesizedSpeed = synthesizedSpeed
                        )
                    }
                } catch (e: IOException) {
                    if (call.isCanceled() || !isStillActive()) {
                        throw CancellationException("TTS request cancelled", e)
                    }
                    lastErr = e
                    if (attempt >= maxAttempts) throw e
                    Thread.sleep(250L)
                } finally {
                    onCallEnded(call)
                }
            }
            throw (lastErr ?: EOFException("XTTS stream terminated unexpectedly"))
        }

        fun sanitizeForSpeech(raw: String, skipCodeBlocks: Boolean): String {
            var s = raw
            if (skipCodeBlocks) {
                s = s.replace(Regex("```[\\s\\S]*?```"), " [код приведён в сообщении] ")
            } else {
                s = s.replace("```", " ")
            }
            s = s.replace(Regex("`([^`]*)`"), "$1")
            s = s.replace(Regex("!\\[[^\\]]*\\]\\([^)]*\\)"), "")
            s = s.replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1")
            s = s.replace(Regex("https?://\\S+"), "")
            s = s.replace(Regex("^#{1,6}\\s*", RegexOption.MULTILINE), "")
            s = s.replace(Regex("[*_~>#|]"), " ")
            return s.replace(Regex("\\s{2,}"), " ").trim()
        }

        fun splitIntoChunks(text: String, maxLen: Int): List<String> {
            val limit = maxLen.coerceAtLeast(24)
            val normalized = text.replace(Regex("\\s+"), " ").trim()
            if (normalized.isEmpty()) return emptyList()

            val sentences = text.split(Regex("(?<=[.!?\\n])\\s+"))
                .map { it.replace(Regex("\\s+"), " ").trim() }
                .filter { it.isNotEmpty() }

            val pieces = mutableListOf<String>()
            fun splitOversize(segment: String) {
                if (segment.length <= limit) {
                    pieces += segment
                    return
                }
                val clauses = segment.split(Regex("(?<=[,;:\u2014\u2013-])\\s+"))
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                for (clause in clauses) {
                    if (clause.length <= limit) {
                        pieces += clause
                    } else {
                        val words = clause.split(' ').filter { it.isNotEmpty() }
                        val wordBuf = StringBuilder()
                        for (w in words) {
                            if (w.length > limit) {
                                if (wordBuf.isNotEmpty()) {
                                    pieces += wordBuf.toString().trim()
                                    wordBuf.clear()
                                }
                                w.chunked(limit).forEach { pieces += it }
                            } else if (wordBuf.isNotEmpty() && wordBuf.length + 1 + w.length > limit) {
                                pieces += wordBuf.toString().trim()
                                wordBuf.clear()
                                wordBuf.append(w)
                            } else {
                                if (wordBuf.isNotEmpty()) wordBuf.append(' ')
                                wordBuf.append(w)
                            }
                        }
                        if (wordBuf.isNotEmpty()) pieces += wordBuf.toString().trim()
                    }
                }
            }

            sentences.forEach { splitOversize(it) }

            val result = mutableListOf<String>()
            val cur = StringBuilder()
            for (p in pieces) {
                if (cur.isNotEmpty() && cur.length + 1 + p.length > limit) {
                    result += cur.toString().trim()
                    cur.clear()
                }
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(p)
            }
            if (cur.isNotEmpty()) result += cur.toString().trim()
            return result
        }
    }
}
