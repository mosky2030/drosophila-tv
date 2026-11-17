package app.orchard.tv

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import app.orchard.tv.wallpaper.Wallpapers

/**
 * The puzzle game: a WebView shell over the HTML prototype bundled in
 * assets/puzzlegame/. The prototype already draws its own focus, reads the
 * arrow/Enter keys and scales a 1920x1080 design to any screen, so the shell
 * only has to keep the keys flowing and the game full screen.
 *
 * DPAD keys are intercepted here and re-sent to the page as DOM key events:
 * WebView's own focus navigation could steal presses from the game's custom
 * cursor, and dispatching them ourselves keeps one code path for every key.
 * Back leaves the game, back to Home, through a short fade of the whole window
 * (see [leave] below: finishing outright cut to Home in one frame).
 *
 * Entering from Home: Home's clock and tiles fade with its window, but its aerial
 * film is a SurfaceView outside that window and cannot fade — it used to snap to
 * black a moment later, which read as a flash. Now the game opens over a black
 * window with an explicit fade-in: the whole Home (picture, clock, tiles) fades
 * out together behind it, then the board fades in from black (index.html's curtain).
 * No wallpaper backdrop is laid behind the WebView: it was that still picture,
 * showing through the transparent WebView until the page drew, which made the
 * wallpaper look intact and then vanish abruptly when the page covered it.
 */
class PuzzleActivity : Activity() {
    private lateinit var web: WebView
    /** Back was pressed: the game is leaving and must not take another key. */
    private var leaving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Cold starts (a direct `am start` on the tile, or the puzzle as the first thing after
        // boot) can reach here before Home has initialised the wallpapers. Without this, the page
        // is served without ?img= and falls back to the bundled sand-dunes picture instead of the
        // daily photo the owner is actually looking at. init() is idempotent, so Home's own path
        // is unaffected.
        Wallpapers.init(this)
        // Fade the game window in over black as Home fades out behind it, so the picture,
        // clock and tiles go together and the board arrives from black rather than snapping
        // in over a wallpaper that had appeared to stay (see the file header).
        // Both windows cross-fade at once: Home (picture + clock + tiles, all inside its window)
        // fades out while this black window fades in, so the screen darkens to black in one
        // continuous motion; then index.html's curtain brings the board up from black.
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        val root = FrameLayout(this)
        web = WebView(this)
        // Some sets' GPU drivers cannot give Chromium an EGL config at all (the owner's MiTV4:
        // `eglChooseConfig failed with error EGL_BAD_ATTRIBUTE`, Android 6, Chromium 66). The
        // page then loads and finishes and draws NOTHING — a black window read as a broken game.
        // Software keeps the compositor off that driver: the board is one picture and a few
        // sprites, and a CPU has plenty for it.
        web.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true     // the game keeps progress + tuning in localStorage
            mediaPlaybackRequiresUserGesture = false
            textZoom = 100
            // Deliberately NOT useWideViewPort / loadWithOverviewMode: with the page's own
            // device-width viewport they double-scale the 1920x1080 design and the game
            // shows as an enlarged corner of the picture.
        }
        // On this TV image WebView settles on a page zoom of ~1.75x its layout viewport
        // (visual 1680x945 over a layout of 960x540 at density 320), which magnifies the
        // game into a crop of the wallpaper. Pin the initial scale to 1:1 CSS:device.
        web.setInitialScale(100)
        // Black, not transparent: there is no wallpaper backdrop any more, so the moment
        // before the page draws must be a clean black stage for the board to fade in on.
        web.setBackgroundColor(Color.BLACK)
        root.addView(web, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        web.requestFocus()
        // saver=1: Home's tile means "play the puzzle" — skip the prototype's own desktop view
        // (its icon row is a dev surface) and go straight to the board. The shell also hands the
        // page whatever picture Home is showing, so the board is that picture.
        PuzzleShell.bind(web)
        web.loadUrl(PuzzleShell.page())
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // Picking up a tile then changing your mind: Back should put it down, not leave the
            // game. Ask the page whether a tile is held; only leave when the hand is empty.
            // The page maps Escape -> back -> keyBack(), which clears the held tile and focus.
            web.evaluateJavascript(
                "(function(){var s=window.__puzzle;return !!(s && s.carry!==null && s.carry!==undefined);})()"
            ) { held ->
                if (held == "true") {
                    web.evaluateJavascript(
                        "(function(){document.dispatchEvent(new KeyboardEvent('keydown'," +
                            "{key:'Escape',bubbles:true,cancelable:true}));})()", null)
                } else {
                    leave()
                }
            }
            return true
        }
        if (leaving) return true
        val key = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> "ArrowUp"
            KeyEvent.KEYCODE_DPAD_DOWN -> "ArrowDown"
            KeyEvent.KEYCODE_DPAD_LEFT -> "ArrowLeft"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "ArrowRight"
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> "Enter"
            KeyEvent.KEYCODE_MENU -> "m"            // the game's own menu
            else -> return super.onKeyDown(keyCode, event)
        }
        // Not passed to super: WebView would also navigate focus or scroll on its own.
        val js = ("(function(){document.dispatchEvent(new KeyboardEvent('keydown',"
            + "{key:'" + key + "',bubbles:true,cancelable:true}));})()")
        web.evaluateJavascript(js, null)
        return true
    }

    /**
     * Leaves the game: the window itself fades out over Home (the system animates the window's
     * surface, so Home shows through as the game goes). Fading the views inside the window was
     * tried first: the window's own background (this theme's black) does not go with them, and
     * the screen read as fade-to-black, then Home cut in. Finishing with no animation was the
     * original complaint: one frame, the game gone.
     */
    private fun leave() {
        if (leaving) return
        leaving = true
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, R.anim.fade_out)
    }

    /** A back the system takes for us (a remote's, or the shell's), the same fade as the key. */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        leave()
    }

    override fun onPause() {
        super.onPause()
        web.onPause()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
