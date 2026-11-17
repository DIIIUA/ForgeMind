package com.forgemind.app.data.db

import androidx.room.*
import kotlinx.serialization.Serializable

@Entity(tableName = "compaction_checkpoints",
    foreignKeys = [ForeignKey(entity = ChatSessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId")])
@Serializable
data class CompactionCheckpointEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val previousSummary: String,
    val previousUpToOrder: Int,
    val previousTokens: Int,
    val appliedSummary: String,
    val appliedUpToOrder: Int,
    val createdAt: Long = System.currentTimeMillis(),
    val restored: Boolean = false
)
