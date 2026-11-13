package com.forgemind.app.i18n

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

enum class AppLanguage(val code: String, val displayName: String) {
    SYSTEM("", "System default"),
    ENGLISH("en", "English"),
    RUSSIAN("ru", "Русский"),
    SPANISH("es", "Español"),
    GERMAN("de", "Deutsch"),
    FRENCH("fr", "Français"),
    PORTUGUESE("pt", "Português"),
    CHINESE("zh-CN", "简体中文"),
    JAPANESE("ja", "日本語"),
    KOREAN("ko", "한국어"),
    HINDI("hi", "हिन्दी"),
    ARABIC("ar", "العربية"),
    UKRAINIAN("uk", "Українська"),
    TURKISH("tr", "Türkçe");

    companion object {
        fun fromCode(code: String?): AppLanguage {
            val clean = code?.trim().orEmpty()
            return entries.firstOrNull { it.code.equals(clean, ignoreCase = true) } ?: SYSTEM
        }
    }
}

object LocaleHelper {
    private const val PREFS_NAME = "forgemind_locale_prefs"
    private const val KEY_APP_LANGUAGE = "app_language"

    fun getSavedLanguageCode(context: Context): String {
        val prefs = runCatching { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }.getOrNull()
        return prefs?.getString(KEY_APP_LANGUAGE, "").orEmpty()
    }

    fun setSavedLanguageCode(context: Context, code: String) {
        val normalized = AppLanguage.fromCode(code).code
        runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_APP_LANGUAGE, normalized)
                .commit()
        }
    }

    fun wrapContext(base: Context): Context {
        val savedCode = getSavedLanguageCode(base)
        if (savedCode.isBlank()) return base
        val locale = Locale.forLanguageTag(savedCode)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLocales(LocaleList(locale))
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }
}
