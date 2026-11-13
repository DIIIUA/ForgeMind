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
        FileSnapshotEntity::class
    ],
    version = 4,
    exportSchema = true
)
abstract class ForgeMindDatabase : RoomDatabase() {
    abstract fun folderDao(): ChatFolderDao
    abstract fun sessionDao(): ChatSessionDao
    abstract fun messageDao(): ChatMessageDao
    abstract fun mcpServerDao(): McpServerDao
    abstract fun sshProfileDao(): SshProfileDao
    abstract fun snapshotDao(): FileSnapshotDao

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

        fun build(context: Context): ForgeMindDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                ForgeMindDatabase::class.java,
                "forgemind.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .fallbackToDestructiveMigration(false)
                .build()
    }
}
