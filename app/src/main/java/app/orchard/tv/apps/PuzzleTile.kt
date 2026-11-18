package app.orchard.tv.apps

import android.content.ComponentName

/**
 * The tile that opens Aerial's own puzzle game (PuzzleActivity, a WebView shell over the
 * bundled HTML prototype). A real entry, not a look-test one: it is always on Home and it
 * launches. It is appended after LayoutStore's arrangement, so like the demo tiles it is
 * not part of the saved layout.
 */
object PuzzleTile {
    const val KEY = "puzzle-game"

    fun entry(): AppEntry = AppEntry(
        packageName = KEY,
        label = "壁纸拼图",
        component = ComponentName("app.orchard.tv", "app.orchard.tv.PuzzleActivity"),
        leanback = false,
        removable = false,
        art = TileArtFactory.forSystem(SystemTile.Photos),
    )
}
