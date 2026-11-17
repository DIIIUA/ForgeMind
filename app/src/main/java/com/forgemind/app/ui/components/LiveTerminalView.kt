package com.forgemind.app.ui.components

import android.content.Context
import android.graphics.Typeface
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/** ANSI screen, cursor, resize and IME input come from the terminal widget itself. */
@Composable
fun LiveTerminalView(session: TerminalSession, revision: Long, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            TerminalView(context, null).apply {
                val terminal = this
                var textPx = (13f * resources.displayMetrics.scaledDensity).toInt().coerceAtLeast(12)
                setTextSize(textPx)
                setTypeface(Typeface.MONOSPACE)
                setTerminalViewClient(object : TerminalViewClient {
                    override fun onScale(scale: Float): Float {
                        if (scale < 0.9f || scale > 1.1f) {
                            textPx = (textPx * scale).toInt().coerceIn(12, 48)
                            terminal.setTextSize(textPx)
                            return 1f
                        }
                        return scale
                    }
                    override fun onSingleTapUp(e: MotionEvent) {
                        terminal.requestFocus()
                        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                            .showSoftInput(terminal, InputMethodManager.SHOW_IMPLICIT)
                    }
                    override fun shouldBackButtonBeMappedToEscape() = false
                    override fun shouldEnforceCharBasedInput() = false
                    override fun shouldUseCtrlSpaceWorkaround() = false
                    override fun isTerminalViewSelected() = terminal.hasFocus()
                    override fun copyModeChanged(copyMode: Boolean) = Unit
                    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession) = false
                    override fun onKeyUp(keyCode: Int, e: KeyEvent) = false
                    override fun onLongPress(event: MotionEvent) = false
                    override fun readControlKey() = false
                    override fun readAltKey() = false
                    override fun readShiftKey() = false
                    override fun readFnKey() = false
                    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession) = false
                    override fun onEmulatorSet() = Unit
                    override fun logError(tag: String, message: String) { Log.e(tag, message) }
                    override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
                    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
                    override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
                    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
                    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
                    override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, e.message, e) }
                })
                attachSession(session)
            }
        },
        update = { view ->
            view.attachSession(session)
            // Read by Compose so updates from a detached, still-running session redraw on return.
            @Suppress("UNUSED_VARIABLE") val observedRevision = revision
            view.onScreenUpdated()
        }
    )
}
