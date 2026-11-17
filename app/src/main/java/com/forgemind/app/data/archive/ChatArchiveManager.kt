package com.forgemind.app.data.archive

import androidx.room.withTransaction
import com.forgemind.app.data.db.ChatFolderEntity
import com.forgemind.app.data.db.ChatMessageEntity
import com.forgemind.app.data.db.ChatSessionEntity
import com.forgemind.app.data.db.ForgeMindDatabase
import com.forgemind.app.data.model.ChatAttachment
import com.forgemind.app.data.model.ConversationBranching
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
    val formatVersion: Int = 3,
    val exportedAt: Long = System.currentTimeMillis(),
    val folders: List<ChatFolderEntity> = emptyList(),
    val sessions: List<ChatSessionEntity> = emptyList(),
    val messages: List<ChatMessageEntity> = emptyList(),
    val compactionCheckpoints: List<com.forgemind.app.data.db.CompactionCheckpointEntity> = emptyList(),
    val assistants: List<com.forgemind.app.data.db.AssistantProfileEntity> = emptyList(),
    val memoryNotes: List<com.forgemind.app.data.db.MemoryNoteEntity> = emptyList(),
    val memoryProposals: List<com.forgemind.app.data.db.MemoryProposalEntity> = emptyList()
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

    fun sanitizeFileName(rawTitle: String, fallback: String = "chat"): String {
        val cleaned = rawTitle
            .replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]+"), " ")
            .trim()
            .replace(Regex("\\s+"), "_")
            .take(48)
        return cleaned.ifBlank { fallback }
    }

    fun exportSessionToMarkdown(
        session: ChatSessionEntity,
        messages: List<ChatMessageEntity>
    ): String {
        val activeMessages = ConversationBranching.resolve(messages, session.branchSelectionsJson).activeMessages
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
            for (msg in activeMessages) {
                val roleLabel = when (msg.role) {
                    "USER" -> "👤 Пользователь"
                    "ASSISTANT" -> "🤖 ${msg.modelId ?: session.modelId}"
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

            // Selected-chat exports include only their CHAT memory, never shared notes from other contexts.
            val memoryNotes = db.memoryDao().allNotes().filter { sessionIds == null || it.scopeType == "CHAT" && it.scopeKey in selectedIds }
            val memoryIds = memoryNotes.map { it.id }.toSet()
            val memoryProposals = db.memoryDao().allProposals().filter {
                it.sourceSessionId in selectedIds && (sessionIds == null || it.scopeType == "CHAT" && it.scopeKey in selectedIds) &&
                    (it.targetNoteId == null || it.targetNoteId in memoryIds)
            }
            val bundle = ChatArchiveBundle(
                assistants = db.assistantDao().all().filter { sessionIds == null || allSessions.any { chat -> chat.assistantId == it.id } },
                folders = allFolders,
                sessions = allSessions,
                messages = allMessages,
                compactionCheckpoints = db.sessionDao().allCompactionCheckpoints().filter { it.sessionId in selectedIds },
                memoryNotes = memoryNotes,
                memoryProposals = memoryProposals
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

                val msgsBySession = allMessages.groupBy { it.sessionId }
                allSessions.forEachIndexed { idx, s ->
                    val safeName = sanitizeFileName(s.title, "chat_${idx + 1}")
                    val shortId = s.id.take(6)
                    val mdText = exportSessionToMarkdown(s, msgsBySession[s.id].orEmpty())
                    zos.putNextEntry(ZipEntry("chats_markdown/${idx + 1}_${safeName}_$shortId.md"))
                    zos.write(mdText.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }

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

            require(bundle.formatVersion in 1..3) { "Неподдерживаемая версия архива" }
            db.withTransaction {
            val importedAssistants = bundle.assistants.map { it.id }.toSet()
            for (profile in bundle.assistants) {
                com.forgemind.app.data.memory.AssistantPolicy.validate(profile)
                db.assistantDao().importProfile(profile)
            }
            require(bundle.sessions.all { it.assistantId == null || it.assistantId in importedAssistants })
            for (folder in bundle.folders) {
                db.folderDao().upsert(folder)
            }
            for (session in bundle.sessions) {
                db.sessionDao().upsert(session)
            }
            // Existing notes win on ID collisions. Imported pending proposals stay pending.
            val importedSessions = bundle.sessions.map { it.id }.toSet()
            val importedFolders = bundle.folders.map { it.id }.toSet()
            for (note in bundle.memoryNotes) {
                com.forgemind.app.data.memory.MemoryPolicy.validateStored(note)
                require(note.scopeType != "ASSISTANT" || note.scopeKey in importedAssistants)
                require(note.chatId == null || note.chatId in importedSessions)
                require(note.folderId == null || note.folderId in importedFolders)
                db.memoryDao().importNote(note.copy(searchText = com.forgemind.app.data.memory.MemoryPolicy.normalize(note.title + "\n" + note.content)))
            }
            for (proposal in bundle.memoryProposals) {
                require(proposal.sourceSessionId in importedSessions && proposal.action in setOf("create", "update", "delete"))
                val scope = com.forgemind.app.data.memory.MemoryScope.valueOf(proposal.scopeType)
                val proposedNote = com.forgemind.app.data.db.MemoryNoteEntity(proposal.id, proposal.scopeType, proposal.scopeKey,
                    if (scope == com.forgemind.app.data.memory.MemoryScope.CHAT) proposal.scopeKey else null,
                    if (scope == com.forgemind.app.data.memory.MemoryScope.FOLDER) proposal.scopeKey else null,
                    proposal.title, proposal.content, "")
                com.forgemind.app.data.memory.MemoryPolicy.validateStored(proposedNote)
                require(proposedNote.scopeType != "ASSISTANT" || proposedNote.scopeKey in importedAssistants)
                require(proposedNote.chatId == null || proposedNote.chatId in importedSessions)
                require(proposedNote.folderId == null || proposedNote.folderId in importedFolders)
                require(if (proposal.action == "create") proposal.targetNoteId == null && proposal.expectedRevision == null
                    else bundle.memoryNotes.any { it.id == proposal.targetNoteId && it.scopeType == proposal.scopeType && it.scopeKey == proposal.scopeKey } && proposal.expectedRevision != null)
                require(proposedNote.chatId == null || proposedNote.chatId == proposal.sourceSessionId)
                db.memoryDao().insertProposal(proposal)
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
            db.sessionDao().importCompactionCheckpoints(bundle.compactionCheckpoints.filter { checkpoint ->
                bundle.sessions.any { it.id == checkpoint.sessionId }
            })
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
}
