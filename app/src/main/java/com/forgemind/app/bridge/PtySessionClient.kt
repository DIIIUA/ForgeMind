package com.forgemind.app.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

/** Session callbacks are delivered on the main thread, independently of the current screen. */
internal class PtySessionClient(
    private val context: Context,
    private val changed: (TerminalSession) -> Unit
) : TerminalSessionClient {
    override fun onTextChanged(session: TerminalSession) = changed(session)
    override fun onTitleChanged(session: TerminalSession) = changed(session)
    override fun onSessionFinished(session: TerminalSession) = changed(session)
    override fun onColorsChanged(session: TerminalSession) = changed(session)
    override fun onTerminalCursorStateChange(state: Boolean) = Unit
    override fun getTerminalCursorStyle(): Int? = null
    override fun onBell(session: TerminalSession) = Unit
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Terminal", text))
    }
    override fun onPasteTextFromClipboard(session: TerminalSession) {
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip ?: return
        if (clip.itemCount > 0) session.emulator?.paste(clip.getItemAt(0).coerceToText(context).toString())
    }
    override fun logError(tag: String, message: String) { Log.e(tag, message) }
    override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
    override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, e.message, e) }
}
