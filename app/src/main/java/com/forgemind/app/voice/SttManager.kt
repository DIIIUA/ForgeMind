package com.forgemind.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.forgemind.app.data.model.ProviderType
import com.forgemind.app.data.model.SttProviderType
import com.forgemind.app.data.model.VoiceSettings
import com.forgemind.app.data.prefs.SettingsRepository
import com.forgemind.app.llm.LlmHttpFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

class SttManager(
    private val context: Context,
    private val settingsRepo: SettingsRepository
) {
    private val client = com.forgemind.app.llm.LlmHttpFactory.baseClientBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun transcribeAudioFile(audioFile: File, voice: VoiceSettings): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!audioFile.exists() || audioFile.length() < 512) {
                    error("Запись слишком короткая или пустая")
                }
                val (url, apiKey, model) = resolveEndpointAndKey(voice)
                if (apiKey.isBlank() && voice.sttProvider != SttProviderType.CUSTOM_WHISPER) {
                    error("Не указан API-ключ для STT (${voice.sttProvider.label}). Задайте ключ в Настройках -> Голос или в Groq/OpenAI.")
                }

                val multipart = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "file",
                        audioFile.name,
                        audioFile.asRequestBody("audio/wav".toMediaType())
                    )
                    .addFormDataPart("model", model)
                    .apply {
                        if (voice.sttLanguage.isNotBlank() && voice.sttLanguage != "auto") {
                            addFormDataPart("language", voice.sttLanguage)
                        }
                    }
                    .addFormDataPart("response_format", "json")
                    .build()

                val reqBuilder = Request.Builder()
                    .url(url)
                    .post(multipart)
                if (apiKey.isNotBlank()) {
                    reqBuilder.header("Authorization", "Bearer $apiKey")
                }

                client.newCall(reqBuilder.build()).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        error("STT HTTP ${resp.code}: ${raw.take(400)}")
                    }
                    val obj = LlmHttpFactory.json.parseToJsonElement(raw).jsonObject
                    val text = obj["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    if (text.isBlank()) error("Модель не распознала речь")
                    text
                }
            }
        }

    suspend fun recognizeWithAndroidSpeech(languageTag: String = "ru-RU"): Result<String> =
        withContext(Dispatchers.Main) {
            runCatching {
                if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                    error("На этом устройстве недоступен встроенный SpeechRecognizer. Используйте Groq/OpenAI Whisper.")
                }
                val deferred = CompletableDeferred<Result<String>>()
                val sr = SpeechRecognizer.createSpeechRecognizer(context)
                sr.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                    override fun onError(error: Int) {
                        sr.destroy()
                        deferred.complete(Result.failure(IllegalStateException("Android STT error code=$error")))
                    }
                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val best = matches?.firstOrNull()?.trim().orEmpty()
                        sr.destroy()
                        if (best.isBlank()) deferred.complete(Result.failure(IllegalStateException("Речь не распознана")))
                        else deferred.complete(Result.success(best))
                    }
                })
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (languageTag == "ru") "ru-RU" else languageTag)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                }
                sr.startListening(intent)
                val res = withTimeoutOrNull(25_000) { deferred.await() }
                if (res == null) {
                    runCatching { sr.destroy() }
                    error("Таймаут встроенного распознавания речи")
                }
                res.getOrThrow()
            }
        }

    private fun resolveEndpointAndKey(voice: VoiceSettings): Triple<String, String, String> {
        return when (voice.sttProvider) {
            SttProviderType.GROQ_WHISPER -> {
                val base = voice.sttBaseUrl.ifBlank { "https://api.groq.com/openai/v1" }.trim().trimEnd('/')
                val url = if (base.endsWith("/audio/transcriptions")) base else "$base/audio/transcriptions"
                val key = voice.sttApiKey.ifBlank {
                    settingsRepo.getProviderConfig(ProviderType.GROQ.id).parsedKeys().firstOrNull().orEmpty()
                }
                val model = voice.sttModel.ifBlank { "whisper-large-v3-turbo" }
                Triple(url, key, model)
            }
            SttProviderType.OPENAI_WHISPER -> {
                val url = "https://api.openai.com/v1/audio/transcriptions"
                val key = voice.sttApiKey.ifBlank {
                    settingsRepo.getProviderConfig(ProviderType.OPENAI.id).parsedKeys().firstOrNull().orEmpty()
                }
                val model = voice.sttModel.ifBlank { "gpt-4o-mini-transcribe" }
                Triple(url, key, model)
            }
            SttProviderType.CUSTOM_WHISPER -> {
                val base = voice.sttBaseUrl.trim().trimEnd('/')
                val url = if (base.endsWith("/transcriptions")) base else "$base/audio/transcriptions"
                Triple(url, voice.sttApiKey, voice.sttModel.ifBlank { "whisper-1" })
            }
            SttProviderType.ANDROID_SPEECH -> Triple("", "", "")
        }
    }
}
