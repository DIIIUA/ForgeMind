package com.forgemind.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ChatFolderEntity::class,
        ChatSessionEntity::class,
        ChatMessageEntity::class,
        McpServerEntity::class,
        SshProfileEntity::class,
        FileSnapshotEntity::class,
        CompactionCheckpointEntity::class,
        MemoryNoteEntity::class,
        MemoryProposalEntity::class,
        AssistantProfileEntity::class
    ],
    version = 8,
    exportSchema = true
)
abstract class ForgeMindDatabase : RoomDatabase() {
    abstract fun folderDao(): ChatFolderDao
    abstract fun sessionDao(): ChatSessionDao
    abstract fun messageDao(): ChatMessageDao
    abstract fun mcpServerDao(): McpServerDao
    abstract fun sshProfileDao(): SshProfileDao
    abstract fun snapshotDao(): FileSnapshotDao

    abstract fun memoryDao(): MemoryDao
    abstract fun assistantDao(): AssistantProfileDao

    companion object {
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ssh_profiles ADD COLUMN enabled INTEGER NOT NULL DEFAULT 1")
            }
        }

        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ssh_profiles ADD COLUMN useCloudflareAccess INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE ssh_profiles ADD COLUMN cfAccessClientId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE ssh_profiles ADD COLUMN cfAccessClientSecret TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE mcp_servers ADD COLUMN instructions TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN branchSelectionsJson TEXT NOT NULL DEFAULT '{}'")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN parentMessageId TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN historyMessageLimit INTEGER NOT NULL DEFAULT -1")
                db.execSQL("CREATE TABLE IF NOT EXISTS compaction_checkpoints (id TEXT NOT NULL, sessionId TEXT NOT NULL, previousSummary TEXT NOT NULL, previousUpToOrder INTEGER NOT NULL, previousTokens INTEGER NOT NULL, appliedSummary TEXT NOT NULL, appliedUpToOrder INTEGER NOT NULL, createdAt INTEGER NOT NULL, restored INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(sessionId) REFERENCES chat_sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_compaction_checkpoints_sessionId ON compaction_checkpoints(sessionId)")
            }
        }

        val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS memory_notes (id TEXT NOT NULL, scopeType TEXT NOT NULL, scopeKey TEXT NOT NULL, chatId TEXT, folderId TEXT, title TEXT NOT NULL, content TEXT NOT NULL, searchText TEXT NOT NULL, enabled INTEGER NOT NULL, revision INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(chatId) REFERENCES chat_sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(folderId) REFERENCES chat_folders(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_notes_chatId ON memory_notes(chatId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_notes_folderId ON memory_notes(folderId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_notes_scopeType_scopeKey ON memory_notes(scopeType, scopeKey)")
                db.execSQL("CREATE TABLE IF NOT EXISTS memory_proposals (id TEXT NOT NULL, sourceSessionId TEXT NOT NULL, action TEXT NOT NULL, scopeType TEXT NOT NULL, scopeKey TEXT NOT NULL, targetNoteId TEXT, expectedRevision INTEGER, previousTitle TEXT NOT NULL, previousContent TEXT NOT NULL, title TEXT NOT NULL, content TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(sourceSessionId) REFERENCES chat_sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(targetNoteId) REFERENCES memory_notes(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_proposals_sourceSessionId ON memory_proposals(sourceSessionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_proposals_targetNoteId ON memory_proposals(targetNoteId)")
            }
        }

        val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS assistant_profiles (id TEXT NOT NULL, name TEXT NOT NULL, category TEXT NOT NULL, instructions TEXT NOT NULL, defaultProviderId TEXT NOT NULL, defaultModelId TEXT NOT NULL, historyMessageLimit INTEGER NOT NULL, revision INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id))")
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN assistantId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN assistantId TEXT DEFAULT NULL")
            }
        }

        fun build(context: Context): ForgeMindDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                ForgeMindDatabase::class.java,
                "forgemind.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                .build()
    }
}
