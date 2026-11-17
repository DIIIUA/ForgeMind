package com.forgemind.app.data.db

import androidx.room.*
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "memory_notes", foreignKeys = [
    ForeignKey(entity = ChatSessionEntity::class, parentColumns = ["id"], childColumns = ["chatId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = ChatFolderEntity::class, parentColumns = ["id"], childColumns = ["folderId"], onDelete = ForeignKey.CASCADE)
], indices = [Index("chatId"), Index("folderId"), Index(value = ["scopeType", "scopeKey"])])
data class MemoryNoteEntity(
    @PrimaryKey val id: String,
    val scopeType: String,
    val scopeKey: String,
    val chatId: String? = null,
    val folderId: String? = null,
    val title: String,
    val content: String,
    val searchText: String,
    val enabled: Boolean = true,
    val revision: Long = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt
)

/** A proposal is never request context. Only an explicit owner action promotes it. */
@Serializable
@Entity(tableName = "memory_proposals", foreignKeys = [
    ForeignKey(entity = ChatSessionEntity::class, parentColumns = ["id"], childColumns = ["sourceSessionId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = MemoryNoteEntity::class, parentColumns = ["id"], childColumns = ["targetNoteId"], onDelete = ForeignKey.CASCADE)
], indices = [Index("sourceSessionId"), Index("targetNoteId")])
data class MemoryProposalEntity(
    @PrimaryKey val id: String,
    val sourceSessionId: String,
    val action: String,
    val scopeType: String,
    val scopeKey: String,
    val targetNoteId: String? = null,
    val expectedRevision: Long? = null,
    val previousTitle: String = "",
    val previousContent: String = "",
    val title: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis()
)
