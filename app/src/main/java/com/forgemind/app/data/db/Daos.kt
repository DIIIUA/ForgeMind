package com.forgemind.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatFolderDao {
    @Query("SELECT * FROM chat_folders ORDER BY name ASC")
    fun observeAll(): Flow<List<ChatFolderEntity>>

    @Query("SELECT * FROM chat_folders ORDER BY name ASC")
    suspend fun getAll(): List<ChatFolderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(folder: ChatFolderEntity)

    @Query("DELETE FROM chat_folders WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface ChatSessionDao {
    @Query(
        "SELECT * FROM chat_sessions WHERE isArchived = :archived " +
            "ORDER BY isPinned DESC, updatedAt DESC"
    )
    fun observeByArchive(archived: Boolean): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_sessions ORDER BY updatedAt DESC")
    suspend fun getAll(): List<ChatSessionEntity>

    @Query("SELECT * FROM chat_sessions WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<ChatSessionEntity?>

    @Query("SELECT * FROM chat_sessions WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ChatSessionEntity?

    @Query(
        "SELECT DISTINCT s.* FROM chat_sessions s " +
            "LEFT JOIN chat_messages m ON s.id = m.sessionId " +
            "WHERE s.title LIKE '%' || :query || '%' OR m.content LIKE '%' || :query || '%' " +
            "ORDER BY s.updatedAt DESC"
    )
    suspend fun search(query: String): List<ChatSessionEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(session: ChatSessionEntity): Long

    @Update
    suspend fun update(session: ChatSessionEntity)

    /**
     * Safe upsert without OnConflictStrategy.REPLACE, because SQLite REPLACE performs
     * DELETE + INSERT and would trigger FOREIGN KEY ON DELETE CASCADE on chat_messages.
     */
    @Transaction
    suspend fun upsert(session: ChatSessionEntity) {
        if (insertIgnore(session) == -1L) {
            update(session)
        }
    }

    @Query("UPDATE chat_sessions SET title = :title, updatedAt = :ts WHERE id = :id")
    suspend fun updateTitle(id: String, title: String, ts: Long = System.currentTimeMillis())

    @Query("UPDATE chat_sessions SET estimatedContextTokens = :tokens WHERE id = :id")
    suspend fun updateContextTokens(id: String, tokens: Int)

    @Query("UPDATE chat_sessions SET updatedAt = :ts WHERE id = :id")
    suspend fun touchUpdatedAt(id: String, ts: Long = System.currentTimeMillis())

    @Query(
        "UPDATE chat_sessions SET compactedSummary = :summary, compactedUpToOrder = :upToOrder, " +
            "estimatedContextTokens = :tokens, updatedAt = :ts WHERE id = :id"
    )
    suspend fun updateCompaction(
        id: String,
        summary: String,
        upToOrder: Int,
        tokens: Int,
        ts: Long = System.currentTimeMillis()
    )

    @Query("UPDATE chat_sessions SET isArchived = :archived, updatedAt = :ts WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean, ts: Long = System.currentTimeMillis())

    @Query("UPDATE chat_sessions SET isPinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    @Query("DELETE FROM chat_sessions WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface ChatMessageDao {
    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY orderIndex ASC, createdAt ASC")
    fun observeForSession(sessionId: String): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY orderIndex ASC, createdAt ASC")
    suspend fun getForSession(sessionId: String): List<ChatMessageEntity>

    @Query("SELECT * FROM chat_messages ORDER BY sessionId ASC, orderIndex ASC")
    suspend fun getAll(): List<ChatMessageEntity>

    @Query("SELECT COALESCE(MAX(orderIndex), -1) FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun getMaxOrderIndex(sessionId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: ChatMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(messages: List<ChatMessageEntity>)

    @Query("DELETE FROM chat_messages WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId AND orderIndex >= :fromOrder")
    suspend fun deleteFromOrder(sessionId: String, fromOrder: Int)

    @Query("UPDATE chat_messages SET isStreaming = 0 WHERE isStreaming = 1")
    suspend fun resetStaleStreaming()
}

@Dao
interface McpServerDao {
    @Query("SELECT * FROM mcp_servers ORDER BY name ASC")
    fun observeAll(): Flow<List<McpServerEntity>>

    @Query("SELECT * FROM mcp_servers ORDER BY name ASC")
    suspend fun getAll(): List<McpServerEntity>

    @Query("SELECT * FROM mcp_servers WHERE enabled = 1 ORDER BY name ASC")
    suspend fun getEnabled(): List<McpServerEntity>

    @Query("SELECT * FROM mcp_servers WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): McpServerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(server: McpServerEntity)

    @Query("DELETE FROM mcp_servers WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface SshProfileDao {
    @Query("SELECT * FROM ssh_profiles ORDER BY isDefault DESC, name ASC")
    fun observeAll(): Flow<List<SshProfileEntity>>

    @Query("SELECT * FROM ssh_profiles ORDER BY isDefault DESC, name ASC")
    suspend fun getAll(): List<SshProfileEntity>

    @Query("SELECT * FROM ssh_profiles WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): SshProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: SshProfileEntity)

    @Query("UPDATE ssh_profiles SET isDefault = 0")
    suspend fun clearDefault()

    @Query("DELETE FROM ssh_profiles WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface FileSnapshotDao {
    @Query("SELECT * FROM file_snapshots WHERE sessionId = :sessionId ORDER BY createdAt DESC")
    fun observeForSession(sessionId: String): Flow<List<FileSnapshotEntity>>

    @Query("SELECT * FROM file_snapshots WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): FileSnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(snapshot: FileSnapshotEntity)

    @Query("UPDATE file_snapshots SET restored = 1 WHERE id = :id")
    suspend fun markRestored(id: String)
}
