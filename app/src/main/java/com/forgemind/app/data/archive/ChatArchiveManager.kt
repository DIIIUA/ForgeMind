package com.forgemind.app.data.archive

import com.forgemind.app.data.db.ChatFolderEntity
import com.forgemind.app.data.db.ChatMessageEntity
import com.forgemind.app.data.db.ChatSessionEntity
import com.forgemind.app.data.db.ForgeMindDatabase
import com.forgemind.app.data.model.ChatAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable
data class ChatArchiveBundle(
    val formatVersion: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val folders: List<ChatFolderEntity> = emptyList(),
    val sessions: List<ChatSessionEntity> = emptyList(),
    val messages: List<ChatMessageEntity> = emptyList()
)

data class ArchiveImportStats(
    val foldersImported: Int,
    val sessionsImported: Int,
    val messagesImported: Int,
    val filesRestored: Int
)

class ChatArchiveManager(
    private val db: ForgeMindDatabase,
    private val chatFilesDir: File
) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    fun exportSessionToMarkdown(
        session: ChatSessionEntity,
        messages: List<ChatMessageEntity>
    ): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return buildString {
            appendLine("# ${session.title}")
            appendLine()
            appendLine("- **Провайдер:** ${session.providerId}")
            appendLine("- **Модель:** ${session.modelId}")
            appendLine("- **Среда:** ${session.workspaceEnv} (`${session.workspacePath}`)")
            appendLine("- **Создан:** ${fmt.format(Date(session.createdAt))}")
            if (session.compactedSummary.isNotBlank()) {
                appendLine()
                appendLine("## Сжатая память диалога")
                appendLine(session.compactedSummary)
            }
            appendLine()
            appendLine("---")
            appendLine()
            for (msg in messages) {
                val roleLabel = when (msg.role) {
                    "USER" -> "👤 Пользователь"
                    "ASSISTANT" -> "🤖 ForgeMind (${msg.modelId ?: session.modelId})"
                    "TOOL" -> "🛠 Инструмент (${msg.toolName ?: ""})"
                    else -> "⚙️ ${msg.role}"
                }
                appendLine("### $roleLabel · ${fmt.format(Date(msg.createdAt))}")
                if (msg.thinking.isNotBlank()) {
                    appendLine("<details><summary>Ход мыслей</summary>")
                    appendLine()
                    appendLine(msg.thinking)
                    appendLine("</details>")
                    appendLine()
                }
                appendLine(msg.content)
                appendLine()
            }
        }
    }

    suspend fun exportFullZip(outputStream: OutputStream, sessionIds: Set<String>? = null): ArchiveImportStats =
        withContext(Dispatchers.IO) {
            val allFolders = db.folderDao().getAll()
            val allSessions = db.sessionDao().getAll().let { list ->
                if (sessionIds == null) list else list.filter { it.id in sessionIds }
            }
            val selectedIds = allSessions.map { it.id }.toSet()
            val allMessages = db.messageDao().getAll().filter { it.sessionId in selectedIds }

            val bundle = ChatArchiveBundle(
                folders = allFolders,
                sessions = allSessions,
                messages = allMessages
            )

            // Collect referenced attachment files inside chatFilesDir
            val attachedFiles = mutableSetOf<File>()
            for (msg in allMessages) {
                val atts = runCatching {
                    json.decodeFromString<List<ChatAttachment>>(msg.attachmentsJson)
                }.getOrDefault(emptyList())
                for (att in atts) {
                    val f = File(att.localPath)
                    if (f.exists() && f.isFile) {
                        attachedFiles += f
                    }
                }
            }

            ZipOutputStream(outputStream.buffered()).use { zos ->
                zos.putNextEntry(ZipEntry("manifest.json"))
                zos.write(json.encodeToString(bundle).toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                val baseCanon = chatFilesDir.canonicalFile
                for (file in attachedFiles) {
                    val rel = runCatching {
                        file.canonicalFile.relativeTo(baseCanon).invariantSeparatorsPath
                    }.getOrElse { file.name }
                    zos.putNextEntry(ZipEntry("attachments/$rel"))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }

            ArchiveImportStats(
                foldersImported = allFolders.size,
                sessionsImported = allSessions.size,
                messagesImported = allMessages.size,
                filesRestored = attachedFiles.size
            )
        }

    suspend fun importFullZip(inputStream: InputStream): ArchiveImportStats =
        withContext(Dispatchers.IO) {
            chatFilesDir.mkdirs()
            var manifestJson: String? = null
            var filesCount = 0

            ZipInputStream(inputStream.buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        if (entry.name == "manifest.json") {
                            manifestJson = zis.readBytes().toString(Charsets.UTF_8)
                        } else if (entry.name.startsWith("attachments/")) {
                            val rel = entry.name.removePrefix("attachments/").replace("..", "_")
                            val outFile = File(chatFilesDir, rel)
                            if (outFile.canonicalPath.startsWith(chatFilesDir.canonicalPath)) {
                                outFile.parentFile?.mkdirs()
                                outFile.outputStream().use { out -> zis.copyTo(out) }
                                filesCount++
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            val raw = manifestJson ?: error("В архиве не найден manifest.json")
            val bundle = json.decodeFromString<ChatArchiveBundle>(raw)

            for (folder in bundle.folders) {
                db.folderDao().upsert(folder)
            }
            for (session in bundle.sessions) {
                db.sessionDao().upsert(session)
            }
            val remappedMessages = bundle.messages.map { msg ->
                val atts = runCatching {
                    json.decodeFromString<List<ChatAttachment>>(msg.attachmentsJson)
                }.getOrDefault(emptyList())
                if (atts.isEmpty()) msg
                else {
                    val fixed = atts.map { att ->
                        val cur = File(att.localPath)
                        if (cur.exists()) att
                        else {
                            val restored = File(chatFilesDir, cur.name)
                            att.copy(localPath = restored.absolutePath)
                        }
                    }
                    msg.copy(attachmentsJson = json.encodeToString(fixed))
                }
            }
            if (remappedMessages.isNotEmpty()) {
                db.messageDao().upsertAll(remappedMessages)
            }

            ArchiveImportStats(
                foldersImported = bundle.folders.size,
                sessionsImported = bundle.sessions.size,
                messagesImported = remappedMessages.size,
                filesRestored = filesCount
            )
        }
}
