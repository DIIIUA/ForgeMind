package com.forgemind.app.ui.screens.preview

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.viewinterop.AndroidView
import com.forgemind.app.web.AgentBrowserController

/** The controller owns persistent tabs; each composition owns only its host. */
@Composable
internal fun BrowserPage(
    controller: AgentBrowserController,
    forAgent: Boolean,
    tabId: String,
    modifier: Modifier = Modifier
) {
    key(controller, forAgent, tabId) {
        AndroidView(
            modifier = modifier.clipToBounds(),
            factory = { context ->
                FrameLayout(context).apply {
                    clipChildren = true
                    clipToPadding = true
                    addView(
                        controller.acquireAttachedWebView(forAgent = forAgent, tabId = tabId),
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                }
            },
            onRelease = { host ->
                // A replacement host may already own this or another tab. Never
                // detach by mode/tab id here, and never destroy persistent pages.
                host.removeAllViews()
            },
            update = {}
        )
    }
}
