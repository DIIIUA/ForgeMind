package com.forgemind.app.bridge

object TermuxCallbackStatus {
    // Termux uses Activity.RESULT_OK (-1); older callbacks may omit the field (0).
    fun failed(code: Int, message: String?) = code !in setOf(-1, 0) || !message.isNullOrBlank()
}
