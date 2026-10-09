package app.stmobile.ui

import android.webkit.JavascriptInterface
import app.stmobile.foreground.ForegroundService

/**
 * JavaScript interface exposed to the WebView to relay SillyTavern client-side
 * generation events (generation_started, generation_ended, generation_stopped)
 * to ForegroundService.
 */
class GenerationJavascriptBridge(
    private val onStarted: () -> Unit = { ForegroundService.onGenerationStarted("webview") },
    private val onEnded: () -> Unit = { ForegroundService.onGenerationEnded("webview") },
) {

    @JavascriptInterface
    fun onGenerationStarted() {
        onStarted()
    }

    @JavascriptInterface
    fun onGenerationEnded() {
        onEnded()
    }
}
