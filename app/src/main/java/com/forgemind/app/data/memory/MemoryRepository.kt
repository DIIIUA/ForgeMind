package com.forgemind.app.data.memory

import androidx.room.withTransaction
import com.forgemind.app.data.db.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

class MemoryRepository(private val db: ForgeMindDatabase) {
    private val dao = db.memoryDao()
    suspend fun context(chatId: String, provider: String? = null, model: String? = null): MemoryContext {
        val chat = db.sessionDao().getById(chatId) ?: error("Чат больше не существует")
        return MemoryContext(chat.id, chat.folderId, provider ?: chat.providerId, model ?: chat.modelId, chat.assistantId)
    }
    suspend fun visible(context: MemoryContext, query: String = "", limit: Int = 100, offset: Int = 0) =
        dao.visible(context.chatId, context.folderId, context.modelKey, MemoryPolicy.normalize(query), limit = limit.coerceIn(1, 100), offset = offset.coerceAtLeast(0), assistant = context.assistantId)
    suspend fun requestBlock(context: MemoryContext, budget: Int): String = db.withTransaction { MemoryPolicy.requestBlock(
        dao.visible(context.chatId, context.folderId, context.modelKey, limit = 128, assistant = context.assistantId),
        dao.countVisible(context.chatId, context.folderId, context.modelKey, assistant = context.assistantId), budget) }
    suspend fun semanticSearch(context: MemoryContext, query: String, engine: LocalSemanticSearch, limit: Int = 20, offset: Int = 0): Pair<Int, List<Pair<MemoryNoteEntity, Float>>> {
        require(query.isNotBlank() && query.length <= 8000) { "Запрос: 1–8000 символов" }
        val index = dao.index(context.chatId, context.folderId, context.modelKey, context.assistantId)
        val expected = index.associate { it.id to it.revision }
        val count = limit.coerceIn(1,100)
        require(offset in 0..100000) { "Слишком большое смещение" }
        var best = emptyList<SemanticHit>()
        for (batch in index.chunked(128)) {
            val notes = dao.byIds(batch.map { it.id })
            check(notes.size == batch.size && notes.all { context.allows(it) && it.enabled && it.revision == expected[it.id] }) { "Память изменилась. Повторите поиск" }
            best = (best + engine.rank(query, notes.map { SemanticDocument(it.id, it.title + "\n" + it.content) }))
                .sortedWith(compareByDescending<SemanticHit> { it.score }.thenBy { it.id }).take(offset + count)
        }
        val selected = best.drop(offset)
        val notes = dao.byIds(selected.map { it.id }).associateBy { it.id }
        check(selected.all { hit -> notes[hit.id]?.let { context.allows(it) && it.enabled && it.revision == expected[it.id] } == true }) { "Память изменилась. Повторите поиск" }
        return index.size to selected.map { notes.getValue(it.id) to it.score }
    }

    suspend fun search(context: MemoryContext, query: String, id: String?, limit: Int, offset: Int): String {
        if (!id.isNullOrBlank()) {
            val note = dao.note(id)?.takeIf { it.enabled && context.allows(it) } ?: error("Запись не найдена в доступной памяти")
            return Json.encodeToString(note)
        }
        val rows = visible(context, query, limit, offset)
        val total = dao.countVisible(context.chatId, context.folderId, context.modelKey, MemoryPolicy.normalize(query), assistant = context.assistantId)
        val next = (offset.coerceAtLeast(0) + rows.size).takeIf { it < total && rows.isNotEmpty() }
        return "total=$total; next_offset=${next ?: "none"}\n" + Json.encodeToString(rows)
    }
    suspend fun save(context: MemoryContext, scope: MemoryScope, title: String, content: String, previous: MemoryNoteEntity? = null) = db.withTransaction {
        if (previous == null) {
            if (scope == MemoryScope.ASSISTANT) check(db.assistantDao().get(context.key(scope)) != null) { "Ассистент уже удалён" }
            dao.insert(MemoryPolicy.note(context, scope, title, content))
        }
        else {
            check(context.allows(previous)) { "Область памяти больше не доступна этому чату" }
            val current = dao.note(previous.id) ?: error("Запись уже удалена")
            check(current.revision == previous.revision) { "Запись уже изменена. Откройте её заново" }
            MemoryPolicy.validate(title, content)
            dao.update(current.copy(title = title.trim(), content = content.trim(), searchText = MemoryPolicy.normalize(title + "\n" + content), revision = current.revision + 1, updatedAt = System.currentTimeMillis()))
        }
    }
    suspend fun toggle(context: MemoryContext, note: MemoryNoteEntity) = db.withTransaction {
        val current = dao.note(note.id) ?: error("Запись уже удалена")
        check(context.allows(current) && current.revision == note.revision) { "Запись уже изменена. Обновите список" }
        dao.update(current.copy(enabled = !current.enabled, revision = current.revision + 1, updatedAt = System.currentTimeMillis()))
    }
    suspend fun delete(context: MemoryContext, note: MemoryNoteEntity) = db.withTransaction {
        val current = dao.note(note.id) ?: return@withTransaction
        check(context.allows(current) && current.revision == note.revision) { "Запись уже изменена. Откройте её заново" }
        dao.delete(note.id)
    }
    suspend fun propose(context: MemoryContext, action: String, scope: String?, title: String?, content: String?, noteId: String?): MemoryProposalEntity = db.withTransaction {
        require(action in setOf("create", "update", "delete")) { "action: create, update или delete" }
        val old = if (action == "create") null else (dao.note(noteId.orEmpty())?.takeIf { context.allows(it) && it.enabled }
            ?: error("Запись не найдена в доступной памяти"))
        val type = old?.scopeType ?: (scope?.uppercase()?.let(MemoryScope::valueOf) ?: MemoryScope.CHAT).name
        val key = old?.scopeKey ?: context.key(MemoryScope.valueOf(type))
        if (type == "ASSISTANT") check(db.assistantDao().get(key) != null) { "Ассистент уже удалён" }
        val newTitle = if (action == "delete") old!!.title else title ?: error("Нужно название title")
        val newContent = if (action == "delete") old!!.content else content ?: error("Нужен текст content")
        MemoryPolicy.validate(newTitle, newContent)
        val signature = Json.encodeToString(listOf(context.chatId, action, type, key, old?.id, old?.revision?.toString(), newTitle, newContent))
        val id = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        dao.proposal(id)?.let { return@withTransaction it }
        check(dao.proposalCount(context.chatId) < 20) { "Уже ожидают 20 предложений памяти. Дождитесь решения пользователя" }
        val proposal = MemoryProposalEntity(id, context.chatId, action, type, key, old?.id, old?.revision,
            old?.title.orEmpty(), old?.content.orEmpty(), newTitle.trim(), newContent.trim())
        dao.insertProposal(proposal)
        proposal
    }
    /** UI-only entrypoint. No tool exposes this action, including in autopilot. */
    suspend fun approve(chatId: String, id: String) = db.withTransaction {
        val proposal = dao.proposal(id) ?: error("Предложение уже обработано")
        check(proposal.sourceSessionId == chatId) { "Предложение относится к другому чату" }
        if (proposal.action == "create") {
            val scope = MemoryScope.valueOf(proposal.scopeType)
            val note = MemoryNoteEntity(java.util.UUID.randomUUID().toString(), proposal.scopeType, proposal.scopeKey,
                if (scope == MemoryScope.CHAT) proposal.scopeKey else null,
                if (scope == MemoryScope.FOLDER) proposal.scopeKey else null,
                proposal.title, proposal.content, MemoryPolicy.normalize(proposal.title + "\n" + proposal.content))
            MemoryPolicy.validateStored(note)
            if (scope == MemoryScope.ASSISTANT) check(db.assistantDao().get(note.scopeKey) != null) { "Ассистент уже удалён" }
            if (scope == MemoryScope.FOLDER) check(db.folderDao().getAll().any { it.id == note.folderId }) { "Папка уже удалена" }
            dao.insert(note)
        } else {
            val current = dao.note(proposal.targetNoteId.orEmpty()) ?: error("Запись уже удалена")
            check(current.revision == proposal.expectedRevision && current.title == proposal.previousTitle && current.content == proposal.previousContent) {
                "Запись изменилась после предложения. Отклоните его и создайте новое"
            }
            check(current.scopeType == proposal.scopeType && current.scopeKey == proposal.scopeKey)
            if (proposal.action == "delete") dao.delete(current.id)
            else {
                check(proposal.action == "update")
                MemoryPolicy.validate(proposal.title, proposal.content)
                dao.update(current.copy(title = proposal.title, content = proposal.content, searchText = MemoryPolicy.normalize(proposal.title + "\n" + proposal.content), revision = current.revision + 1, updatedAt = System.currentTimeMillis()))
            }
        }
        dao.reject(id)
    }
    suspend fun reject(chatId: String, id: String) = db.withTransaction {
        check(dao.proposal(id)?.sourceSessionId == chatId) { "Предложение уже обработано или относится к другому чату" }
        dao.reject(id)
    }
}
