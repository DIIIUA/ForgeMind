package com.forgemind.app.setup

/** Pure transformations; a successful shell reply is never a permission check. */
object LocalAdbPolicy {
    fun port(text: String): Int = text.trim().toInt().also { require(it in 1..65535) }
    fun pairingCode(text: String): String = text.trim().also { require(it.matches(Regex("[0-9]{6}"))) }
    fun accessibilityServices(existing: String, component: String): String {
        require(component.matches(Regex("[a-zA-Z0-9_.]+/[a-zA-Z0-9_.]+")))
        return (existing.trim().takeUnless { it == "null" }.orEmpty().split(':')
            .filter { it.isNotBlank() } + component).distinct().joinToString(":")
    }
    fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
}

enum class SetupGrant { OVERLAY, ACCESSIBILITY, SECURE_SETTINGS, TERMUX_COMMAND, MICROPHONE, NOTIFICATIONS, STORAGE, BATTERY }
