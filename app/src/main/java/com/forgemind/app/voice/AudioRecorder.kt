package com.forgemind.app.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs

class AudioRecorder {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private var recordJob: Job? = null
    private var recorder: AudioRecord? = null
    private var currentFile: File? = null

    @SuppressLint("MissingPermission")
    fun startRecording(outputFile: File): Result<Unit> {
        if (_isRecording.value) return Result.success(Unit)
        return runCatching {
            val sampleRate = 16_000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding).coerceAtLeast(4096)

            val ar = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                encoding,
                minBuf * 2
            )
            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                ar.release()
                error("Не удалось инициализировать микрофон (AudioRecord)")
            }

            outputFile.parentFile?.mkdirs()
            currentFile = outputFile
            recorder = ar
            ar.startRecording()
            _isRecording.value = true

            recordJob = scope.launch {
                val pcmOut = ByteArrayOutputStream()
                val buf = ByteArray(minBuf)
                try {
                    while (isActive && _isRecording.value) {
                        val read = ar.read(buf, 0, buf.size)
                        if (read > 1) {
                            pcmOut.write(buf, 0, read)
                            var peak = 0
                            var i = 0
                            while (i + 1 < read) {
                                val sample = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
                                val a = abs(sample)
                                if (a > peak) peak = a
                                i += 2
                            }
                            _amplitude.value = (peak / 32767f).coerceIn(0f, 1f)
                        }
                    }
                } finally {
                    runCatching { ar.stop() }
                    runCatching { ar.release() }
                    writeWavFile(outputFile, pcmOut.toByteArray(), sampleRate, 1, 16)
                    _amplitude.value = 0f
                }
            }
        }
    }

    suspend fun stopRecording(): File? {
        if (!_isRecording.value) return currentFile
        _isRecording.value = false
        recordJob?.join()
        recordJob = null
        recorder = null
        return currentFile?.takeIf { it.exists() && it.length() > 44 }
    }

    fun cancelRecording() {
        _isRecording.value = false
        recordJob?.cancel()
        recordJob = null
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        _amplitude.value = 0f
        currentFile?.let { runCatching { if (it.exists()) it.delete() } }
        currentFile = null
    }

    companion object {
        fun writeWavFile(file: File, pcmData: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int) {
            val byteRate = sampleRate * channels * bitsPerSample / 8
            val blockAlign = (channels * bitsPerSample / 8).toShort()
            val dataLen = pcmData.size
            val chunkLen = 36 + dataLen

            file.outputStream().use { out ->
                fun writeStr(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
                fun writeIntLE(v: Int) {
                    out.write(byteArrayOf(
                        (v and 0xff).toByte(),
                        ((v shr 8) and 0xff).toByte(),
                        ((v shr 16) and 0xff).toByte(),
                        ((v shr 24) and 0xff).toByte()
                    ))
                }
                fun writeShortLE(v: Short) {
                    out.write(byteArrayOf((v.toInt() and 0xff).toByte(), ((v.toInt() shr 8) and 0xff).toByte()))
                }
                writeStr("RIFF")
                writeIntLE(chunkLen)
                writeStr("WAVE")
                writeStr("fmt ")
                writeIntLE(16)
                writeShortLE(1)
                writeShortLE(channels.toShort())
                writeIntLE(sampleRate)
                writeIntLE(byteRate)
                writeShortLE(blockAlign)
                writeShortLE(bitsPerSample.toShort())
                writeStr("data")
                writeIntLE(dataLen)
                out.write(pcmData)
            }
        }
    }
}
