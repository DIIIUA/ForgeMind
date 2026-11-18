package com.forgemind.app.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Entity(tableName = "assistant_profiles")
@Serializable
data class AssistantProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val category: String = "",
    val instructions: String = "",
    val defaultProviderId: String = "",
    val defaultModelId: String = "",
    val historyMessageLimit: Int = -1,
    val revision: Long = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    @ColumnInfo(defaultValue = "'{}'") val generationConfigJson: String = "{}"
)

@Dao
interface AssistantProfileDao {
    @Query("SELECT * FROM assistant_profiles ORDER BY category, name, id")
    fun observeAll(): Flow<List<AssistantProfileEntity>>
    @Query("SELECT * FROM assistant_profiles ORDER BY category, name, id")
    suspend fun all(): List<AssistantProfileEntity>
    @Query("SELECT * FROM assistant_profiles WHERE id = :id")
    suspend fun get(id: String): AssistantProfileEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(profile: AssistantProfileEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun importProfile(profile: AssistantProfileEntity): Long
    @Update
    suspend fun update(profile: AssistantProfileEntity)
    @Query("DELETE FROM assistant_profiles WHERE id = :id")
    suspend fun delete(id: String)
    @Query("UPDATE chat_sessions SET assistantId = :profileId WHERE id = :chatId")
    suspend fun assign(chatId: String, profileId: String?)
    @Query("UPDATE chat_sessions SET assistantId = NULL WHERE assistantId = :id")
    suspend fun detachAll(id: String)
    @Query("DELETE FROM memory_notes WHERE scopeType = 'ASSISTANT' AND scopeKey = :id")
    suspend fun deleteMemory(id: String)
    @Query("DELETE FROM memory_proposals WHERE scopeType = 'ASSISTANT' AND scopeKey = :id")
    suspend fun deleteProposals(id: String)
}
