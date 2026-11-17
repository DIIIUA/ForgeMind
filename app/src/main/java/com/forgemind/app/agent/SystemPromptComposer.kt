package com.forgemind.app.agent

/** Adds editable instructions to the application prompt; it never substitutes the application base. */
object SystemPromptComposer {
    fun compose(
        application: String,
        modelInstructions: String = "",
        globalInstructions: String = "",
        chatInstructions: String = "",
        environmentReference: String = "",
        assistantInstructions: String = ""
    ): String = buildString {
        append(application.trim())
        val editable = listOf(modelInstructions, globalInstructions, assistantInstructions, chatInstructions)
        if (editable.any { it.isNotBlank() }) {
            append("\n\n=== APPLICATION OF SYSTEM INSTRUCTIONS ===\n")
            append("The application instructions and the model, global, assistant-profile and current-chat instructions below are active parts of this same system message, with equal authority. ")
            append("Apply the editable instructions directly to your answers and actions; they are not quoted conversation, optional suggestions or text merely to describe. ")
            append("More specific current-chat instructions refine general defaults and global instructions. Keep the application's environment and tool descriptions when applying these settings.")
        }
        fun section(title: String, text: String) {
            if (text.isNotBlank()) append("\n\n=== $title ===\n${text.trim()}")
        }
        section("MODEL INSTRUCTIONS", modelInstructions)
        section("GLOBAL INSTRUCTIONS FOR ALL CHATS", globalInstructions)
        section("ASSISTANT PROFILE INSTRUCTIONS", assistantInstructions)
        section("CURRENT CHAT INSTRUCTIONS", chatInstructions)
        section("CURRENT ENVIRONMENT REFERENCE", environmentReference)
    }.trim()
}
