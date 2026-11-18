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

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFolder(folder: ChatFolderEntity): Long
    @Update
    suspend fun updateFolder(folder: ChatFolderEntity)
    @Transaction
    suspend fun upsert(folder: ChatFolderEntity) {
        if (insertFolder(folder) == -1L) updateFolder(folder)
    }

    @Query("DELETE FROM chat_folders WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface ChatSessionDao {
    @Query("UPDATE chat_sessions SET historyMessageLimit = :limit WHERE id = :id")
    suspend fun updateHistoryLimit(id: String, limit: Int)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCompactionCheckpoint(checkpoint: CompactionCheckpointEntity)

    @Query("SELECT * FROM compaction_checkpoints WHERE sessionId = :sessionId AND restored = 0 ORDER BY createdAt DESC, rowid DESC LIMIT 1")
    suspend fun latestCompactionCheckpoint(sessionId: String): CompactionCheckpointEntity?

    @Query("SELECT COUNT(*) FROM compaction_checkpoints WHERE sessionId = :sessionId AND restored = 0")
    fun observeCompactionCheckpointCount(sessionId: String): Flow<Int>

    @Query("SELECT * FROM compaction_checkpoints")
    suspend fun allCompactionCheckpoints(): List<CompactionCheckpointEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun importCompactionCheckpoints(checkpoints: List<CompactionCheckpointEntity>)

    @Query("UPDATE compaction_checkpoints SET restored = 1 WHERE sessionId = :sessionId")
    suspend fun invalidateCompactions(sessionId: String)

    @Query("UPDATE compaction_checkpoints SET restored = 1 WHERE id = :id")
    suspend fun markCompactionRestored(id: String)

    /** The checkpoint and the new summary are committed together, or neither is changed. */
    @Transaction
    suspend fun applyCompactionCheckpoint(checkpoint: CompactionCheckpointEntity, tokensAfter: Int) {
        val current = getById(checkpoint.sessionId) ?: error("Chat no longer exists")
        check(current.compactedSummary == checkpoint.previousSummary && current.compactedUpToOrder == checkpoint.previousUpToOrder) {
            "Context changed during compaction; the original history was kept"
        }
        insertCompactionCheckpoint(checkpoint)
        updateCompaction(checkpoint.sessionId, checkpoint.appliedSummary, checkpoint.appliedUpToOrder, tokensAfter)
    }

    @Transaction
    suspend fun undoLatestCompaction(sessionId: String): Boolean {
        val checkpoint = latestCompactionCheckpoint(sessionId) ?: return false
        val current = getById(sessionId) ?: return false
        check(current.compactedSummary == checkpoint.appliedSummary && current.compactedUpToOrder == checkpoint.appliedUpToOrder) {
            "Context has changed since this checkpoint; no history was overwritten"
        }
        updateCompaction(sessionId, checkpoint.previousSummary, checkpoint.previousUpToOrder, checkpoint.previousTokens)
        markCompactionRestored(checkpoint.id)
        return true
    }

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

    @Query("UPDATE chat_sessions SET providerId = :providerId, modelId = :modelId, updatedAt = :ts WHERE id = :id")
    suspend fun updateModel(id: String, providerId: String, modelId: String, ts: Long = System.currentTimeMillis())

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

    @Query("UPDATE chat_sessions SET branchSelectionsJson = :branchSelectionsJson, updatedAt = :ts WHERE id = :id")
    suspend fun updateBranchSelections(id: String, branchSelectionsJson: String, ts: Long = System.currentTimeMillis())

    @Query("UPDATE chat_sessions SET isArchived = :archived, updatedAt = :ts WHERE id IN (:ids)")
    suspend fun setArchivedForIds(ids: List<String>, archived: Boolean, ts: Long = System.currentTimeMillis())

    @Query("UPDATE chat_sessions SET folderId = :folderId, updatedAt = :ts WHERE id IN (:ids)")
    suspend fun assignFolderForIds(ids: List<String>, folderId: String?, ts: Long = System.currentTimeMillis())

    @Query("DELETE FROM chat_sessions WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM chat_sessions WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}

@Dao
interface ChatMessageDao {
    @Query("SELECT id, sessionId, content, thinking FROM chat_messages WHERE id > :after AND role IN ('USER','ASSISTANT') ORDER BY id LIMIT 200")
    suspend fun searchPage(after: String): List<MessageSearchRecord>

    @Query("UPDATE chat_messages SET excludedFromContext = :excluded WHERE sessionId = :sessionId AND id IN (:ids)")
    suspend fun setExcluded(sessionId: String, ids: Set<String>, excluded: Boolean)

    @Query("SELECT * FROM chat_messages WHERE id = :messageId AND sessionId = :sessionId LIMIT 1")
    suspend fun getMessage(sessionId: String, messageId: String): ChatMessageEntity?

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY orderIndex ASC, createdAt ASC")
    fun observeForSession(sessionId: String): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY orderIndex ASC, createdAt ASC")
    suspend fun getForSession(sessionId: String): List<ChatMessageEntity>

    @Query("SELECT * FROM chat_messages ORDER BY sessionId ASC, orderIndex ASC")
    suspend fun getAll(): List<ChatMessageEntity>

    @Query(
        "SELECT * FROM chat_messages WHERE content LIKE '%' || :query || '%' " +
            "ORDER BY createdAt DESC LIMIT :limit"
    )
    suspend fun searchMessages(query: String, limit: Int = 30): List<ChatMessageEntity>

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

data class MessageSearchRecord(val id: String, val sessionId: String, val content: String, val thinking: String)

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

    @Query("SELECT * FROM file_snapshots WHERE sessionId = :sessionId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecentForSession(sessionId: String, limit: Int = 25): List<FileSnapshotEntity>

    @Query("SELECT * FROM file_snapshots WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): FileSnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(snapshot: FileSnapshotEntity)

    @Query("UPDATE file_snapshots SET restored = 1 WHERE id = :id")
    suspend fun markRestored(id: String)
}
