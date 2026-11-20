package com.forgemind.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.forgemind.app.R
import com.forgemind.app.data.model.TtsProviderType
import com.forgemind.app.data.model.VoiceSettings
import com.forgemind.app.llm.LlmHttpFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
import okhttp3.WebSocket
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

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _speakingMessageId = MutableStateFlow<String?>(null)
    val speakingMessageId: StateFlow<String?> = _speakingMessageId.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var speakJob: Job? = null
    @Volatile
    private var activeAudioTrack: AudioTrack? = null
    @Volatile
    private var activeMediaPlayer: MediaPlayer? = null
    @Volatile
    private var activeMediaSynthSpeed: Float = 1.0f
    private var androidTts: TextToSpeech? = null
    private var androidTtsReady = false
    private var androidTtsListenerBound = false
    private val activeSpeakToken = AtomicLong(0L)
    private val liveSpeed = AtomicReference(1.0f)
    private val activeCalls: MutableSet<Call> = Collections.synchronizedSet(mutableSetOf())
    private val activeWebSockets: MutableSet<WebSocket> = Collections.synchronizedSet(mutableSetOf())
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

    private fun applyLiveSpeedToMediaPlayer(mp: MediaPlayer) {
        val ratio = (liveSpeed.get() / activeMediaSynthSpeed).coerceIn(0.5f, 2.0f)
        runCatching {
            mp.playbackParams = mp.playbackParams.setSpeed(ratio)
        }
    }

    fun updateLiveSpeed(speed: Float) {
        val clamped = clampTtsSpeed(speed)
        liveSpeed.set(clamped)
        runCatching {
            activeMediaPlayer?.let { mp ->
                if (mp.isPlaying) {
                    applyLiveSpeedToMediaPlayer(mp)
                }
            }
        }
    }

    private data class SpeechTextChunk(
        val messageId: String?,
        val text: String
    )

    private val playbackLock = Any()
    private val streamInputLock = Any()
    private var streamJob: Job? = null
    private var streamWake: Channel<Unit>? = null
    private val streamCompletedSnapshots = ArrayDeque<Pair<String, String>>()
    private var streamCurrentMessageId: String? = null
    private var streamCurrentText = ""
    private var streamClosed = false
    @Volatile
    private var voiceInputActive = false

    /**
     * Barge-in: immediately silences any active TTS (regular or streaming) when the user taps the mic
     * and suppresses automatic TTS playback until voice capture ends.
     */
    fun onUserMicStarted() {
        voiceInputActive = true
        stop()
    }

    fun onUserMicFinished() {
        voiceInputActive = false
    }

    fun isVoiceInputSuppressingAutoSpeak(): Boolean = voiceInputActive

    fun speakAutoReply(messageId: String?, rawText: String, settings: VoiceSettings) {
        if (voiceInputActive || !settings.autoSpeakReplies || settings.streamSpeakReplies || rawText.isBlank()) return
        speak(messageId, rawText, settings)
    }

    fun startStreaming(settings: VoiceSettings) {
        stop()
        if (voiceInputActive) return
        val wake = Channel<Unit>(Channel.CONFLATED)
        synchronized(streamInputLock) {
            streamCompletedSnapshots.clear()
            streamCurrentMessageId = null
            streamCurrentText = ""
            streamClosed = false
            streamWake = wake
        }

        val token = cancelPlayback(resetState = false)
        liveSpeed.set(clampTtsSpeed(settings.ttsSpeed))
        _lastError.value = null

        val firstTarget = streamingFirstBatchLimitFor(settings.ttsProvider)
        val nextTarget = streamingBatchLimitFor(settings.ttsProvider)
        val minNextChars = minOf(nextTarget / 2, STREAM_NEXT_MIN_CHARS)

        val launched = scope.launch {
            val rawQueue = Channel<Pair<String, String>>(STREAM_RAW_QUEUE_CAPACITY)
            val chunkChannel = Channel<SpeechTextChunk>(8)
            try {
                coroutineScope {
                    // Decoupled from AgentEngine.stream.collect: coalesces live text snapshots off the
                    // LLM SSE thread so streaming TTS never blocks token generation or SQLite flushes.
                    val extractorJob = launch(Dispatchers.Default) {
                        var activeMsgId: String? = null
                        var consumedText = ""
                        var sentenceBuffer = StreamingSpeechText()

                        suspend fun processSnapshot(msgId: String, fullText: String) {
                            if (activeMsgId != null && activeMsgId != msgId) {
                                val prevId = activeMsgId!!
                                for (tail in sentenceBuffer.append("", finish = true)) {
                                    rawQueue.send(prevId to tail)
                                }
                                consumedText = ""
                                sentenceBuffer = StreamingSpeechText()
                            }
                            activeMsgId = msgId
                            if (fullText.length > consumedText.length && fullText.startsWith(consumedText)) {
                                val delta = fullText.substring(consumedText.length)
                                consumedText = fullText
                                for (sentence in sentenceBuffer.append(delta)) {
                                    rawQueue.send(msgId to sentence)
                                }
                            }
                        }

                        suspend fun drainSnapshots(): Boolean {
                            val completed = ArrayList<Pair<String, String>>()
                            val curId: String?
                            val curText: String
                            val closed: Boolean
                            synchronized(streamInputLock) {
                                if (streamWake !== wake) return true
                                while (streamCompletedSnapshots.isNotEmpty()) {
                                    completed += streamCompletedSnapshots.removeFirst()
                                }
                                curId = streamCurrentMessageId
                                curText = streamCurrentText
                                closed = streamClosed
                            }
                            for ((id, txt) in completed) {
                                if (!isActive || activeSpeakToken.get() != token || voiceInputActive) return true
                                processSnapshot(id, txt)
                            }
                            if (curId != null && isActive && activeSpeakToken.get() == token && !voiceInputActive) {
                                processSnapshot(curId, curText)
                            }
                            return closed
                        }

                        try {
                            while (isActive && activeSpeakToken.get() == token && !voiceInputActive) {
                                val sig = wake.receiveCatching()
                                val closed = drainSnapshots()
                                if (closed || sig.isClosed) break
                            }
                            if (isActive && activeSpeakToken.get() == token && !voiceInputActive) {
                                drainSnapshots()
                                val finalId = activeMsgId
                                if (finalId != null) {
                                    for (tail in sentenceBuffer.append("", finish = true)) {
                                        rawQueue.send(finalId to tail)
                                    }
                                }
                            }
                        } finally {
                            rawQueue.close()
                        }
                    }

                    val batcherJob = launch(Dispatchers.Default) {
                        try {
                            var pendingCarry: Pair<String, String>? = null
                            var isFirstBatch = true
                            while (isActive && activeSpeakToken.get() == token && !voiceInputActive) {
                                val first = pendingCarry ?: rawQueue.receiveCatching().getOrNull() ?: break
                                pendingCarry = null
                                val msgId = first.first
                                val batch = StringBuilder(first.second.trim())
                                val targetLen = if (isFirstBatch) firstTarget else nextTarget
                                val minDesiredChars = if (isFirstBatch) STREAM_FIRST_MIN_CHARS else minNextChars
                                val stepWaitMs = if (isFirstBatch) STREAM_FIRST_WAIT_MS else STREAM_NEXT_WAIT_MS
                                val maxWaitMs = if (isFirstBatch) STREAM_FIRST_WAIT_MS else STREAM_NEXT_MAX_WAIT_MS
                                val waitDeadline = System.currentTimeMillis() + maxWaitMs

                                // Drain everything already queued for this message up to targetLen.
                                while (pendingCarry == null && batch.length < targetLen) {
                                    val next = rawQueue.tryReceive().getOrNull() ?: break
                                    val cleanedNext = next.second.trim()
                                    if (cleanedNext.isEmpty()) continue
                                    if (next.first == msgId && (batch.isEmpty() || batch.length + 1 + cleanedNext.length <= targetLen)) {
                                        if (batch.isNotEmpty()) batch.append(' ')
                                        batch.append(cleanedNext)
                                    } else {
                                        pendingCarry = next
                                    }
                                }

                                // Coalesce short sentences so streaming TTS fetches larger, smooth segments
                                // instead of stuttering on half-sentence fragments.
                                while (pendingCarry == null && batch.length < minDesiredChars && isActive && activeSpeakToken.get() == token) {
                                    val remaining = waitDeadline - System.currentTimeMillis()
                                    if (remaining <= 0L) break
                                    val extra = withTimeoutOrNull(minOf(stepWaitMs, remaining)) {
                                        rawQueue.receiveCatching()
                                    } ?: break // timeout — speak what we have now
                                    if (extra.isClosed) break // stream finished
                                    val pair = extra.getOrNull() ?: break
                                    val cleanedExtra = pair.second.trim()
                                    if (cleanedExtra.isEmpty()) continue
                                    if (pair.first == msgId && (batch.isEmpty() || batch.length + 1 + cleanedExtra.length <= targetLen)) {
                                        if (batch.isNotEmpty()) batch.append(' ')
                                        batch.append(cleanedExtra)
                                        // Also drain any additional buffered items that arrived together.
                                        while (pendingCarry == null && batch.length < targetLen) {
                                            val more = rawQueue.tryReceive().getOrNull() ?: break
                                            val cleanedMore = more.second.trim()
                                            if (cleanedMore.isEmpty()) continue
                                            if (more.first == msgId && (batch.isEmpty() || batch.length + 1 + cleanedMore.length <= targetLen)) {
                                                if (batch.isNotEmpty()) batch.append(' ')
                                                batch.append(cleanedMore)
                                            } else {
                                                pendingCarry = more
                                            }
                                        }
                                    } else {
                                        pendingCarry = pair
                                    }
                                }

                                // Sentences emitted by StreamingSpeechText are already cleaned via cleanSpeechMarkdown.
                                val cleaned = batch.toString().trim()
                                if (cleaned.isNotEmpty()) {
                                    if (activeSpeakToken.get() == token && !_isSpeaking.value) {
                                        _isSpeaking.value = true
                                        _speakingMessageId.value = msgId
                                    }
                                    val parts = splitIntoChunks(cleaned, targetLen)
                                    for (part in parts) {
                                        chunkChannel.send(SpeechTextChunk(msgId, part))
                                    }
                                    isFirstBatch = false
                                }
                            }
                        } finally {
                            chunkChannel.close()
                        }
                    }

                    playChunkStream(chunkChannel, settings, token)
                    batcherJob.join()
                    extractorJob.join()
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (activeSpeakToken.get() == token && isActive) {
                    _lastError.value = formatTtsError(t)
                }
            } finally {
                rawQueue.close()
                chunkChannel.close()
                synchronized(streamInputLock) {
                    if (streamWake === wake) {
                        streamWake = null
                        streamCompletedSnapshots.clear()
                        streamCurrentMessageId = null
                        streamCurrentText = ""
                        streamClosed = false
                    }
                }
                if (activeSpeakToken.get() == token) {
                    _isSpeaking.value = false
                    _isPaused.value = false
                    _speakingMessageId.value = null
                }
            }
        }
        synchronized(playbackLock) {
            streamJob = launched
        }
    }

    fun pause() {
        synchronized(playbackLock) {
            if (!_isSpeaking.value || _isPaused.value) return
            _isPaused.value = true
            runCatching { activeAudioTrack?.pause() }
            runCatching {
                activeMediaPlayer?.let { mp ->
                    if (mp.isPlaying) mp.pause()
                }
            }
            runCatching { androidTts?.stop() }
        }
    }

    fun resume() {
        synchronized(playbackLock) {
            if (!_isSpeaking.value || !_isPaused.value) return
            _isPaused.value = false
            runCatching { activeAudioTrack?.play() }
            runCatching {
                activeMediaPlayer?.let { mp ->
                    if (!mp.isPlaying) {
                        mp.start()
                        applyLiveSpeedToMediaPlayer(mp)
                    }
                }
            }
        }
    }

    fun togglePause() {
        if (_isPaused.value) resume() else pause()
    }

    private suspend fun awaitIfPaused(token: Long) {
        while (_isPaused.value && activeSpeakToken.get() == token && currentCoroutineContext().isActive) {
            delay(50)
        }
    }

    fun updateStreaming(messageId: String, visibleText: String) {
        val wake: Channel<Unit>
        synchronized(streamInputLock) {
            val ch = streamWake ?: return
            if (streamClosed) return
            val prevId = streamCurrentMessageId
            if (prevId != null && prevId != messageId && streamCurrentText.isNotEmpty()) {
                if (streamCompletedSnapshots.size < 64) {
                    streamCompletedSnapshots.addLast(prevId to streamCurrentText)
                }
            }
            streamCurrentMessageId = messageId
            streamCurrentText = visibleText
            wake = ch
        }
        wake.trySend(Unit)
    }

    fun finishStreaming() {
        val wake: Channel<Unit>
        synchronized(streamInputLock) {
            val ch = streamWake ?: return
            streamClosed = true
            wake = ch
        }
        wake.trySend(Unit)
        wake.close()
    }

    fun speak(messageId: String?, rawText: String, settings: VoiceSettings) {
        clearStreamSession()
        val clean = sanitizeForSpeech(rawText, settings.skipCodeInTts)
        if (clean.isBlank()) {
            stop()
            return
        }
        val token = cancelPlayback(resetState = false)
        liveSpeed.set(clampTtsSpeed(settings.ttsSpeed))

        val chunks = splitIntoProgressiveChunks(
            text = clean,
            firstMaxLen = firstChunkLimitFor(settings.ttsProvider),
            nextMaxLen = fullChunkLimitFor(settings.ttsProvider)
        )
        if (chunks.isEmpty()) {
            stop()
            return
        }

        _isSpeaking.value = true
        _speakingMessageId.value = messageId
        _lastError.value = null

        val chunkChannel = Channel<SpeechTextChunk>(chunks.size.coerceAtLeast(1))
        for (chunk in chunks) {
            chunkChannel.trySend(SpeechTextChunk(messageId, chunk))
        }
        chunkChannel.close()

        val launched = scope.launch {
            try {
                playChunkStream(chunkChannel, settings, token)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (activeSpeakToken.get() == token && isActive) {
                    _lastError.value = formatTtsError(t)
                }
            } finally {
                if (activeSpeakToken.get() == token) {
                    _isSpeaking.value = false
                    _isPaused.value = false
                    _speakingMessageId.value = null
                }
            }
        }
        synchronized(playbackLock) {
            speakJob = launched
        }
    }

    private suspend fun playChunkStream(
        chunkChannel: ReceiveChannel<SpeechTextChunk>,
        settings: VoiceSettings,
        token: Long
    ) {
        when (settings.ttsProvider) {
            TtsProviderType.MICROSOFT_EDGE -> playEdgeStream(chunkChannel, settings, token)
            TtsProviderType.XTTS_V2, TtsProviderType.XTTS_EXTERNAL -> playXttsStream(chunkChannel, settings, token)
            TtsProviderType.OPENAI_TTS -> playOpenAiStream(chunkChannel, settings, token)
            TtsProviderType.ANDROID_TTS -> playAndroidStream(
                chunkChannel,
                settings.xttsLanguage.ifBlank { settings.sttLanguage },
                token
            )
        }
    }

    private fun clearStreamSession() {
        val oldWake: Channel<Unit>?
        synchronized(streamInputLock) {
            oldWake = streamWake
            streamWake = null
            streamCompletedSnapshots.clear()
            streamCurrentMessageId = null
            streamCurrentText = ""
            streamClosed = false
        }
        oldWake?.cancel()
        val oldJob: Job?
        synchronized(playbackLock) {
            oldJob = streamJob
            streamJob = null
        }
        oldJob?.cancel()
    }

    fun stop() {
        clearStreamSession()
        cancelPlayback(resetState = true)
    }

    private fun cancelActiveHttpCalls() {
        val snapshot = synchronized(activeCalls) { activeCalls.toList() }
        snapshot.forEach { call -> runCatching { call.cancel() } }
        synchronized(activeCalls) { activeCalls.clear() }
        val wsSnapshot = synchronized(activeWebSockets) { activeWebSockets.toList() }
        wsSnapshot.forEach { ws -> runCatching { ws.cancel() } }
        synchronized(activeWebSockets) { activeWebSockets.clear() }
    }

    private fun cancelPlayback(resetState: Boolean): Long {
        val nextToken = activeSpeakToken.incrementAndGet()
        val oldSpeakJob: Job?
        val oldAudioTrack: AudioTrack?
        val oldMediaPlayer: MediaPlayer?
        val oldAndroidTts: TextToSpeech?
        synchronized(playbackLock) {
            oldSpeakJob = speakJob
            speakJob = null
            oldAudioTrack = activeAudioTrack
            activeAudioTrack = null
            oldMediaPlayer = activeMediaPlayer
            activeMediaPlayer = null
            activeMediaSynthSpeed = 1.0f
            oldAndroidTts = androidTts
        }
        oldSpeakJob?.cancel()
        cancelActiveHttpCalls()
        utteranceWaiters.values.forEach { it.cancel() }
        utteranceWaiters.clear()
        _isPaused.value = false
        if (resetState) {
            _isSpeaking.value = false
            _speakingMessageId.value = null
        }
        if (oldAudioTrack != null || oldMediaPlayer != null || oldAndroidTts != null) {
            // Release native audio resources asynchronously on IO so Stop / barge-in / startStreaming
            // never block the UI thread or the LLM SSE reader.
            scope.launch(Dispatchers.IO) {
                runCatching { oldAudioTrack?.pause(); oldAudioTrack?.flush(); oldAudioTrack?.release() }
                runCatching { oldMediaPlayer?.stop(); oldMediaPlayer?.release() }
                runCatching { oldAndroidTts?.stop() }
            }
        }
        return nextToken
    }

    private fun formatTtsError(t: Throwable): String {
        val cls = t::class.java.simpleName
        val msg = t.message?.trim().orEmpty()
        return if (t is EOFException || t is ProtocolException || msg.contains("EOF", ignoreCase = true) || msg.contains("unexpected end of stream", ignoreCase = true)) {
            "TTS stream error ($cls): server closed the audio stream early. Check XTTS or retry."
        } else {
            "TTS error: ${msg.ifBlank { cls }}"
        }
    }

    suspend fun testAndLoadXttsVoices(settings: VoiceSettings): XttsHealthStatus =
        withContext(Dispatchers.IO) {
            runCatching {
                val base = settings.activeXttsBaseUrl().trim().trimEnd('/')
                if (base.isBlank()) {
                    error(
                        if (settings.ttsProvider == TtsProviderType.XTTS_EXTERNAL) {
                            "Specify external XTTS URL (e.g. https://....trycloudflare.com)"
                        } else {
                            "Specify local XTTS-v2 server URL (e.g. http://192.168.1.100:8020)"
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
                        error("Server returned an HTML login page (Cloudflare Access). Provide CF-Access-Client-Id and CF-Access-Client-Secret.")
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
                val defNote = defVoice?.let { ", default: $it" } ?: ""
                val modeTag = if (settings.ttsProvider == TtsProviderType.XTTS_EXTERNAL) "XTTS External" else "XTTS"
                XttsHealthStatus(
                    ok = true,
                    message = "$modeTag ready ($healthSummary$defNote, voices: ${voices.size})",
                    voices = voices,
                    defaultVoice = defVoice
                )
            }.getOrElse { e ->
                if (e is CancellationException) throw e
                XttsHealthStatus(ok = false, message = e.message ?: "Failed to connect to XTTS")
            }
        }

    private data class PreparedXttsChunk(
        val messageId: String?,
        val chunk: XttsPcmChunk
    )

    private suspend fun playXttsStream(
        chunkChannel: ReceiveChannel<SpeechTextChunk>,
        settings: VoiceSettings,
        token: Long
    ) = coroutineScope {
        val base = settings.activeXttsBaseUrl().trim().trimEnd('/')
        if (base.isBlank()) error("XTTS-v2 server URL is not configured")
        cleanupLegacySpeakerCache()

        val lang = settings.xttsLanguage.ifBlank { Locale.getDefault().language.ifBlank { "en" } }
        val audioChannel = Channel<PreparedXttsChunk>(XTTS_PREFETCH_CAPACITY)

        // Sequential producer avoids overloading single-GPU XTTS servers while keeping up to 3 chunks prefetched ahead.
        val producerJob = launch(Dispatchers.IO) {
            try {
                var latents: LoadedSpeakerLatents? = null
                for (item in chunkChannel) {
                    if (activeSpeakToken.get() != token || !isActive) break
                    val text = item.text.trim()
                    if (text.isEmpty()) continue
                    val spk = latents ?: getCachedOrFetchSingleSpeaker(base, settings).also { latents = it }
                    if (activeSpeakToken.get() != token || !isActive) break
                    val speedAtRequest = liveSpeed.get()
                    val reqJson = buildXttsStreamPayload(
                        text = text,
                        language = lang,
                        speakerEmbedding = spk.speakerEmbedding,
                        gptCondLatent = spk.gptCondLatent,
                        pitch = settings.xttsPitch,
                        keepTempo = settings.xttsKeepTempo,
                        speed = speedAtRequest
                    )
                    val pcmChunk = requestXttsChunk(
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
                    if (pcmChunk.pcm.isNotEmpty() && activeSpeakToken.get() == token && isActive) {
                        audioChannel.send(PreparedXttsChunk(item.messageId, pcmChunk))
                    }
                }
            } finally {
                audioChannel.close()
            }
        }

        var track: AudioTrack? = null
        var writtenFrames = 0L
        var appliedTrackSpeed = 1.0f
        var startedPlayback = false

        try {
            for (prepared in audioChannel) {
                if (activeSpeakToken.get() != token || !isActive) break
                val currentChunk = prepared.chunk
                val pcm = trimPcm16Silence(currentChunk.pcm)
                if (pcm.isEmpty()) continue

                if (prepared.messageId != null && activeSpeakToken.get() == token) {
                    _speakingMessageId.value = prepared.messageId
                }
                awaitIfPaused(token)
                if (activeSpeakToken.get() != token || !isActive) break

                val activeTrack = track ?: run {
                    val sampleRate = 24_000
                    val minBuf = AudioTrack.getMinBufferSize(
                        sampleRate,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    ).coerceAtLeast(9600)
                    AudioTrack.Builder()
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
                        .also {
                            track = it
                            activeAudioTrack = it
                        }
                }

                if (!startedPlayback) {
                    activeTrack.play()
                    startedPlayback = true
                }

                var offset = 0
                val writeSlice = 4800
                while (offset < pcm.size && activeSpeakToken.get() == token && isActive) {
                    if (_isPaused.value) {
                        runCatching { activeTrack.pause() }
                        awaitIfPaused(token)
                        if (activeSpeakToken.get() != token || !isActive) break
                        runCatching { activeTrack.play() }
                    }
                    val targetSpeed = computeTrackPlaybackSpeed(
                        liveSpeed = liveSpeed.get(),
                        synthesizedSpeed = currentChunk.synthesizedSpeed,
                        serverAppliedSpeed = currentChunk.serverAppliedSpeed
                    )
                    if (abs(targetSpeed - appliedTrackSpeed) > 0.02f) {
                        runCatching {
                            activeTrack.playbackParams = activeTrack.playbackParams.setSpeed(targetSpeed)
                            appliedTrackSpeed = targetSpeed
                        }
                    }
                    val count = minOf(writeSlice, pcm.size - offset)
                    val written = activeTrack.write(pcm, offset, count, AudioTrack.WRITE_NON_BLOCKING)
                    if (written < 0) error("AudioTrack write error: $written")
                    if (written == 0) {
                        delay(15)
                        continue
                    }
                    offset += written
                    writtenFrames += (written / 2)
                }
            }

            val drainTrack = track
            if (drainTrack != null && startedPlayback && writtenFrames > 0L && activeSpeakToken.get() == token && isActive) {
                val targetFrames = writtenFrames.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                var drainStart = System.currentTimeMillis()
                while (activeSpeakToken.get() == token && isActive) {
                    if (_isPaused.value) {
                        awaitIfPaused(token)
                        drainStart = System.currentTimeMillis()
                        continue
                    }
                    val head = runCatching { drainTrack.playbackHeadPosition }.getOrDefault(targetFrames)
                    if (head >= targetFrames) break
                    if (System.currentTimeMillis() - drainStart > 2500L) break
                    delay(40)
                }
            }
            producerJob.join()
        } finally {
            producerJob.cancel()
            audioChannel.cancel()
            track?.let { t ->
                runCatching {
                    t.pause()
                    t.flush()
                    t.release()
                }
                if (activeAudioTrack == t) activeAudioTrack = null
            }
        }
    }

    suspend fun loadMicrosoftEdgeVoices(forceRefresh: Boolean = false): Result<List<EdgeNeuralVoice>> =
        withContext(Dispatchers.IO) {
            runCatching {
                MicrosoftEdgeTtsClient.fetchVoiceList(client, forceRefresh = forceRefresh)
            }
        }

    private data class PreparedEdgeChunk(
        val messageId: String?,
        val bytes: ByteArray,
        val synthesizedSpeed: Float
    )

    private suspend fun playEdgeStream(
        chunkChannel: ReceiveChannel<SpeechTextChunk>,
        settings: VoiceSettings,
        token: Long
    ) = coroutineScope {
        val audioChannel = Channel<PreparedEdgeChunk>(EDGE_PREFETCH_CAPACITY)

        // Prefetch up to 3 large paragraph-sized MP3 chunks ahead so playback never pauses to reload.
        val producerJob = launch(Dispatchers.IO) {
            try {
                for (item in chunkChannel) {
                    if (activeSpeakToken.get() != token || !isActive) break
                    val sentence = item.text.trim()
                    if (sentence.isEmpty()) continue
                    val reqSpeed = liveSpeed.get().coerceIn(0.6f, 2.0f)
                    val resolvedVoice = MicrosoftEdgeTtsClient.resolveVoiceForText(
                        selectedVoice = settings.edgeTtsVoice,
                        text = sentence,
                        fallbackLanguage = settings.sttLanguage.ifBlank { settings.xttsLanguage }
                    )
                    val mp3Bytes = MicrosoftEdgeTtsClient.synthesizeMp3(
                        httpClient = client,
                        text = sentence,
                        voice = resolvedVoice,
                        rateMultiplier = reqSpeed,
                        pitchHz = settings.edgeTtsPitchHz,
                        onWebSocketOpened = { activeWebSockets.add(it) },
                        onWebSocketClosed = { activeWebSockets.remove(it) },
                        isStillActive = { activeSpeakToken.get() == token && isActive }
                    )
                    if (mp3Bytes.isNotEmpty() && activeSpeakToken.get() == token && isActive) {
                        audioChannel.send(PreparedEdgeChunk(item.messageId, mp3Bytes, reqSpeed))
                    }
                }
            } finally {
                audioChannel.close()
            }
        }

        var playIdx = 0
        try {
            for (synth in audioChannel) {
                if (activeSpeakToken.get() != token || !isActive) break
                if (synth.bytes.isEmpty()) { playIdx++; continue }
                if (synth.messageId != null && activeSpeakToken.get() == token) {
                    _speakingMessageId.value = synth.messageId
                }
                awaitIfPaused(token)
                if (activeSpeakToken.get() != token || !isActive) break

                val tempFile = File(context.cacheDir, "edge_tts_${token}_${playIdx++}_${System.currentTimeMillis()}.mp3")
                var currentMp: MediaPlayer? = null
                var publishedToActive = false
                try {
                    tempFile.writeBytes(synth.bytes)
                    if (activeSpeakToken.get() != token || !isActive) break
                    activeMediaSynthSpeed = synth.synthesizedSpeed
                    val done = CompletableDeferred<Unit>()
                    // Prepare and start MediaPlayer off the Main thread so codec init & Binder IPC
                    // never stall UI rendering or live token streaming.
                    val mp = MediaPlayer().apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        setDataSource(tempFile.absolutePath)
                        setOnCompletionListener { done.complete(Unit) }
                        setOnErrorListener { _, what, extra ->
                            done.completeExceptionally(
                                IllegalStateException("MediaPlayer error $what/$extra")
                            )
                            true
                        }
                        prepare()
                    }
                    currentMp = mp
                    val started = synchronized(playbackLock) {
                        if (activeSpeakToken.get() != token || !isActive) {
                            false
                        } else {
                            activeMediaPlayer = mp
                            publishedToActive = true
                            if (!_isPaused.value) {
                                mp.start()
                                val ratio = (liveSpeed.get() / synth.synthesizedSpeed).coerceIn(0.5f, 2.0f)
                                if (abs(ratio - 1.0f) > 0.02f) {
                                    applyLiveSpeedToMediaPlayer(mp)
                                }
                            }
                            true
                        }
                    }
                    if (!started) {
                        done.cancel()
                        break
                    }
                    done.await()
                } finally {
                    val toRelease = synchronized(playbackLock) {
                        val mp = currentMp
                        when {
                            mp == null -> null
                            activeMediaPlayer === mp -> {
                                activeMediaPlayer = null
                                activeMediaSynthSpeed = 1.0f
                                mp
                            }
                            !publishedToActive -> mp
                            else -> null
                        }
                    }
                    runCatching { toRelease?.stop() }
                    runCatching { toRelease?.release() }
                    runCatching { tempFile.delete() }
                }
            }
            producerJob.join()
        } finally {
            producerJob.cancel()
            audioChannel.cancel()
        }
    }

    private data class PreparedOpenAiChunk(
        val messageId: String?,
        val file: File,
        val synthesizedSpeed: Float
    )

    private suspend fun playOpenAiStream(
        chunkChannel: ReceiveChannel<SpeechTextChunk>,
        settings: VoiceSettings,
        token: Long
    ) = coroutineScope {
        val url = settings.openAiTtsUrl.ifBlank { "https://api.openai.com/v1/audio/speech" }.trim()
        val key = settings.openAiTtsKey.trim()
        if (key.isBlank() && url.contains("openai.com")) error("Specify OpenAI TTS API key")

        val audioChannel = Channel<PreparedOpenAiChunk>(OPENAI_PREFETCH_CAPACITY)

        val producerJob = launch(Dispatchers.IO) {
            var idx = 0
            try {
                for (item in chunkChannel) {
                    if (activeSpeakToken.get() != token || !isActive) break
                    val chunk = item.text.trim()
                    if (chunk.isEmpty()) continue
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
                    val tempFile = File(context.cacheDir, "tts_${token}_${idx++}_${System.currentTimeMillis()}.mp3")
                    val call = client.newCall(req)
                    activeCalls.add(call)
                    try {
                        call.execute().use { resp ->
                            if (!resp.isSuccessful) error("OpenAI TTS HTTP ${resp.code}: ${resp.body?.string().orEmpty().take(300)}")
                            resp.body?.byteStream()?.use { inp -> tempFile.outputStream().use { out -> inp.copyTo(out) } }
                        }
                        audioChannel.send(PreparedOpenAiChunk(item.messageId, tempFile, reqSpeed))
                    } catch (e: Throwable) {
                        runCatching { tempFile.delete() }
                        throw e
                    } finally {
                        activeCalls.remove(call)
                    }
                }
            } finally {
                audioChannel.close()
            }
        }

        try {
            for (prepared in audioChannel) {
                val tempFile = prepared.file
                var currentMp: MediaPlayer? = null
                var publishedToActive = false
                try {
                    if (activeSpeakToken.get() != token || !isActive) break
                    if (prepared.messageId != null && activeSpeakToken.get() == token) {
                        _speakingMessageId.value = prepared.messageId
                    }
                    awaitIfPaused(token)
                    if (activeSpeakToken.get() != token || !isActive) break
                    activeMediaSynthSpeed = prepared.synthesizedSpeed
                    val done = CompletableDeferred<Unit>()
                    val mp = MediaPlayer().apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        setDataSource(tempFile.absolutePath)
                        setOnCompletionListener { done.complete(Unit) }
                        setOnErrorListener { _, what, extra ->
                            done.completeExceptionally(IllegalStateException("MediaPlayer error $what/$extra"))
                            true
                        }
                        prepare()
                    }
                    currentMp = mp
                    val started = synchronized(playbackLock) {
                        if (activeSpeakToken.get() != token || !isActive) {
                            false
                        } else {
                            activeMediaPlayer = mp
                            publishedToActive = true
                            if (!_isPaused.value) {
                                mp.start()
                                val ratio = (liveSpeed.get() / prepared.synthesizedSpeed).coerceIn(0.5f, 2.0f)
                                if (abs(ratio - 1.0f) > 0.02f) {
                                    applyLiveSpeedToMediaPlayer(mp)
                                }
                            }
                            true
                        }
                    }
                    if (!started) {
                        done.cancel()
                        break
                    }
                    done.await()
                } finally {
                    val toRelease = synchronized(playbackLock) {
                        val mp = currentMp
                        when {
                            mp == null -> null
                            activeMediaPlayer === mp -> {
                                activeMediaPlayer = null
                                activeMediaSynthSpeed = 1.0f
                                mp
                            }
                            !publishedToActive -> mp
                            else -> null
                        }
                    }
                    runCatching { toRelease?.stop() }
                    runCatching { toRelease?.release() }
                    runCatching { tempFile.delete() }
                }
            }
            producerJob.join()
        } finally {
            producerJob.cancel()
            while (true) {
                val leftover = audioChannel.tryReceive().getOrNull() ?: break
                runCatching { leftover.file.delete() }
            }
            audioChannel.cancel()
        }
    }

    private suspend fun playAndroidStream(
        chunkChannel: ReceiveChannel<SpeechTextChunk>,
        language: String,
        token: Long
    ) {
        var initializedTts: TextToSpeech? = null
        var idx = 0

        for (item in chunkChannel) {
            if (activeSpeakToken.get() != token || !currentCoroutineContext().isActive) break
            val chunk = item.text.trim()
            if (chunk.isEmpty()) continue

            val tts = initializedTts ?: run {
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
                if (!ready.await()) error("Android system TTS failed to initialize")
                val engine = androidTts ?: return
                if (!androidTtsListenerBound) {
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {
                            utteranceId?.let { utteranceWaiters.remove(it)?.complete(Unit) }
                        }
                        @Suppress("OVERRIDE_DEPRECATION")
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            utteranceId?.let {
                                utteranceWaiters.remove(it)?.completeExceptionally(
                                    IllegalStateException("Android system TTS error")
                                )
                            }
                        }
                        override fun onError(utteranceId: String?, errorCode: Int) {
                            utteranceId?.let {
                                utteranceWaiters.remove(it)?.completeExceptionally(
                                    IllegalStateException("Android system TTS error (code $errorCode)")
                                )
                            }
                        }
                        override fun onStop(utteranceId: String?, interrupted: Boolean) {
                            utteranceId?.let { utteranceWaiters.remove(it)?.cancel() }
                        }
                    })
                    androidTtsListenerBound = true
                }
                val cleanLang = language.trim().lowercase()
                val locale = when {
                    cleanLang.startsWith("en") -> Locale.US
                    cleanLang.isNotBlank() -> Locale.forLanguageTag(cleanLang)
                    else -> Locale.getDefault()
                }
                engine.language = locale
                initializedTts = engine
                engine
            }

            if (item.messageId != null && activeSpeakToken.get() == token) {
                _speakingMessageId.value = item.messageId
            }

            while (activeSpeakToken.get() == token && currentCoroutineContext().isActive) {
                awaitIfPaused(token)
                if (activeSpeakToken.get() != token || !currentCoroutineContext().isActive) break
                tts.setSpeechRate(liveSpeed.get().coerceIn(0.5f, 2.5f))
                val uttId = "fm_${token}_${idx}_${System.nanoTime()}"
                val waiter = CompletableDeferred<Unit>()
                utteranceWaiters[uttId] = waiter
                val res = tts.speak(chunk, TextToSpeech.QUEUE_FLUSH, null, uttId)
                if (res != TextToSpeech.SUCCESS) {
                    utteranceWaiters.remove(uttId)
                    error("Android TTS failed to enqueue utterance (code $res)")
                }
                var pausedInterrupt = false
                try {
                    val timeoutMs = maxOf(45_000L, chunk.length * 120L)
                    withTimeoutOrNull(timeoutMs) {
                        waiter.await()
                    }
                } catch (cancel: CancellationException) {
                    if (_isPaused.value && activeSpeakToken.get() == token && currentCoroutineContext().isActive) {
                        pausedInterrupt = true
                    } else {
                        throw cancel
                    }
                } finally {
                    utteranceWaiters.remove(uttId)
                }
                if (pausedInterrupt) {
                    awaitIfPaused(token)
                    // Re-speak the paused chunk cleanly on resume without losing position in the message.
                    continue
                }
                break
            }
            idx++
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
            val stream = resp.body?.byteStream() ?: error("Empty /studio_speakers response")
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
                ?: error("No voice profiles found on XTTS-v2 server")
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
            val stream = resp.body?.byteStream() ?: error("Empty /studio_speakers response")
            val match = BufferedReader(InputStreamReader(stream, Charsets.UTF_8), 64 * 1024).use { reader ->
                extractSingleSpeakerJsonFromStream(reader, targetVoice)
            } ?: error("Voice \"$targetVoice\" not found on XTTS-v2 server")
            val obj = LlmHttpFactory.json.parseToJsonElement(match.second).jsonObject
            parseLatentsObject(match.first, obj)
        }
    }

    private fun parseLatentsObject(voiceName: String, obj: JsonObject): LoadedSpeakerLatents {
        val spkEmb = obj["speaker_embedding"]?.jsonArray
            ?: error("Missing speaker_embedding for voice $voiceName")
        val gptLat = obj["gpt_cond_latent"]?.jsonArray
            ?: error("Missing gpt_cond_latent for voice $voiceName")
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
        private const val RESERVED_META_SPEAKER = "__default__"

        fun sanitizeVoiceName(name: String?): String? =
            name?.trim()?.takeIf { it.isNotBlank() && !it.equals(RESERVED_META_SPEAKER, ignoreCase = true) }

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
            if (cur.next() != '"'.code) error("Expected JSON string")
            val sb = StringBuilder(32)
            while (true) {
                val c = cur.next()
                if (c < 0) error("Unexpected end of JSON string")
                when (c) {
                    '"'.code -> return sb.toString()
                    '\\'.code -> {
                        val esc = cur.next()
                        if (esc < 0) error("Unterminated escape sequence in JSON")
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
                                    if (h < 0) error("Unterminated \\uXXXX")
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
            if (first < 0) error("Unexpected end of JSON value")
            if (first == '{'.code || first == '['.code) {
                var depth = 0
                var inString = false
                var escaped = false
                while (true) {
                    val c = cur.next()
                    if (c < 0) break
                    if (out != null) {
                        if (out.length >= 8 * 1024 * 1024) error("Speaker object exceeds 8 MB")
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
                if (cur.next() != ':'.code) error("Expected ':' after speaker name")
                names += key
                consumeJsonValue(cur, out = null)
                cur.skipWhitespace()
                val sep = cur.next()
                if (sep == ','.code) continue
                if (sep == '}'.code || sep < 0) break
                error("Expected ',' or '}' in /studio_speakers")
            }
            return names
        }

        /**
         * Streams /studio_speakers JSON and extracts ONLY the raw JSON object for the requested voice
         * (skipping reserved meta entries and all unrelated speakers in O(1) memory).
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
                if (cur.next() != ':'.code) error("Expected ':' after speaker name")
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
                error("Expected ',' or '}' in /studio_speakers")
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

        const val XTTS_CHUNK_MAX_LEN = 220
        const val EDGE_FIRST_CHUNK_MAX_LEN = 520
        const val EDGE_CHUNK_MAX_LEN = 1500
        const val OPENAI_FIRST_CHUNK_MAX_LEN = 900
        const val OPENAI_CHUNK_MAX_LEN = 3800
        const val ANDROID_TTS_CHUNK_MAX_LEN = 1000
        private const val XTTS_PREFETCH_CAPACITY = 3
        private const val EDGE_PREFETCH_CAPACITY = 3
        private const val OPENAI_PREFETCH_CAPACITY = 2
        private const val STREAM_RAW_QUEUE_CAPACITY = 1024
        private const val STREAM_FIRST_MIN_CHARS = 110
        private const val STREAM_NEXT_MIN_CHARS = 420
        private const val STREAM_FIRST_WAIT_MS = 450L
        private const val STREAM_NEXT_WAIT_MS = 650L
        private const val STREAM_NEXT_MAX_WAIT_MS = 1400L

        fun firstChunkLimitFor(provider: TtsProviderType): Int = when (provider) {
            TtsProviderType.XTTS_V2, TtsProviderType.XTTS_EXTERNAL -> XTTS_CHUNK_MAX_LEN
            TtsProviderType.MICROSOFT_EDGE -> EDGE_FIRST_CHUNK_MAX_LEN
            TtsProviderType.OPENAI_TTS -> OPENAI_FIRST_CHUNK_MAX_LEN
            TtsProviderType.ANDROID_TTS -> ANDROID_TTS_CHUNK_MAX_LEN
        }

        fun fullChunkLimitFor(provider: TtsProviderType): Int = when (provider) {
            TtsProviderType.XTTS_V2, TtsProviderType.XTTS_EXTERNAL -> XTTS_CHUNK_MAX_LEN
            TtsProviderType.MICROSOFT_EDGE -> EDGE_CHUNK_MAX_LEN
            TtsProviderType.OPENAI_TTS -> OPENAI_CHUNK_MAX_LEN
            TtsProviderType.ANDROID_TTS -> ANDROID_TTS_CHUNK_MAX_LEN
        }

        fun streamingFirstBatchLimitFor(provider: TtsProviderType): Int = when (provider) {
            TtsProviderType.XTTS_V2, TtsProviderType.XTTS_EXTERNAL -> XTTS_CHUNK_MAX_LEN
            TtsProviderType.MICROSOFT_EDGE -> 360
            TtsProviderType.OPENAI_TTS -> 500
            TtsProviderType.ANDROID_TTS -> 320
        }

        fun streamingBatchLimitFor(provider: TtsProviderType): Int = when (provider) {
            TtsProviderType.XTTS_V2, TtsProviderType.XTTS_EXTERNAL -> XTTS_CHUNK_MAX_LEN
            TtsProviderType.MICROSOFT_EDGE -> EDGE_CHUNK_MAX_LEN
            TtsProviderType.OPENAI_TTS -> 2000
            TtsProviderType.ANDROID_TTS -> ANDROID_TTS_CHUNK_MAX_LEN
        }

        fun clampTtsSpeed(speed: Float): Float = speed.coerceIn(0.5f, 2.5f)

        /**
         * Trims excessive leading/trailing digital silence from 16-bit LE mono PCM chunks
         * so adjacent XTTS sentences don't accumulate multi-second artificial pauses.
         */
        fun trimPcm16Silence(
            pcm: ByteArray,
            sampleRate: Int = 24_000,
            keepLeadMs: Int = 25,
            keepTailMs: Int = 85,
            threshold: Int = 320
        ): ByteArray {
            val sampleCount = pcm.size / 2
            if (sampleCount == 0) return ByteArray(0)
            var firstActive = -1
            for (i in 0 until sampleCount) {
                val lo = pcm[i * 2].toInt() and 0xFF
                val hi = pcm[i * 2 + 1].toInt()
                val sample = (hi shl 8) or lo
                if (abs(sample) >= threshold) {
                    firstActive = i
                    break
                }
            }
            if (firstActive < 0) return ByteArray(0)
            var lastActive = firstActive
            for (i in (sampleCount - 1) downTo firstActive) {
                val lo = pcm[i * 2].toInt() and 0xFF
                val hi = pcm[i * 2 + 1].toInt()
                val sample = (hi shl 8) or lo
                if (abs(sample) >= threshold) {
                    lastActive = i
                    break
                }
            }
            val keepLeadSamples = (sampleRate.toLong() * keepLeadMs.coerceAtLeast(0) / 1000L).toInt()
            val keepTailSamples = (sampleRate.toLong() * keepTailMs.coerceAtLeast(0) / 1000L).toInt()
            val startSample = (firstActive - keepLeadSamples).coerceAtLeast(0)
            val endSampleExclusive = (lastActive + 1 + keepTailSamples).coerceAtMost(sampleCount)
            if (startSample == 0 && endSampleExclusive == sampleCount) return pcm
            return pcm.copyOfRange(startSample * 2, endSampleExclusive * 2)
        }

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

        private val SPLIT_WHITESPACE_REGEX = Regex("\\s+")
        private val SPLIT_SENTENCE_REGEX = Regex("(?<=[.!?\\n])\\s+")
        private val SPLIT_CLAUSE_REGEX = Regex("(?<=[,;:\u2014\u2013-])\\s+")

        fun sanitizeForSpeech(raw: String, skipCodeBlocks: Boolean): String =
            cleanSpeechMarkdown(if (skipCodeBlocks) raw else raw.replace("```", "").replace("~~~", ""))

        fun splitIntoProgressiveChunks(text: String, firstMaxLen: Int, nextMaxLen: Int): List<String> {
            val firstLimit = firstMaxLen.coerceAtLeast(24)
            val nextLimit = nextMaxLen.coerceAtLeast(24)
            if (firstLimit >= nextLimit) return splitIntoChunks(text, nextLimit)
            val normalized = text.replace(SPLIT_WHITESPACE_REGEX, " ").trim()
            if (normalized.isEmpty()) return emptyList()
            if (normalized.length <= firstLimit) return listOf(normalized)

            val sentences = text.split(SPLIT_SENTENCE_REGEX)
                .map { it.replace(SPLIT_WHITESPACE_REGEX, " ").trim() }
                .filter { it.isNotEmpty() }
            if (sentences.isEmpty()) return emptyList()

            val result = mutableListOf<String>()
            val firstBuf = StringBuilder()
            var idx = 0
            while (idx < sentences.size) {
                val s = sentences[idx]
                if (firstBuf.isEmpty()) {
                    if (s.length <= firstLimit) {
                        firstBuf.append(s)
                        idx++
                    } else {
                        val splitFirst = splitIntoChunks(s, firstLimit)
                        if (splitFirst.isNotEmpty()) {
                            result += splitFirst.first()
                            val remainder = splitFirst.drop(1).joinToString(" ")
                            val tail = buildList {
                                if (remainder.isNotBlank()) add(remainder)
                                for (j in (idx + 1) until sentences.size) add(sentences[j])
                            }.joinToString(" ")
                            if (tail.isNotBlank()) result += splitIntoChunks(tail, nextLimit)
                            return result
                        }
                        idx++
                    }
                } else if (firstBuf.length + 1 + s.length <= firstLimit) {
                    firstBuf.append(' ').append(s)
                    idx++
                } else {
                    break
                }
            }
            if (firstBuf.isNotEmpty()) {
                result += firstBuf.toString().trim()
            }
            if (idx < sentences.size) {
                val rest = sentences.subList(idx, sentences.size).joinToString(" ")
                result += splitIntoChunks(rest, nextLimit)
            }
            return result
        }

        fun splitIntoChunks(text: String, maxLen: Int): List<String> {
            val limit = maxLen.coerceAtLeast(24)
            val normalized = text.replace(SPLIT_WHITESPACE_REGEX, " ").trim()
            if (normalized.isEmpty()) return emptyList()

            val sentences = text.split(SPLIT_SENTENCE_REGEX)
                .map { it.replace(SPLIT_WHITESPACE_REGEX, " ").trim() }
                .filter { it.isNotEmpty() }

            val pieces = mutableListOf<String>()
            fun splitOversize(segment: String) {
                if (segment.length <= limit) {
                    pieces += segment
                    return
                }
                val clauses = segment.split(SPLIT_CLAUSE_REGEX)
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
