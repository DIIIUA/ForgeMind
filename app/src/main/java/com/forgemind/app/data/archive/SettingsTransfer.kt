package com.forgemind.app.data.archive

import android.content.Context
import androidx.room.withTransaction
import com.forgemind.app.data.db.ForgeMindDatabase
import com.forgemind.app.data.db.McpServerEntity
import com.forgemind.app.data.db.SshProfileEntity
import com.forgemind.app.data.prefs.SettingsRepository
import com.forgemind.app.i18n.LocaleHelper
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class PortableSettings(
    val strings: Map<String, String> = emptyMap(),
    val booleans: Map<String, Boolean> = emptyMap(),
    val secrets: Map<String, String> = emptyMap(),
    // Every other DataStore type, so a future numeric setting does not break export.
    val ints: Map<String, Int> = emptyMap(),
    val longs: Map<String, Long> = emptyMap(),
    val floats: Map<String, Float> = emptyMap(),
    val doubles: Map<String, Double> = emptyMap(),
    val stringSets: Map<String, Set<String>> = emptyMap()
)

@Serializable
private data class SettingsTransferBundle(
    val version: Int = 1,
    val settings: PortableSettings,
    val mcpServers: List<McpServerEntity>,
    val sshProfiles: List<SshProfileEntity>,
    val language: String
)

class SettingsTransfer(private val context: Context, private val settings: SettingsRepository,
                       private val db: ForgeMindDatabase) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    suspend fun export(password: CharArray): ByteArray {
        val bundle = db.withTransaction {
            SettingsTransferBundle(settings = settings.exportPortableSettings(),
                mcpServers = db.mcpServerDao().getAll(), sshProfiles = db.sshProfileDao().getAll(),
                language = LocaleHelper.getSavedLanguageCode(context))
        }
        val plain = json.encodeToString(bundle).toByteArray(Charsets.UTF_8)
        return try { SettingsBackupCipher.encrypt(plain, password) } finally { plain.fill(0) }
    }

    suspend fun restore(encrypted: ByteArray, password: CharArray) {
        val plain = SettingsBackupCipher.decrypt(encrypted, password)
        val bundle = try { json.decodeFromString<SettingsTransferBundle>(plain.toString(Charsets.UTF_8)) }
            finally { plain.fill(0) }
        require(bundle.version == 1)
        settings.validatePortableSettings(bundle.settings)
        require(bundle.mcpServers.size <= 1000 && bundle.sshProfiles.size <= 1000)
        require(bundle.mcpServers.all { it.id.isNotBlank() } && bundle.sshProfiles.all { it.id.isNotBlank() && it.port in 1..65535 })
        require(bundle.mcpServers.distinctBy { it.id }.size == bundle.mcpServers.size)
        require(bundle.sshProfiles.distinctBy { it.id }.size == bundle.sshProfiles.size)
        val before = settings.exportPortableSettings()
        val oldLanguage = LocaleHelper.getSavedLanguageCode(context)
        // No partial apply on coroutine cancellation. Database rollback plus the captured preferences
        // restore the old configuration on ordinary write failures; process death is not transactional.
        withContext(NonCancellable) {
            try {
                db.withTransaction {
                    db.mcpServerDao().getAll().forEach { db.mcpServerDao().deleteById(it.id) }
                    db.sshProfileDao().getAll().forEach { db.sshProfileDao().deleteById(it.id) }
                    bundle.mcpServers.forEach { db.mcpServerDao().upsert(it) }
                    bundle.sshProfiles.forEach { db.sshProfileDao().upsert(it) }
                    settings.importPortableSettings(bundle.settings, dropUnavailableGrants = true)
                    LocaleHelper.setSavedLanguageCode(context, bundle.language)
                }
            } catch (error: Throwable) {
                settings.importPortableSettings(before, dropUnavailableGrants = false)
                LocaleHelper.setSavedLanguageCode(context, oldLanguage)
                settings.loadAll()
                throw error
            }
            settings.loadAll()
        }
    }
}
