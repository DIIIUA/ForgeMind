package com.forgemind.app.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

data class MemoryRevision(val id: String, val revision: Long)

@Dao
interface MemoryDao {
    @Query("SELECT id, revision FROM memory_notes WHERE (scopeType = 'GLOBAL' AND scopeKey = '' OR scopeType = 'CHAT' AND scopeKey = :chat OR scopeType = 'ASSISTANT' AND scopeKey = :assistant OR scopeType = 'FOLDER' AND scopeKey = :folder OR scopeType = 'MODEL' AND scopeKey = :model) AND enabled = 1 ORDER BY id")
    suspend fun index(chat: String, folder: String?, model: String, assistant: String?): List<MemoryRevision>
    @Query("SELECT * FROM memory_notes WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<MemoryNoteEntity>
    @Query("SELECT * FROM memory_notes WHERE id IN (:ids)")
    fun observeByIds(ids: List<String>): Flow<List<MemoryNoteEntity>>

    @Query("SELECT * FROM memory_notes WHERE (scopeType = 'GLOBAL' AND scopeKey = '' OR scopeType = 'CHAT' AND scopeKey = :chat OR scopeType = 'ASSISTANT' AND scopeKey = :assistant OR scopeType = 'FOLDER' AND scopeKey = :folder OR scopeType = 'MODEL' AND scopeKey = :model) AND (:includeDisabled OR enabled = 1) AND instr(searchText, :query) > 0 ORDER BY CASE scopeType WHEN 'CHAT' THEN 0 WHEN 'ASSISTANT' THEN 1 WHEN 'FOLDER' THEN 2 WHEN 'MODEL' THEN 3 ELSE 4 END, updatedAt DESC, id ASC LIMIT :limit OFFSET :offset")
    suspend fun visible(chat: String, folder: String?, model: String, query: String = "", includeDisabled: Boolean = false, limit: Int = 100, offset: Int = 0, assistant: String? = null): List<MemoryNoteEntity>
    @Query("SELECT * FROM memory_notes WHERE (scopeType = 'GLOBAL' AND scopeKey = '' OR scopeType = 'CHAT' AND scopeKey = :chat OR scopeType = 'ASSISTANT' AND scopeKey = :assistant OR scopeType = 'FOLDER' AND scopeKey = :folder OR scopeType = 'MODEL' AND scopeKey = :model) AND (:includeDisabled OR enabled = 1) AND instr(searchText, :query) > 0 ORDER BY CASE scopeType WHEN 'CHAT' THEN 0 WHEN 'ASSISTANT' THEN 1 WHEN 'FOLDER' THEN 2 WHEN 'MODEL' THEN 3 ELSE 4 END, updatedAt DESC, id ASC LIMIT :limit OFFSET :offset")
    fun observeVisible(chat: String, folder: String?, model: String, query: String, includeDisabled: Boolean, limit: Int, offset: Int = 0, assistant: String? = null): Flow<List<MemoryNoteEntity>>
    @Query("SELECT COUNT(*) FROM memory_notes WHERE (scopeType = 'GLOBAL' AND scopeKey = '' OR scopeType = 'CHAT' AND scopeKey = :chat OR scopeType = 'ASSISTANT' AND scopeKey = :assistant OR scopeType = 'FOLDER' AND scopeKey = :folder OR scopeType = 'MODEL' AND scopeKey = :model) AND (:includeDisabled OR enabled = 1) AND instr(searchText, :query) > 0")
    suspend fun countVisible(chat: String, folder: String?, model: String, query: String = "", includeDisabled: Boolean = false, assistant: String? = null): Int
    @Query("SELECT * FROM memory_notes WHERE id = :id")
    suspend fun note(id: String): MemoryNoteEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(note: MemoryNoteEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun importNote(note: MemoryNoteEntity): Long
    @Update
    suspend fun update(note: MemoryNoteEntity)
    @Query("DELETE FROM memory_notes WHERE id = :id")
    suspend fun delete(id: String)
    @Query("SELECT * FROM memory_notes ORDER BY createdAt, id")
    suspend fun allNotes(): List<MemoryNoteEntity>
    @Query("SELECT * FROM memory_proposals WHERE sourceSessionId = :chat ORDER BY createdAt, id")
    fun observeProposals(chat: String): Flow<List<MemoryProposalEntity>>
    @Query("SELECT * FROM memory_proposals WHERE id = :id")
    suspend fun proposal(id: String): MemoryProposalEntity?
    @Query("SELECT COUNT(*) FROM memory_proposals WHERE sourceSessionId = :chat")
    suspend fun proposalCount(chat: String): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertProposal(proposal: MemoryProposalEntity): Long
    @Query("DELETE FROM memory_proposals WHERE id = :id")
    suspend fun reject(id: String)
    @Query("SELECT * FROM memory_proposals ORDER BY createdAt, id")
    suspend fun allProposals(): List<MemoryProposalEntity>

    @Query("SELECT COUNT(*) FROM memory_notes WHERE scopeType = 'FOLDER' AND scopeKey = :folderId")
    suspend fun countFolderNotes(folderId: String): Int

    @Query("UPDATE memory_notes SET scopeType = 'GLOBAL', scopeKey = '', folderId = NULL, updatedAt = :ts WHERE scopeType = 'FOLDER' AND scopeKey = :folderId")
    suspend fun promoteFolderNotesToGlobal(folderId: String, ts: Long = System.currentTimeMillis())

    @Query("UPDATE memory_proposals SET scopeType = 'GLOBAL', scopeKey = '' WHERE scopeType = 'FOLDER' AND scopeKey = :folderId")
    suspend fun promoteFolderProposalsToGlobal(folderId: String)

    @Query("DELETE FROM memory_proposals WHERE scopeType = 'FOLDER' AND scopeKey = :folderId")
    suspend fun deleteFolderProposals(folderId: String)
}
