package com.forgemind.app.data.memory

import android.content.Context
import ai.onnxruntime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.LongBuffer
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.tanh

/** All text/vector computation stays on-device. Download only fetches fixed public model files. */
class LocalSemanticSearch(context: Context) {
    val modelDirectory = File(context.noBackupFilesDir, "semantic-model-v1")
    private val cacheDirectory = File(context.cacheDir, "semantic-vectors-v1")
    val status = MutableStateFlow(if(filesPresent()) "Модель готова к локальному поиску" else "Модель поиска не загружена")
    val busy = MutableStateFlow(false)
    val ready = MutableStateFlow(filesPresent())
    private val lock = Mutex()
    private var verified = false
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private fun filesPresent() = MODEL_FILES.all { File(modelDirectory, it.name).length() == it.bytes }
    private fun sha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer,0,n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    suspend fun download() = withContext(Dispatchers.IO) { lock.withLock {
        busy.value = true
        try {
            modelDirectory.mkdirs()
            MODEL_FILES.forEach { spec ->
                ensureActive()
                val destination = File(modelDirectory, spec.name)
                if (destination.length() != spec.bytes || sha(destination) != spec.sha256) {
                    val part = File(modelDirectory, spec.name + ".part")
                    try {
                        val call = client.newCall(Request.Builder().url(spec.url).build())
                        status.value = "Загрузка ${spec.name}: 0%"
                        call.execute().use { response ->
                            check(response.isSuccessful) { "HTTP ${response.code} при загрузке модели" }
                            response.body!!.byteStream().use { input -> part.outputStream().use { output ->
                                val buffer = ByteArray(65536); var size = 0L
                                while (true) {
                                    ensureActive()
                                    val n = input.read(buffer); if (n < 0) break
                                    size += n; check(size <= spec.bytes) { "Неожиданный размер модели" }
                                    output.write(buffer,0,n)
                                    status.value = "Загрузка ${spec.name}: ${size * 100 / spec.bytes}%"
                                }
                            } }
                        }
                        check(part.length() == spec.bytes && sha(part) == spec.sha256) { "Проверка модели не пройдена. Повторите загрузку" }
                        java.nio.file.Files.move(part.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    } finally { part.delete() }
                }
            }
            verified = true; ready.value = true; status.value = "Модель готова. Поиск работает локально"
        } catch (cancel: CancellationException) { status.value = "Загрузка отменена"; throw cancel }
        catch (error: Exception) { status.value = error.message.orEmpty(); throw error }
        finally { busy.value = false; ready.value = filesPresent() }
    } }
    suspend fun remove() = withContext(Dispatchers.IO) { lock.withLock {
        // Only the owned, fixed-name model files and vector cache are removed.
        MODEL_FILES.forEach { File(modelDirectory,it.name).delete(); File(modelDirectory,it.name + ".part").delete() }
        cacheDirectory.listFiles()?.filter { it.name.matches(Regex("[a-f0-9]{64}\\.json")) }?.forEach { it.delete() }
        verified = false; ready.value = false; status.value = "Модель поиска удалена; записи памяти сохранены"
    } }
    suspend fun rank(query: String, documents: List<SemanticDocument>): List<SemanticHit> = withContext(Dispatchers.Default) { lock.withLock {
        require(query.isNotBlank() && query.length <= 8000) { "Запрос: 1–8000 символов" }
        check(filesPresent()) { "Скачайте модель: меню чата → Память → Скачать модель поиска" }
        require(documents.map { it.id }.distinct().size == documents.size)
        busy.value = true
        try {
            if (!verified) {
                status.value = "Проверка файлов модели"
                check(MODEL_FILES.all { sha(File(modelDirectory,it.name)) == it.sha256 }) { "Файлы модели повреждены. Удалите модель и скачайте заново" }
                verified = true
            }
            val tokenizer = WordPieceTokenizer(File(modelDirectory,"vocab.txt").readLines())
            val dense = DenseProjection(File(modelDirectory,"dense.safetensors").readBytes())
            cacheDirectory.mkdirs()
            val environment = OrtEnvironment.getEnvironment()
            OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(2); options.setInterOpNumThreads(1)
                environment.createSession(File(modelDirectory,"encoder.onnx").absolutePath,options).use { session ->
                    suspend fun vector(text: String): List<FloatArray> {
                        val key = MessageDigest.getInstance("SHA-256").digest((MODEL_ID + "\n" + text).toByteArray()).joinToString("") { "%02x".format(it) }
                        val file = File(cacheDirectory,"$key.json")
                        val cached = runCatching { Json.decodeFromString<List<List<Float>>>(file.readText()).map { it.toFloatArray() } }.getOrNull()
                        if (cached != null && cached.isNotEmpty() && cached.all { it.size == 512 && it.all(Float::isFinite) }) return cached
                        val result = tokenizer.chunks(text).map { ids ->
                            ensureActive()
                            val shape = longArrayOf(1,ids.size.toLong())
                            OnnxTensor.createTensor(environment,LongBuffer.wrap(ids),shape).use { input ->
                                OnnxTensor.createTensor(environment,LongBuffer.wrap(LongArray(ids.size) { 1L }),shape).use { mask ->
                                    session.run(mapOf("input_ids" to input,"attention_mask" to mask)).use { output ->
                                        @Suppress("UNCHECKED_CAST")
                                        val hidden = (output[0].value as Array<Array<FloatArray>>)[0]
                                        check(hidden.size == ids.size && hidden[0].size == 768)
                                        val mean = FloatArray(768) { col -> hidden.sumOf { it[col].toDouble() }.toFloat() / hidden.size }
                                        SemanticMath.normalize(dense.project(mean))
                                    }
                                }
                            }
                        }
                        ensureActive()
                        file.writeText(Json.encodeToString(result.map { it.toList() }))
                        return result
                    }
                    val q = vector(query)
                    val hits = documents.mapIndexed { index, document ->
                        ensureActive(); status.value = "Поиск по смыслу: ${index + 1}/${documents.size}"
                        SemanticHit(document.id, SemanticMath.score(q,vector(document.text)))
                    }.sortedWith(compareByDescending<SemanticHit> { it.score }.thenBy { it.id })
                    // A disposable, content-addressed cache. Edits never reuse a stale vector.
                    cacheDirectory.listFiles()?.filter { it.name.matches(Regex("[a-f0-9]{64}\\.json")) }?.sortedByDescending { it.lastModified() }?.drop(1024)?.forEach { it.delete() }
                    status.value = "Поиск завершён: ${documents.size} записей"
                    hits
                }
            }
        } catch (cancel: CancellationException) { status.value = "Поиск отменён"; throw cancel }
        catch (error: Exception) { status.value = error.message.orEmpty(); throw error }
        finally { busy.value = false; ready.value = filesPresent() }
    } }

    private class DenseProjection(bytes: ByteArray) {
        private val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        private val headerLength = buffer.long.toInt()
        private val metadata = Json.parseToJsonElement(String(bytes,8,headerLength,Charsets.UTF_8)).jsonObject
        private fun tensor(name: String, size: Int): FloatArray {
            val tensor = metadata.getValue(name).jsonObject
            check(tensor["dtype"]!!.jsonPrimitive.content == "F32")
            val offsets = tensor["data_offsets"]!!.jsonArray
            val start = offsets[0].jsonPrimitive.int
            check(offsets[1].jsonPrimitive.int - start == size * 4)
            buffer.position(8 + headerLength + start)
            return FloatArray(size) { buffer.float }
        }
        private val bias = tensor("linear.bias",512)
        private val weight = tensor("linear.weight",512 * 768)
        fun project(input: FloatArray): FloatArray = FloatArray(512) { row ->
            var sum = bias[row]
            for (col in 0 until 768) sum += weight[row * 768 + col] * input[col]
            tanh(sum)
        }
    }
    data class ModelFile(val name: String,val url: String,val bytes: Long,val sha256: String)
    companion object {
        const val MODEL_ID = "distiluse-multilingual-v2-bfe45d0-u8u8-mean-dense-tanh512-wordpiece128-v2"
        val MODEL_FILES = listOf(
            ModelFile("encoder.onnx","https://huggingface.co/sentence-transformers/distiluse-base-multilingual-cased-v2/resolve/bfe45d0732ca50787611c0fe107ba278c7f3f889/onnx/model_quint8_avx2.onnx",135377779L,"6a5852e0da9ca0e4532274b6c5eed71f9938fa8ff15e8345c6873a1969093f80"),
            ModelFile("vocab.txt","https://huggingface.co/sentence-transformers/distiluse-base-multilingual-cased-v2/resolve/bfe45d0732ca50787611c0fe107ba278c7f3f889/vocab.txt",995526L,"fe0fda7c425b48c516fc8f160d594c8022a0808447475c1a7c6d6479763f310c"),
            ModelFile("dense.safetensors","https://huggingface.co/sentence-transformers/distiluse-base-multilingual-cased-v2/resolve/bfe45d0732ca50787611c0fe107ba278c7f3f889/2_Dense/model.safetensors",1575104L,"0a21b1ce908e772ebf09f93c20ca09524c32706e9918d9c0169a3f0663b191ed")
        )
    }
}
