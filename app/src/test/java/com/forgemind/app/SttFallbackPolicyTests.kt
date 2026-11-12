package com.forgemind.app

import com.forgemind.app.data.model.SttProviderType
import com.forgemind.app.data.model.VoiceSettings
import com.forgemind.app.voice.SttFallbackPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SttFallbackPolicyTests {

    @Test
    fun `android fallback triggers only when enabled and primary engine is cloud`() {
        assertFalse(SttFallbackPolicy.shouldTryAndroidFallback(SttProviderType.GROQ_WHISPER, false))
        assertFalse(SttFallbackPolicy.shouldTryAndroidFallback(SttProviderType.OPENAI_WHISPER, false))
        assertTrue(SttFallbackPolicy.shouldTryAndroidFallback(SttProviderType.GROQ_WHISPER, true))
        assertTrue(SttFallbackPolicy.shouldTryAndroidFallback(SttProviderType.OPENAI_WHISPER, true))
        assertTrue(SttFallbackPolicy.shouldTryAndroidFallback(SttProviderType.CUSTOM_WHISPER, true))
        // Primary is already the offline recognizer - no point "falling back" to itself.
        assertFalse(SttFallbackPolicy.shouldTryAndroidFallback(SttProviderType.ANDROID_SPEECH, true))
    }

    @Test
    fun `cloud fallback triggers only when enabled and primary engine is offline`() {
        assertFalse(SttFallbackPolicy.shouldTryCloudFallback(SttProviderType.ANDROID_SPEECH, false))
        assertTrue(SttFallbackPolicy.shouldTryCloudFallback(SttProviderType.ANDROID_SPEECH, true))
        assertFalse(SttFallbackPolicy.shouldTryCloudFallback(SttProviderType.GROQ_WHISPER, true))
        assertFalse(SttFallbackPolicy.shouldTryCloudFallback(SttProviderType.CUSTOM_WHISPER, true))
    }

    @Test
    fun `cloud candidates are groq then openai and only for offline primary`() {
        assertEquals(
            listOf(SttProviderType.GROQ_WHISPER, SttProviderType.OPENAI_WHISPER),
            SttFallbackPolicy.cloudFallbackCandidates(SttProviderType.ANDROID_SPEECH)
        )
        assertTrue(SttFallbackPolicy.cloudFallbackCandidates(SttProviderType.GROQ_WHISPER).isEmpty())
        assertTrue(SttFallbackPolicy.cloudFallbackCandidates(SttProviderType.OPENAI_WHISPER).isEmpty())
    }

    @Test
    fun `stt auto fallback is disabled by default for backward compatibility`() {
        assertFalse(VoiceSettings().sttAutoFallback)
    }
}
