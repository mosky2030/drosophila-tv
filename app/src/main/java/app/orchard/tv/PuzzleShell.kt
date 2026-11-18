package app.orchard.tv

import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import app.orchard.tv.system.DiagLog
import app.orchard.tv.wallpaper.Wallpapers
import java.io.ByteArrayInputStream

/**
 * The puzzle game's window: a WebView over the HTML prototype in assets/puzzlegame/, played on
 * whatever picture Home is showing.
 *
 * The page is a file:// document inside the app's own storage, so it cannot read the pictures kept
 * in the app's private directory — Bing's daily download, the owner's own photos. The shell serves
 * them instead: the page asks for [PICTURE] and [bind] answers with the same picture Home has, cut
 * to the game's size, so the board is the wallpaper the owner is looking at. When Home has no
 * picture of its own (following the focused app), the page falls back to the four bundled ones.
 *
 * Used by both ways into the game — Home's tile ([PuzzleActivity]) and the screensaver
 * (ui/home/Screensaver.kt), which keeps the two looking and behaving the same.
 */
object PuzzleShell {
    /** The page in its full-screen form; `&img=` asks it to play Home's picture instead. */
    private const val PAGE = "file:///android_asset/puzzlegame/index.html?saver=1"

    /** Where the page fetches Home's picture from. Nothing answers it but [bind]. */
    private const val PICTURE = "https://aerial.local/home.jpg"

    /** The size the picture is served at: the game's own 1920x1080 design. */
    private const val W = 1920
    private const val H = 1080

    /**
     * The page's address: Home's own picture when there is one, else the four bundled ones.
     *
     */
    fun page(): String =
        if (Wallpapers.hasPictureToPuzzle()) PAGE + "&img=" + Uri.encode(PICTURE) else PAGE

    /**
     * Wires [web] to hand Home's picture to the page. Call before loading [page]: the page asks for
     * it while it is being parsed.
     */
    fun bind(web: WebView, onReady: () -> Unit = {}) {
        // DevTools over adb (`chrome://inspect`), to ask the page on the set itself what it sees
        // when it will not draw. Remote debugging needs adb; a television has no browser beside it.
        WebView.setWebContentsDebuggingEnabled(true)
        // The page's own voice, in the log: an old WebView (Chromium 66 on the owner's MiTV4,
        // Android 6) that throws on a modern line of it leaves a black window and says nothing
        // anywhere the app can see. DiagLog writes to logcat always, and to a file when diag is on.
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                DiagLog.i("puzzle console [${m.messageLevel()}] ${m.sourceId()}:${m.lineNumber()} ${m.message()}")
                return true
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                DiagLog.i("puzzle page finished: $url")
                // The shell's black cover can come off now: past this point the page's own dark
                // curtain is drawn, so nothing uninitialised can show at the edges (the owner,
                // 2026-11-18: the TV flashed colour along the right and bottom on the way in).
                view.post(onReady)
            }

            // The API-1 overload: it is the one an Android 6 WebView calls.
            @Suppress("DEPRECATION", "OverridingDeprecatedMember")
            override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
                DiagLog.i("puzzle page error $errorCode: $description ($failingUrl)")
            }

            // Called on a background thread, so decoding here does not stall the page.
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (!request.url.toString().startsWith(PICTURE)) return null
                val bytes = runCatching { Wallpapers.currentPictureJpeg(W, H) }.getOrNull() ?: return null
                return WebResourceResponse("image/jpeg", null, ByteArrayInputStream(bytes))
            }
        }
    }
}
