package com.forgemind.app.setup

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class SetupWizardStore(context: Context) {
    // Separate from portable settings: restoring .fms must not dismiss a guide on a new phone.
    private val prefs = context.getSharedPreferences("forgemind_setup", Context.MODE_PRIVATE)
    private val _progress = MutableStateFlow(SetupProgress.restore(
        prefs.getString("step", null), prefs.getString("disposition", null), prefs.getString("mode", null)))
    val progress = _progress.asStateFlow()

    private fun save(value: SetupProgress) {
        prefs.edit().putString("step", value.step.name)
            .putString("disposition", value.disposition.name).putString("mode", value.mode.name).apply()
        _progress.value = value
    }

    fun visit(step: SetupStep) = save(_progress.value.visit(step))
    fun defer() = save(_progress.value.defer())
    fun complete() = save(_progress.value.complete())
    fun reopen() = save(_progress.value.reopen())
    fun mode(mode: SetupMode) = save(_progress.value.copy(mode = mode))
    val preparationAttempted get() = prefs.getBoolean("termux_preparation_attempted", false)
    fun recordPreparationAttempt() {
        check(prefs.edit().putBoolean("termux_preparation_attempted", true).commit())
    }
}
