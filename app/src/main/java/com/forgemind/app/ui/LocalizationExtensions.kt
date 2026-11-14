package com.forgemind.app.ui

import android.content.Context
import androidx.annotation.StringRes
import com.forgemind.app.R
import com.forgemind.app.data.model.ProviderAuthMode
import com.forgemind.app.data.model.ProviderCategory
import com.forgemind.app.data.model.ProviderConfig
import com.forgemind.app.data.model.ProviderType
import com.forgemind.app.data.model.SearchEngineType
import com.forgemind.app.data.model.SttProviderType
import com.forgemind.app.data.model.TtsProviderType
import com.forgemind.app.data.model.WireProtocol
import com.forgemind.app.data.model.WorkspaceEnvType
import java.util.Locale

@StringRes
fun AppSection.labelRes(): Int = when (this) {
    AppSection.CHAT -> R.string.nav_chat
    AppSection.FILES -> R.string.nav_files
    AppSection.TERMINAL -> R.string.nav_terminal
    AppSection.PREVIEW -> R.string.nav_browser
    AppSection.SETTINGS -> R.string.nav_settings
}

fun ProviderType.localizedName(context: Context): String = when (this) {
    ProviderType.OLLAMA -> context.getString(R.string.provider_name_ollama)
    ProviderType.LM_STUDIO -> context.getString(R.string.provider_name_lm_studio)
    ProviderType.LLAMA_CPP -> context.getString(R.string.provider_name_llama_cpp)
    ProviderType.VLLM_LOCAL -> context.getString(R.string.provider_name_vllm_local)
    ProviderType.CUSTOM -> context.getString(R.string.provider_name_custom)
    else -> displayName
}

fun ProviderType.localizedSubtitle(context: Context): String = when (this) {
    ProviderType.OPENROUTER -> context.getString(R.string.provider_sub_openrouter)
    ProviderType.GROQ -> context.getString(R.string.provider_sub_groq)
    ProviderType.VENICE -> context.getString(R.string.provider_sub_venice)
    ProviderType.NVIDIA -> context.getString(R.string.provider_sub_nvidia)
    ProviderType.OLLAMA -> context.getString(R.string.provider_sub_ollama)
    ProviderType.LM_STUDIO -> context.getString(R.string.provider_sub_lm_studio)
    ProviderType.LLAMA_CPP -> context.getString(R.string.provider_sub_llama_cpp)
    ProviderType.VLLM_LOCAL -> context.getString(R.string.provider_sub_vllm_local)
    ProviderType.CUSTOM -> context.getString(R.string.provider_sub_custom)
    else -> subtitle
}

fun ProviderConfig.resolvedDisplayName(context: Context): String {
    if (customDisplayName.isNotBlank()) return customDisplayName.trim()
    val builtIn = ProviderType.findById(providerId)
    if (builtIn != null) return builtIn.localizedName(context)
    return ProviderType.fromId(providerId).localizedName(context)
}

fun ProviderConfig.resolvedSubtitle(context: Context): String {
    if (customSubtitle.isNotBlank()) return customSubtitle.trim()
    val builtIn = ProviderType.findById(providerId)
    if (builtIn != null && wireProtocolOverride == null) return builtIn.localizedSubtitle(context)
    val proto = effectiveWireProtocol().localizedLabel(context)
    return "${baseUrl.ifBlank { "—" }} • $proto"
}

fun ProviderCategory.localizedLabel(context: Context): String = when (this) {
    ProviderCategory.CLOUD -> context.getString(R.string.prov_cat_cloud)
    ProviderCategory.LOCAL_LAN -> context.getString(R.string.prov_cat_lan)
    ProviderCategory.CUSTOM -> context.getString(R.string.prov_cat_custom)
}

fun WireProtocol.localizedLabel(context: Context): String = when (this) {
    WireProtocol.OPENAI_COMPAT -> context.getString(R.string.prov_proto_openai)
    WireProtocol.OPENAI_RESPONSES -> context.getString(R.string.prov_proto_responses)
    WireProtocol.ANTHROPIC -> context.getString(R.string.prov_proto_anthropic)
    WireProtocol.GEMINI -> context.getString(R.string.prov_proto_gemini)
    WireProtocol.OLLAMA_NATIVE -> context.getString(R.string.prov_proto_ollama)
}

fun ProviderAuthMode.localizedLabel(context: Context): String = when (this) {
    ProviderAuthMode.AUTO -> context.getString(R.string.prov_auth_auto)
    ProviderAuthMode.NONE -> context.getString(R.string.prov_auth_none)
    ProviderAuthMode.BEARER -> context.getString(R.string.prov_auth_bearer)
    ProviderAuthMode.X_API_KEY -> context.getString(R.string.prov_auth_x_api_key)
    ProviderAuthMode.QUERY_KEY -> context.getString(R.string.prov_auth_query)
    ProviderAuthMode.CUSTOM_HEADER -> context.getString(R.string.prov_auth_custom_header)
}

fun WorkspaceEnvType.localizedLabel(context: Context): String = when (this) {
    WorkspaceEnvType.INTERNAL -> context.getString(R.string.env_internal)
    WorkspaceEnvType.DEVICE_STORAGE -> context.getString(R.string.env_device_storage)
    WorkspaceEnvType.TERMUX_SSH -> context.getString(R.string.env_termux_ssh)
    WorkspaceEnvType.SAF -> context.getString(R.string.env_saf)
}

fun SttProviderType.localizedLabel(context: Context): String = when (this) {
    SttProviderType.ANDROID_SPEECH -> context.getString(R.string.stt_android)
    else -> label
}

fun TtsProviderType.localizedLabel(context: Context): String = when (this) {
    TtsProviderType.MICROSOFT_EDGE -> context.getString(R.string.tts_microsoft_edge)
    TtsProviderType.XTTS_V2 -> context.getString(R.string.tts_xtts_local)
    TtsProviderType.XTTS_EXTERNAL -> context.getString(R.string.tts_xtts_external)
    TtsProviderType.ANDROID_TTS -> context.getString(R.string.tts_android)
    else -> label
}

fun SearchEngineType.localizedLabel(context: Context): String = when (this) {
    SearchEngineType.SEARXNG -> context.getString(R.string.search_searxng)
    else -> label
}

fun formatSafTreeLabelLocalized(context: Context, uriString: String?): String {
    if (uriString.isNullOrBlank()) return context.getString(R.string.ws_not_selected)
    val decoded = com.forgemind.app.bridge.WorkspaceManager.decodeSafTreePath(uriString)
        ?: return uriString
    return when {
        decoded.startsWith("primary:") -> {
            val sub = decoded.removePrefix("primary:").trim('/')
            if (sub.isBlank()) context.getString(R.string.ws_phone_root)
            else context.getString(R.string.ws_phone_sub_fmt, sub)
        }
        decoded.startsWith("home:") -> {
            val sub = decoded.removePrefix("home:").trim('/')
            if (sub.isBlank()) context.getString(R.string.ws_docs_root)
            else context.getString(R.string.ws_docs_sub_fmt, sub)
        }
        decoded.startsWith("raw:") -> decoded.removePrefix("raw:")
        decoded.contains(':') -> {
            val vol = decoded.substringBefore(':')
            val sub = decoded.substringAfter(':').trim('/')
            if (sub.isBlank()) context.getString(R.string.ws_vol_root_fmt, vol) else "$vol / $sub"
        }
        else -> decoded
    }
}

fun formatByteCount(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    return String.format(Locale.US, "%.2f MB", mb)
}
