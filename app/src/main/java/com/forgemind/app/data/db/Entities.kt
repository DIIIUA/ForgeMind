package com.forgemind.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(tableName = "chat_folders")
@Serializable
data class ChatFolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "chat_sessions")
@Serializable
data class ChatSessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val folderId: String? = null,
    val isArchived: Boolean = false,
    val isPinned: Boolean = false,
    val providerId: String = "openrouter",
    val modelId: String = "google/gemini-2.5-flash",
    val workspaceEnv: String = "internal",
    val workspacePath: String = "default",
    val customSystemPrompt: String = "",
    val compactedSummary: String = "",
    val compactedUpToOrder: Int = 0,
    val estimatedContextTokens: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId"), Index("orderIndex")]
)
@Serializable
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val orderIndex: Int,
    val role: String, // SYSTEM, USER, ASSISTANT, TOOL
    val content: String = "",
    val thinking: String = "",
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolCallsJson: String = "[]",
    val toolCardsJson: String = "[]",
    val attachmentsJson: String = "[]",
    val providerId: String? = null,
    val modelId: String? = null,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val isStreaming: Boolean = false,
    val isError: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "mcp_servers")
@Serializable
data class McpServerEntity(
    @PrimaryKey val id: String,
    val name: String,
    val transport: String = "STREAMABLE_HTTP", // STREAMABLE_HTTP, HTTP_SSE, SSH_STDIO
    val endpoint: String,
    val headersJson: String = "{}",
    val enabled: Boolean = true,
    val cachedToolsJson: String = "[]",
    val lastConnectedAt: Long? = null,
    val lastError: String? = null
)

@Entity(tableName = "ssh_profiles")
@Serializable
data class SshProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val host: String = "127.0.0.1",
    val port: Int = 2222,
    val username: String = "",
    val password: String = "",
    val privateKeyPem: String = "",
    val keyPassphrase: String = "",
    val defaultWorkDir: String = "",
    val isDefault: Boolean = false,
    val enabled: Boolean = true,
    val useCloudflareAccess: Boolean = false,
    val cfAccessClientId: String = "",
    val cfAccessClientSecret: String = ""
)

@Entity(
    tableName = "file_snapshots",
    indices = [Index("sessionId"), Index("messageId")]
)
@Serializable
data class FileSnapshotEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val messageId: String,
    val envType: String,
    val filePath: String,
    val fileExistedBefore: Boolean,
    val previousContent: String?,
    val newContent: String?,
    val diffPreview: String,
    val restored: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
