package com.forgemind.app.data.memory

import androidx.room.withTransaction
import com.forgemind.app.data.db.*

object AssistantPolicy {
    fun validate(profile: AssistantProfileEntity) {
        require(profile.id.isNotBlank() && profile.name.isNotBlank() && profile.name.length <= 120) { "Имя ассистента: 1–120 символов" }
        require(profile.category.length <= 80 && profile.instructions.length <= 32000) { "Категория: до 80; инструкция: до 32000 символов" }
        require(profile.historyMessageLimit in -1..10000 && profile.revision > 0)
        require(profile.defaultProviderId.isBlank() == profile.defaultModelId.isBlank()) { "Укажите провайдера и модель вместе" }
    }
    fun historyLimit(chat: Int, assistant: Int?, provider: Int): Int =
        if (chat >= 0) chat else assistant?.takeIf { it >= 0 } ?: provider
}

class AssistantRepository(private val db: ForgeMindDatabase) {
    suspend fun save(profile: AssistantProfileEntity, previousRevision: Long? = null) = db.withTransaction {
        AssistantPolicy.validate(profile)
        val current = db.assistantDao().get(profile.id)
        if (current == null) {
            check(previousRevision == null) { "Профиль уже удалён" }
            db.assistantDao().insert(profile.copy(name = profile.name.trim(), category = profile.category.trim()))
        } else {
            check(current.revision == previousRevision) { "Профиль изменён. Откройте его заново" }
            db.assistantDao().update(profile.copy(name = profile.name.trim(), category = profile.category.trim(), revision = current.revision + 1, createdAt = current.createdAt, updatedAt = System.currentTimeMillis()))
        }
    }
    suspend fun attach(chatId: String, profileId: String?, useDefaultModel: Boolean) = db.withTransaction {
        check(db.sessionDao().getById(chatId) != null) { "Чат уже удалён" }
        val profile = profileId?.let { db.assistantDao().get(it) ?: error("Профиль уже удалён") }
        db.assistantDao().assign(chatId, profileId)
        if (useDefaultModel && profile != null && profile.defaultProviderId.isNotBlank()) {
            db.sessionDao().updateModel(chatId, profile.defaultProviderId, profile.defaultModelId)
        }
    }
    suspend fun delete(profile: AssistantProfileEntity) = db.withTransaction {
        check(db.assistantDao().get(profile.id)?.revision == profile.revision) { "Профиль изменён или уже удалён" }
        db.assistantDao().detachAll(profile.id)
        db.assistantDao().deleteMemory(profile.id)
        db.assistantDao().deleteProposals(profile.id)
        db.assistantDao().delete(profile.id)
    }
}
