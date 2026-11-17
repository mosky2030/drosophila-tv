package app.orchard.tv.apps

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import androidx.annotation.RequiresApi
import android.os.Build
import android.graphics.drawable.Drawable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.settings.IconLook
import app.orchard.tv.system.DiagLog
import app.orchard.tv.ui.theme.Glyph
import app.orchard.tv.ui.theme.drawGlyph
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import app.orchard.tv.ui.components.smoothRectPath
import app.orchard.tv.wallpaper.Images
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * What a tile draws. tvOS icons are layered so they can shift against each other when focused;
 * Android's adaptive icons happen to be layered the same way, so those keep their two layers here.
 *
 * - [back] always fills the tile: the app's TV banner, the adaptive icon's background layer, or a
 *   plate in the icon's own color.
 * - [fore] is drawn on top at [foreAt] (fractions of the tile), and moves with parallax. It is cut
 *   down to its visible pixels: tiles are drawn every frame, and the TV's GPU is short of fill rate.
 */
class TileArt(
    val back: ImageBitmap,
    val fore: ImageBitmap?,
    val foreAt: androidx.compose.ui.geometry.Rect,
    val tint: Color,
    /**
     * The whole home backdrop for this app, baked small: its blurred artwork as ambient light plus
     * the shading that keeps the clock and grid legible. Scaled up to full screen it costs the GPU
     * one textured draw per frame, which matters on TV chips.
     */
    val backdrop: ImageBitmap,
    /** A stand-in in the app's color while its picture is still being read (see AppCatalog.load). */
    val placeholder: Boolean = false,
    /**
     * A separate 1:1 artwork for a square slot (the wheel and the dock). A standard launcher icon
     * is square: there it is drawn whole, without cropping it to 16:9 first. Null means the square
     * slot cuts the middle out of [back], as it does for Aerial's own glyph tiles and in dopamine.
     */
    val square: ImageBitmap? = null,
) {
    fun withSquare(square: ImageBitmap?): TileArt =
        TileArt(back, fore, foreAt, tint, backdrop, placeholder, square)
}

/** Firmware apps Aerial draws its own tile for, each in the color that tells the kind apart. */
enum class SystemTile(top: Long, bottom: Long, val glyph: Glyph) {
    // The three Xiaomi system tiles (settings, player, notifications) used to wear the firmware's
    // 16:9 banner icon, which read as a wide bar under a square logo (the owner: 「系统三图标…
    // 下面有个宽条」). Drawn as Aerial's own tile in the TV-maker's blue-violet, they match the
    // stock launcher's rounded-square icons and stay the same on any Android TV (no APK resource).
    Settings(0xFF5B8CFF, 0xFF3A4FD8, Glyph.Gear),
    Photos(0xFFFFB340, 0xFFFF5E3A, Glyph.Photo),
    Media(0xFF4A9BFF, 0xFF1E5FD8, Glyph.Play),
    Notifications(0xFF8B7BFF, 0xFF4F42D8, Glyph.Bell),
    Store(0xFF40B0FF, 0xFF0A5FD8, Glyph.Bag),
    Weather(0xFF5AC8FA, 0xFF1C6FD6, Glyph.CloudSun),
    Calendar(0xFFFF6B5E, 0xFFD02A1E, Glyph.Calendar),
    Cast(0xFF8E8CFF, 0xFF4744D0, Glyph.Cast),
    Security(0xFF3ED97A, 0xFF138A47, Glyph.Shield),
    Browser(0xFF64D2FF, 0xFF0A7BEA, Glyph.Globe),
    /** The TV's own launcher, where it has no entry of its own (AppCatalog.stockLaunchers). */
    Home(0xFF7D7AFF, 0xFF3634A3, Glyph.Home),
    /** The stock launcher's children's mode (AppCatalog.shortcuts). */
    Kids(0xFFFF8FB1, 0xFFE0457B, Glyph.Kids);

    val top = top.toInt()
    val bottom = bottom.toInt()
}

object TileArtFactory {
    /**
     * Pixel size of the artwork: the tile at its focused size on this screen, so the lifted tile is
     * still pixel-sharp. 352×198 on a 1080p UI, twice that on a TV that draws its UI at 4K.
     */
    @Volatile var W = 352; private set
    @Volatile var H = 198; private set

    private var density = 2f

    /** Called with [app.orchard.tv.ui.theme.canvasDensity] before any tile is built. */
    fun useCanvasDensity(density: Float) {
        this.density = density
        fitColumns()
    }

    /**
     * Sizes the artwork for AerialSettings.homeColumns; true when that changed it, and the tiles
     * want drawing again. 176dp: the 147dp tile of five a row at its 1.15× focus scale, with a
     * little to spare; 222dp the 187dp one of four. Six a row stays at five's: it is smaller still.
     */
    fun fitColumns(): Boolean {
        val dp = if (AerialSettings.homeColumns == 4) 222 else 176
        val w = (dp * density).roundToInt().coerceIn(352, 888)
        if (w == W) return false
        W = w
        H = W * 9 / 16
        return true
    }

    /** How large a logo is, as the side of the square of the same area, in tile heights. */
    private const val LOGO = 0.5f

    /** The resolution an icon is sampled at to find its logo. */
    private const val SAMPLE = 256

    private const val GRAPHITE_TOP = 0xFF3A3A3C.toInt()
    private const val GRAPHITE_BOTTOM = 0xFF242426.toInt()
    // A dark logo on transparency used to sit on a black plate: in the original look every such
    // icon gets a soft warm yellow instead, the way the owner's previous Aerial did.
    private const val PALE_YELLOW_TOP = 0xFFFFB340.toInt()
    private const val PALE_YELLOW_BOTTOM = 0xFFFF5E3A.toInt()

    /**
     * A banner that is a picture keeps its own pixels (up to twice the tile), cut to 16:9 only, so
     * the tile resamples it once on its way to the screen. Drawn at W×H first, a 320×180 banner was
     * resampled three times (320 → 352 → tile → focus scale) and read soft beside launchers that
     * show it directly (issue #307, 奇异果 on Xiaomi MiTV-MFTR0).
     */
    fun fromBanner(banner: Drawable): TileArt {
        val src = (banner as? BitmapDrawable)?.bitmap
        val w = src?.width?.coerceAtMost(2 * W) ?: W
        val h = w * 9 / 16
        val back = createBitmap(w, h)
        Canvas(back).apply {
            drawColor(GRAPHITE_BOTTOM)
            if (src != null) drawCover(banner, w, h, src.width, src.height) else drawCover(banner, w, h)
        }
        return finish(back, null)
    }

    /**
     * A TV input's tile, in the spirit of an app icon: a white mark over a gradient, the input's
     * name under it and [sub] (the port under a chosen name, or the HDMI-CEC device name) as a
     * second line. The connector's glyph over a color that tells the kind apart (HDMI blue, DP
     * violet, tuner teal, AV amber, USB rose), or with a [device] chosen from the tile's menu, that device's
     * colours and mark. A null [label] shows the mark alone, larger: for a chosen device, whose
     * mark says what it is.
     */
    fun forSource(kind: app.orchard.tv.system.SourceKind, label: String?, sub: String?, device: SourceDevice? = null): TileArt {
        val (top, bottom) = if (device != null) device.top to device.bottom else when (kind) {
            app.orchard.tv.system.SourceKind.Hdmi -> 0xFF3B6FD8.toInt() to 0xFF1D3A86.toInt()
            app.orchard.tv.system.SourceKind.DisplayPort -> 0xFF6E5BD8.toInt() to 0xFF3A2D86.toInt()
            app.orchard.tv.system.SourceKind.Tuner -> 0xFF1FA88E.toInt() to 0xFF0E5C55.toInt()
            app.orchard.tv.system.SourceKind.Av -> 0xFFE0912F.toInt() to 0xFF9A4F12.toInt()
            app.orchard.tv.system.SourceKind.Usb -> 0xFFD8457A.toInt() to 0xFF861E47.toInt()
            app.orchard.tv.system.SourceKind.Other -> 0xFF5E6470.toInt() to 0xFF30343C.toInt()
        }
        val data = device?.mark ?: when (kind) {
            app.orchard.tv.system.SourceKind.Hdmi, app.orchard.tv.system.SourceKind.DisplayPort -> app.orchard.tv.ui.theme.GlyphPaths.HDMI
            app.orchard.tv.system.SourceKind.Tuner -> app.orchard.tv.ui.theme.GlyphPaths.ANTENNA
            app.orchard.tv.system.SourceKind.Usb -> app.orchard.tv.ui.theme.GlyphPaths.USB
            else -> app.orchard.tv.ui.theme.GlyphPaths.COMPONENT
        }
        val path = androidx.core.graphics.PathParser.createPathFromPathData(data)
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        if (label == null) {
            // The mark alone in a square half the tile's height: Material's own 960-unit box,
            // whose y runs from -960 to 0, or a logo's 24-unit box.
            val g = H * 0.5f
            val box = device?.markBox ?: 960f
            val fore = layer {
                translate((W - g) / 2f, (H - g) / 2f)
                scale(g / box, g / box)
                if (box == 960f) translate(0f, 960f)
                // The same soft shadow as under the glyphs.
                val drop = box * 0.025f
                for ((dy, a) in listOf(drop * 2f to 0x0F, drop to 0x1F)) {
                    translate(0f, dy)
                    drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = a shl 24 })
                    translate(0f, -dy)
                }
                drawPath(path, white)
            }
            return finish(colorTile(top, bottom), fore)
        }
        val back = colorTile(top, bottom)
        Canvas(back).apply {
            val two = sub != null
            // Every mark in the same square box, as Aerial's glyphs: Material's own 960-unit box,
            // whose y runs from -960 to 0, or a logo's 24-unit box inset by Material's margin.
            val g = H * (if (two) 0.36f else 0.42f)
            val box = device?.markBox ?: 960f
            save()
            translate((W - g) / 2f, H * (if (two) 0.31f else 0.36f) - g / 2f)
            if (box == 960f) {
                scale(g / 960f, g / 960f)
                translate(0f, 960f)
            } else {
                translate(g / 12f, g / 12f)
                scale(g * 5f / 6f / box, g * 5f / 6f / box)
            }
            drawPath(path, white)
            restore()
            val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt(); textSize = H * 0.15f; textAlign = Paint.Align.CENTER
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            }
            // A name typed on the phone can be long: smaller type first, then cut short.
            var shown: String = label
            while (title.measureText(shown) > W * 0.88f && title.textSize > H * 0.11f) title.textSize *= 0.92f
            while (title.measureText(shown) > W * 0.88f && shown.length > 1) shown = shown.dropLast(2) + "…"
            drawText(shown, W / 2f, H * (if (two) 0.68f else 0.76f), title)
            if (sub != null) {
                val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt(); textSize = H * 0.11f; textAlign = Paint.Align.CENTER }
                drawText(sub, W / 2f, H * 0.85f, small)
            }
        }
        return finish(back, null)
    }

    /**
     * A firmware app (settings, photos, weather...) drawn as Aerial's own tile: a white glyph over
     * a color that tells the kind apart, the way tvOS draws its system apps. TV makers ship these
     * with phone icons that look different on every TV and unlike the apps around them.
     */
    fun forSystem(kind: SystemTile): TileArt = glyphTile(kind.top, kind.bottom, kind.glyph)

    /**
     * One of Aerial's own glyphs, in the look chosen in Personalize. The tiles Aerial draws itself
     * (settings, photos, the puzzle game, a TV input's neighbours) follow it too, as the app icons
     * do, so the grid is in one style.
     */
    fun glyphTile(top: Int, bottom: Int, glyph: Glyph): TileArt = when (AerialSettings.iconLook) {
        IconLook.Original -> glyphTileOf(top, bottom, glyph)
        // Its own bright color, the same one on every reload, taken from what the tile is rather
        // than from the color it no longer wears.
        IconLook.Dopamine -> dopaminePair(top xor (glyph.ordinal * 7919)).let { (t, b) -> glyphTileOf(t, b, glyph) }
    }

    /** The glyph in white over the tile's own color, as Aerial has always drawn its own tiles. */
    private fun glyphTileOf(top: Int, bottom: Int, glyph: Glyph): TileArt {
        val back = colorTile(top, bottom)
        // The glyph on its own layer, so it shifts against the color when the tile leans. The
        // symbols keep a margin inside their box, so the box is larger than the glyph looks.
        val g = H * 0.58f
        val fore = layer {
            translate((W - g) / 2f, (H - g) / 2f)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(this), Size(g, g)) {
                // A soft shadow under the glyph lifts it off the color, as on tvOS's own icons.
                val drop = g * 0.025f
                for ((dy, a) in listOf(drop * 2f to 0.06f, drop to 0.12f)) {
                    drawContext.transform.translate(0f, dy)
                    paintGlyph(glyph, Color.Black.copy(alpha = a), shadow = true)
                    drawContext.transform.translate(0f, -dy)
                }
                paintGlyph(glyph, Color.White, shadow = false)
            }
        }
        return finish(back, fore)
    }

    /**
     * Weather gets its colors, as on tvOS: a warm sun half behind a white cloud. Everything else is
     * one white glyph.
     */
    private fun androidx.compose.ui.graphics.drawscope.DrawScope.paintGlyph(glyph: Glyph, color: Color, shadow: Boolean) {
        if (glyph != Glyph.CloudSun) return drawGlyph(glyph, color)
        val g = size.minDimension
        drawContext.transform.translate(-g * 0.14f, -g * 0.15f)
        scale(0.78f, pivot = androidx.compose.ui.geometry.Offset(g / 2, g / 2)) {
            drawGlyph(Glyph.Sun, if (shadow) color else Color(0xFFFFD60A))
        }
        drawContext.transform.translate(g * 0.24f, g * 0.27f)
        scale(0.86f, pivot = androidx.compose.ui.geometry.Offset(g / 2, g / 2)) { drawGlyph(Glyph.Cloud, color) }
        drawContext.transform.translate(-g * 0.10f, -g * 0.12f)
    }

    /**
     * A folder, as on tvOS: a frosted plate holding up to nine of its apps, three by three, in
     * their own colors.
     *
     * [square] is the dock's folder: that tile is cut to the middle square of this artwork, so only
     * apps drawn inside it would survive. Four of them, two by two and square like the dock's own
     * icons, sit there whole — nine had their outer columns sliced off by the cut (issue #703).
     * [seed] is the folder's own, so its plate wears the same color every time.
     */
    fun forFolder(apps: List<TileArt>, square: Boolean = false, seed: Int = 0): TileArt {
        val back = layer {
            // A plate behind the apps, in the look the apps inside it wear: a pale one in the
            // original look, its own bright color in the dopamine look (the owner: 「多巴胺图标，
            // 群组图标背景给点颜色，现在是纯白色」).
            when (AerialSettings.iconLook) {
                IconLook.Dopamine -> {
                    val (top, bottom) = dopaminePair(seed)
                    drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
                        shader = LinearGradient(0f, 0f, W * 0.4f, H.toFloat(), top, bottom, Shader.TileMode.CLAMP)
                    })
                }
                IconLook.Original -> drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply { color = 0x40FFFFFF })
            }
            drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, H.toFloat(), 0x22FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
            })
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            if (square) {
                // The middle square of the artwork: everything drawn here is what the dock shows.
                val side = H.toFloat()
                val pad = side * 0.10f
                val gap = side * 0.06f
                val cell = (side - 2 * pad - gap) / 2f
                val left = (W - H) / 2f + pad
                apps.take(4).forEachIndexed { i, art ->
                    val x = left + (i % 2) * (cell + gap)
                    val y = pad + (i / 2) * (cell + gap)
                    // Each app's own middle square, as the dock's single icons are drawn.
                    val flat = flatten(art)
                    val s = cell / minOf(flat.width, flat.height)
                    paint.shader = android.graphics.BitmapShader(flat, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                        setLocalMatrix(
                            android.graphics.Matrix().apply {
                                setScale(s, s)
                                postTranslate(x - (flat.width - minOf(flat.width, flat.height)) / 2f * s, y)
                            },
                        )
                    }
                    drawPath(smoothRect(RectF(x, y, x + cell, y + cell), cell * 0.22f), paint)
                }
            } else {
                val pad = H * 0.09f
                val gap = H * 0.045f
                val th = (H - 2 * pad - 2 * gap) / 3f
                val tw = th * 16f / 9f
                val left = (W - 3 * tw - 2 * gap) / 2f
                apps.take(9).forEachIndexed { i, art ->
                    val x = left + (i % 3) * (tw + gap)
                    val y = pad + (i / 3) * (th + gap)
                    val flat = flatten(art)
                    paint.shader = android.graphics.BitmapShader(flat, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                        setLocalMatrix(android.graphics.Matrix().apply { setScale(tw / flat.width, th / flat.height); postTranslate(x, y) })
                    }
                    drawPath(smoothRect(RectF(x, y, x + tw, y + th), th * 0.14f), paint)
                }
            }
        }
        return finish(back, null)
    }

    /** Aerial's own tile color: a diagonal gradient with soft light from the top left. */
    private fun colorTile(top: Int, bottom: Int): Bitmap = layer {
        drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, W * 0.4f, H.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        })
        drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
            shader = android.graphics.RadialGradient(W * 0.25f, -H * 0.2f, W * 0.8f, 0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        })
    }

    /**
     * A tile drawn from the app's own launcher icon, and nothing else: the owner asked for exactly
     * that (「都使用APP本身的图标图案」), so no per-package recipe recolors it and no banner replaces
     * it. How it is painted is the one look chosen in Personalize ([IconLook]); [seed] keeps an
     * app's own color stable across reloads (the package name's hash).
     */
    /**
     * One rule for every standard app, in both looks: take the icon as the app drew it, drop a
     * firmware label strip if the picture has one, then put the whole square icon in the middle of
     * the 16:9 tile at its own aspect. Nothing is pulled out of it, so a corner badge cannot pull
     * the mark off centre and nothing is stretched or cropped. The original look uses the icon's
     * own edge colors behind it; dopamine uses its bright plate. A square slot uses a 1:1 copy.
     */
    fun fromIcon(icon: Drawable, seed: Int = 0): TileArt {
        val src = stripFree(icon)
        val base = when (AerialSettings.iconLook) {
            IconLook.Original -> original(src)
            IconLook.Dopamine -> dopamine(src, seed)
        }
        return if (AerialSettings.iconLook == IconLook.Original) base.withSquare(originalSquare(src)) else base
    }

    /** A firmware banner's plain bottom band is not part of the app icon: drop it before centring. */
    private fun stripFree(icon: Drawable): Drawable {
        val iw = icon.intrinsicWidth
        val ih = icon.intrinsicHeight
        if (iw <= 0 || ih <= 0) return icon
        val aspect = iw / ih.toFloat()
        if (aspect < 1.45f || aspect > 2.2f) return icon
        val from = bottomStrip(icon, iw, ih) ?: return icon
        val full = rasterBitmap(icon, iw, ih)
        val crop = Bitmap.createBitmap(full, 0, 0, iw, ih - from)
        full.recycle()
        return BitmapDrawable(crop)
    }

    /**
     * The 16:9 artwork in the original look: the icon's own edge colors across the tile, the icon
     * itself whole and centred. Its height is the tile's less a small margin, so a square icon sits
     * as a square in the middle instead of filling (and overflowing) the wide tile.
     */
    private fun original(icon: Drawable): TileArt {
        val n = SAMPLE
        val px = raster(icon, n)
        val box = solidBox(px, n)
        val pale = needsPaleYellow(px)
        val top = if (pale) PALE_YELLOW_TOP else box?.let { bandColor(px, n, it, top = true) } ?: GRAPHITE_TOP
        val bottom = if (pale) PALE_YELLOW_BOTTOM else box?.let { bandColor(px, n, it, top = false) } ?: top
        return centeredTile(icon, top, bottom, bright = false)
    }

    /** The same centred icon over dopamine's bright plate, stable for the same app. */
    private fun dopamine(icon: Drawable, seed: Int): TileArt {
        val (top, bottom) = dopaminePair(seed)
        return centeredTile(icon, top, bottom, bright = true)
    }

    /** Draws [icon] whole, at its own aspect, centred in the 16:9 tile. */
    private fun centeredTile(icon: Drawable, top: Int, bottom: Int, bright: Boolean): TileArt {
        val back = layer {
            drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
                shader = LinearGradient(0f, 0f, if (bright) W * 0.4f else 0f, H.toFloat(), top, bottom, Shader.TileMode.CLAMP)
            })
            if (bright) drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
                shader = android.graphics.RadialGradient(W * 0.25f, -H * 0.2f, W * 0.8f, 0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
            })
            val iw = icon.intrinsicWidth.coerceAtLeast(1)
            val ih = icon.intrinsicHeight.coerceAtLeast(1)
            val pad = H * 0.08f
            val k = minOf((W - 2 * pad) / iw, (H - 2 * pad) / ih)
            val dw = (iw * k).roundToInt().coerceAtLeast(1)
            val dh = (ih * k).roundToInt().coerceAtLeast(1)
            val left = ((W - dw) / 2f).roundToInt()
            val topEdge = ((H - dh) / 2f).roundToInt()
            icon.setBounds(left, topEdge, left + dw, topEdge + dh)
            icon.draw(this)
        }
        return finish(back, null)
    }

    /**
     * The artwork for a square slot in the original look. A standard launcher icon is a square
     * picture: at 1:1 it keeps its badge, its corners and the mark where the app drew them. We only
     * take an icon whose opaque pixels really form a near-full square — a wordmark on glass or a
     * circular mark still uses the centred 16:9 artwork, whose middle is cut by the slot. The
     * icon's own top and bottom colors fill any transparent edge; it is fitted, never cropped.
     */
    private fun originalSquare(icon: Drawable): ImageBitmap? {
        val iw = icon.intrinsicWidth
        val ih = icon.intrinsicHeight
        if (iw <= 0 || ih <= 0) return null
        val aspect = iw / ih.toFloat()
        if (aspect < 0.88f || aspect > 1.14f) return null
        val n = SAMPLE
        val px = raster(icon, n)
        val box = solidBox(px, n) ?: return null
        val w = box.width(); val h = box.height()
        if (w < n * 0.8f || h < n * 0.8f || kotlin.math.abs(w - h) > w * 0.08f) return null
        var filled = 0
        for (y in box.top until box.bottom) for (x in box.left until box.right) if (solid(px[y * n + x])) filled++
        if (filled < w * h * 0.9f) return null
        val pale = needsPaleYellow(px)
        val top = if (pale) PALE_YELLOW_TOP else bandColor(px, n, box, top = true) ?: GRAPHITE_TOP
        val bottom = if (pale) PALE_YELLOW_BOTTOM else bandColor(px, n, box, top = false) ?: top
        val side = W
        val out = createBitmap(side, side)
        Canvas(out).apply {
            drawRect(0f, 0f, side.toFloat(), side.toFloat(), Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, side.toFloat(), top, bottom, Shader.TileMode.CLAMP)
            })
            val k = minOf(side.toFloat() / iw, side.toFloat() / ih)
            val dw = (iw * k).roundToInt()
            val dh = (ih * k).roundToInt()
            val left = (side - dw) / 2
            val topEdge = (side - dh) / 2
            icon.setBounds(left, topEdge, left + dw, topEdge + dh)
            icon.draw(this)
        }
        return out.asImageBitmap()
    }

    /**
     * A launcher icon shaped like a TV banner (many China-market firmware apps ship one: 284×160,
     * exactly 16:9 — 电视设置 and 高清播放器 on a Xiaomi MiTV). Squeezed through the square logo
     * pipeline it was stretched into a square, which squashed its mark, and then drawn small in
     * the middle of a tile in its own hue, which read as a box floating on a plate (the owner:
     * 「大方框内有一个小方图标…最好是一个整体，而且电视设置图标图案变形了」). Drawn across the
     * tile it needs no scaling at all, so it fills it whole: one picture, nothing floating,
     * nothing distorted.
     */
    private fun banner(icon: Drawable): TileArt? {
        val iw = icon.intrinsicWidth
        val ih = icon.intrinsicHeight
        if (iw <= 0 || ih <= 0) return null
        val aspect = iw / ih.toFloat()
        if (aspect < 1.45f || aspect > 2.1f) return null
        val n = SAMPLE
        val px = raster(icon, n)
        val box = solidBox(px, n) ?: return null
        val w = box.width(); val h = box.height()
        if (w < n / 2 || h < n / 3) return null
        var filled = 0
        for (y in box.top until box.bottom) for (x in box.left until box.right) if (solid(px[y * n + x])) filled++
        // A filled banner-style picture, not a wide wordmark on a transparent field (a wordmark
        // still goes to the logo pipeline, where it is sized like every other logo).
        if (filled < w * h * 0.85f) return null
        // Some firmware banners (电视设置, 高清播放器, 通知中心) end in a plain label band that is
        // part of the picture: stretched across the tile it reads as a wide bar under a full-size
        // icon. Find it on the icon's own pixels and draw only the picture above it. This works out
        // of the shape of the picture, not out of a package name, so an app installed later gets
        // the same treatment.
        val from = bottomStrip(icon, iw, ih)
        val back = layer {
            // The icon's own edge color behind it, so its rounded corners melt into the tile.
            drawColor(bandColor(px, n, box, top = true) ?: GRAPHITE_BOTTOM)
            if (from == null) {
                drawCover(icon, W, H, iw, ih)
            } else {
                val full = rasterBitmap(icon, iw, ih)
                val crop = createBitmap(iw, ih - from)
                Canvas(crop).drawBitmap(full, 0f, -from.toFloat(), null)
                full.recycle()
                drawCover(BitmapDrawable(crop), W, H, iw, ih - from)
                crop.recycle()
            }
        }
        return finish(back, null)
    }

    /**
     * The first source row of a flat band along the bottom of [icon], or null when its bottom is
     * part of the picture. Measured on the icon's own aspect: the earlier version measured on the
     * n×n logo sample and anchored on the very last row, but firmware icons often finish with a
     * one-pixel lighter border, so it saw a one-row "strip" and gave up. Here the color is taken a
     * few rows up, the border rows get a wider tolerance, and the row above the band must differ
     * from it — so an ordinary picture that happens to end on one colour is left alone.
     */
    private fun bottomStrip(icon: Drawable, iw: Int, ih: Int): Int? {
        val rw = iw.coerceAtMost(512)
        val rh = (ih.toFloat() * rw / iw).roundToInt().coerceAtLeast(1)
        val bmp = rasterBitmap(icon, rw, rh)
        val px = IntArray(rw * rh).also { bmp.getPixels(it, 0, rw, 0, 0, rw, rh) }
        bmp.recycle()
        var l = rw; var t = rh; var r = -1; var b = -1
        for (y in 0 until rh) for (x in 0 until rw) if (solid(px[y * rw + x])) {
            l = minOf(l, x); r = maxOf(r, x); t = minOf(t, y); b = maxOf(b, y)
        }
        if (r < 0) return null
        val w = r - l + 1
        val h = b - t + 1
        if (w < rw * 0.7f || h < rh * 0.7f) return null
        val xs = (l + w / 6) until (r - w / 6)
        if (xs.isEmpty()) return null
        fun rowColor(y: Int): Int {
            val rs = ArrayList<Int>(); val gs = ArrayList<Int>(); val bs = ArrayList<Int>()
            for (x in xs) {
                val c = px[y * rw + x]
                rs += (c shr 16) and 0xFF; gs += (c shr 8) and 0xFF; bs += c and 0xFF
            }
            return android.graphics.Color.rgb(rs.sorted()[rs.size / 2], gs.sorted()[gs.size / 2], bs.sorted()[bs.size / 2])
        }
        fun rowFlat(y: Int, color: Int, tol: Int): Boolean =
            xs.count { distance(px[y * rw + it], color) <= tol } >= xs.count() * 0.85f
        // A plain band cannot be the same colour as the picture's top edge: that is an ordinary
        // flat background, not an extra strip stuck under the artwork.
        val topColor = rowColor(t + maxOf(1, h / 20))
        val probe = maxOf(t, b - maxOf(2, (h * 0.08f).roundToInt()))
        val band = rowColor(probe)
        if (distance(band, topColor) < 60 || !rowFlat(probe, band, 40)) return null
        var top = probe
        while (top > t && rowFlat(top - 1, band, 52)) top--
        // The last row or two may be an antialiased border a shade lighter than the band.
        for (y in maxOf(t, b - minOf(3, h / 20))..b) if (!rowFlat(y, band, 150)) return null
        val strip = b - top + 1
        if (strip < h * 0.08f || strip > h * 0.45f) return null
        if (distance(rowColor(maxOf(t, top - 2)), band) < 48) return null
        return (top * ih.toFloat() / rh).roundToInt().coerceIn(1, ih - 1)
    }

    /** [d] rastered at an exact size, keeping its aspect (unlike [raster]'s square sample). */
    private fun rasterBitmap(d: Drawable, w: Int, h: Int): Bitmap =
        createBitmap(w, h).also { d.setBounds(0, 0, w, h); d.draw(Canvas(it)) }

    /** The median color of a band just inside [box]'s top (or bottom) edge of an n×n sample. */
    private fun bandColor(px: IntArray, n: Int, box: android.graphics.Rect, top: Boolean): Int? {
        val w = box.width()
        val depth = (box.height() * 0.06f).roundToInt().coerceAtLeast(2)
        val y0 = if (top) box.top + depth else box.bottom - 1 - 2 * depth
        val y1 = y0 + depth
        val rs = ArrayList<Int>(); val gs = ArrayList<Int>(); val bs = ArrayList<Int>()
        for (y in y0..y1) for (x in box.left + w / 6..box.right - w / 6) {
            val c = px[y * n + x]
            rs += (c shr 16) and 0xFF; gs += (c shr 8) and 0xFF; bs += c and 0xFF
        }
        if (rs.isEmpty()) return null
        return android.graphics.Color.rgb(rs.sorted()[rs.size / 2], gs.sorted()[gs.size / 2], bs.sorted()[bs.size / 2])
    }

    /**
     * A dark mark on a transparent field (TVBox's grey cube) used to inherit a black plate from
     * its own pixels. In the original look those icons get the soft pale-yellow plate instead.
     * A coloured mark, an opaque icon and a full-square picture keep their own colors; dopamine
     * never asks, so its random bright plate stays exactly as it is.
     */
    private fun needsPaleYellow(px: IntArray): Boolean {
        var opaque = 0
        var lightness = 0L
        var dark = 0
        for (c in px) {
            if ((c ushr 24) <= 200) continue
            opaque++
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val y = (r * 299 + g * 587 + b * 114) / 1000
            lightness += y
            if (y < 110) dark++
        }
        if (opaque < px.size * 0.10f || opaque > px.size * 0.88f) return false
        return lightness / opaque < 110 && dark > opaque * 0.55f
    }

    /** The two colors of a dopamine tile for [seed]: one bright hue, a step darker below it. */
    private fun dopaminePair(seed: Int): Pair<Int, Int> {
        // Six high-saturation hues, the ones the name suggests, kept away from the two that read as
        // danger or as a system warning.
        val hues = floatArrayOf(348f, 22f, 45f, 155f, 205f, 268f, 300f)
        val hue = hues[Math.floorMod(seed, hues.size)]
        return ColorUtils.HSLToColor(floatArrayOf(hue, 0.82f, 0.60f)) to
            ColorUtils.HSLToColor(floatArrayOf((hue + 18f) % 360f, 0.78f, 0.44f))
    }

    /**
     * Null when the foreground ignores the adaptive safe zone (a legacy icon wrapped as adaptive),
     * or when the background has shapes of its own (a disc the logo sits in, Google's Clock):
     * the layers only line up drawn together, so those go on as a whole icon.
     */
    @RequiresApi(26)
    private fun adaptive(icon: AdaptiveIconDrawable): TileArt? {
        val logo = visible(raster(icon.foreground, SAMPLE), SAMPLE) ?: return null
        // A logo inside the 66-unit safe zone of the 108-unit layer spans at most about 0.61.
        if (max(logo.width(), logo.height()) > 0.7f) return null
        val bg = icon.background
        val bgPx = bg?.let { raster(it, 32) }
        if (bgPx != null && !isFlat(bgPx)) return null
        val (fore, _) = placeLogo(icon.foreground, ::visible) ?: return null
        val back = layer {
            drawColor(GRAPHITE_BOTTOM)
            bg?.let {
                // The background layer is a square; cover the wide tile with it.
                it.setBounds(0, (H - W) / 2, W, (H - W) / 2 + W)
                it.draw(this)
            }
        }
        return finish(back, fore)
    }

    /**
     * A legacy icon that is a filled rounded square (most China-market apps ship these: a logo on a
     * white or colored plate). Drawn small in the middle it reads as an icon on a tile, not as the
     * tile. Instead the plate's own color is carried across the whole tile, top edge to bottom
     * edge, and the logo on it is sized like every other logo, the plate fading into the tile
     * around it. Null for any other shape.
     */
    private fun plate(icon: Drawable): TileArt? {
        fun no(reason: String): TileArt? { DiagLog.i("art ${icon.intrinsicWidth}x${icon.intrinsicHeight}: plate no ($reason)"); return null }
        val n = SAMPLE
        val px = raster(icon, n)
        val box = solidBox(px, n) ?: return no("no solid box")
        val l = box.left; val t = box.top; val r = box.right - 1; val bt = box.bottom - 1
        val w = box.width()
        val h = box.height()
        // A square at least half the canvas, and nearly all of it solid: a rounded square fills
        // about 96% of its box, a circle 79%.
        var filled = 0
        for (y in t..bt) for (x in l..r) if (solid(px[y * n + x])) filled++
        if (w < n / 2 || kotlin.math.abs(w - h) > w * 0.06f || filled < w * h * 0.9f) return no("shape ${w}x${h} filled=${filled * 100 / (w * h)}%")
        // The plate's color along its top and bottom edges, inside the corner rounding: the
        // median, so a badge in a corner (WPS's "TV") or a logo touching the edge does not tint it.
        fun band(fromY: Int, toY: Int): Int? {
            val xs = (l + w / 6)..(r - w / 6)
            val rs = ArrayList<Int>(); val gs = ArrayList<Int>(); val bs = ArrayList<Int>()
            for (y in fromY..toY) for (x in xs) {
                val c = px[y * n + x]
                rs += (c shr 16) and 0xFF; gs += (c shr 8) and 0xFF; bs += c and 0xFF
            }
            val median = android.graphics.Color.rgb(rs.sorted()[rs.size / 2], gs.sorted()[gs.size / 2], bs.sorted()[bs.size / 2])
            // Most of the edge has to be that color: a picture running into the edge has no plate.
            var near = 0
            for (y in fromY..toY) for (x in xs) if (distance(px[y * n + x], median) <= 48) near++
            return if (near < rs.size * 0.6f) null else median
        }
        val edge = (h * 0.04f).roundToInt()
        val depth = (h * 0.06f).roundToInt()
        val top = band(t + edge, t + edge + depth) ?: return no("top band")
        val bottom = band(bt - edge - depth, bt - edge) ?: return no("bottom band")
        // Two colors (Xiaomi's 视频头条: white over red) are a picture, not a plate to carry across.
        // A smooth vertical gradient (电视管家: light green into a darker green) is a plate like
        // any other — what gives a picture away is its middle, which a gradient blends into.
        if (distance(top, bottom) > 150) {
            val mid = band(t + h / 2 - depth / 2, t + h / 2 + depth / 2) ?: return no("middle band")
            if (distance(mid, ColorUtils.blendARGB(top, bottom, 0.5f)) > 96) return no("picture, not a plate")
        }

        // The logo: the largest connected mark standing out from the plate's color. Picking the
        // whole set of differing pixels used to let a small corner badge (TVBox's OSC tag) pull
        // the box towards the corner, so the real mark sat off-centre and read as a small icon on
        // a plate. Keeping the biggest group, and the pieces of the same mark next to it, leaves a
        // corner badge out without knowing about any particular app.
        fun logoOnPlate(px: IntArray, n: Int): RectF? {
            val plate = solidBox(px, n) ?: return null
            val inset = (plate.width() * 0.06f).roundToInt()
            val region = android.graphics.Rect(plate.left + inset, plate.top + inset, plate.right - inset, plate.bottom - inset)
            val pw = region.width()
            val ph = region.height()
            if (pw <= 2 || ph <= 2) return null
            val mark = BooleanArray(pw * ph)
            for (y in 0 until ph) for (x in 0 until pw) {
                val c = px[(region.top + y) * n + region.left + x]
                val plateAtY = ColorUtils.blendARGB(top, bottom, (region.top + y - plate.top) / plate.height().toFloat())
                mark[y * pw + x] = solid(c) && distance(c, plateAtY) > 72
            }
            val seen = BooleanArray(pw * ph)
            val stack = IntArray(pw * ph)
            var main = android.graphics.Rect()
            var mainSize = 0
            val groups = ArrayList<Pair<Int, android.graphics.Rect>>()
            for (y in 0 until ph) for (x in 0 until pw) {
                val start = y * pw + x
                if (!mark[start] || seen[start]) continue
                var sp = 0
                stack[sp++] = start
                seen[start] = true
                var size = 0
                var l = x; var r = x; var t = y; var b = y
                while (sp > 0) {
                    val at = stack[--sp]
                    val cx = at % pw; val cy = at / pw
                    size++
                    l = minOf(l, cx); r = maxOf(r, cx); t = minOf(t, cy); b = maxOf(b, cy)
                    if (cx > 0 && mark[at - 1] && !seen[at - 1]) { seen[at - 1] = true; stack[sp++] = at - 1 }
                    if (cx < pw - 1 && mark[at + 1] && !seen[at + 1]) { seen[at + 1] = true; stack[sp++] = at + 1 }
                    if (cy > 0 && mark[at - pw] && !seen[at - pw]) { seen[at - pw] = true; stack[sp++] = at - pw }
                    if (cy < ph - 1 && mark[at + pw] && !seen[at + pw]) { seen[at + pw] = true; stack[sp++] = at + pw }
                }
                val box = android.graphics.Rect(region.left + l, region.top + t, region.left + r + 1, region.top + b + 1)
                groups += size to box
                if (size > mainSize) { mainSize = size; main = box }
            }
            if (mainSize == 0) return null
            // Pieces of one mark (letters with a gap, a symbol split by its own colour) stay; a
            // badge in a corner has its own group and its centre falls outside the mark's box.
            val keep = groups.filter { (size, box) ->
                size >= mainSize * 0.15f &&
                    box.centerX() in main.left..main.right && box.centerY() in main.top..main.bottom
            }
            val box = android.graphics.Rect(main)
            for ((_, rect) in keep) box.union(rect)
            return RectF(box.left / n.toFloat(), box.top / n.toFloat(), box.right / n.toFloat(), box.bottom / n.toFloat())
        }

        val (fore, logo) = placeLogo(icon, ::logoOnPlate) ?: return no("no logo")
        val back = layer {
            drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, H.toFloat(), top, bottom, Shader.TileMode.CLAMP)
            })
        }
        feather(fore, logo)
        return finish(back, fore)
    }

    private fun legacy(icon: Drawable): TileArt {
        // A legacy icon with no plate to carry across the tile. It sits, sized like every other
        // logo, on a tile in its own hue, the way tvOS draws its system apps. (It used to sit on
        // a blurred copy of itself, which read as a phone icon dropped on a smudge.) Full-square
        // icons (many China-market system apps expect the launcher to mask them) get rounded corners.
        val sample = 192
        val iconBitmap = createBitmap(sample, sample)
        icon.setBounds(0, 0, sample, sample)
        icon.draw(Canvas(iconBitmap))
        val square = opaqueCorners(iconBitmap)
        val tint = dominant(iconBitmap)
        iconBitmap.recycle()
        val (top, bottom) = tileFromTint(tint)
        // A square icon is a picture with its logo somewhere inside: sized up so the logo reads
        // as large as the others. When its edges are one color each, those colors are carried
        // across the tile and the picture fades into them, so it fills the tile instead of sitting
        // on it (HiFi音乐, 华数TV, 开唛K歌 from 当贝市场).
        edgeColors(icon)?.let { (edgeTop, edgeBottom) ->
            placeLogo(icon, ::visible, scale = 1.25f)?.let { (fore, at) ->
                val side = at.width()
                feather(fore, RectF(at.left + side * 0.18f, at.top + side * 0.18f, at.right - side * 0.18f, at.bottom - side * 0.18f), margin = 0f, fade = side * 0.18f)
                val back = layer {
                    drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
                        shader = LinearGradient(0f, at.top, 0f, at.bottom, edgeTop, edgeBottom, Shader.TileMode.CLAMP)
                    })
                }
                return finish(back, fore)
            }
        }
        val fore = placeLogo(icon, ::visible, round = square, scale = if (square) 1.25f else 1f)?.first
        return finish(colorTile(top, bottom), fore)
    }

    /**
     * For an icon that is a filled (rounded) square, the colors along its top and bottom edges,
     * just inside them and away from the corners. Null for any other shape, when either edge is
     * not mostly one color (a photo running off the edge), or when the two are far apart (black
     * over orange, 华数南瓜电影), which fades into the tile as a stripe. A gradient (pink into
     * purple) is fine.
     */
    private fun edgeColors(icon: Drawable): Pair<Int, Int>? {
        val n = 96
        val px = raster(icon, n)
        val box = solidBox(px, n) ?: return null
        val w = box.width(); val h = box.height()
        if (w < n / 2 || kotlin.math.abs(w - h) > w * 0.06f) return null
        var filled = 0
        for (y in box.top until box.bottom) for (x in box.left until box.right) if (solid(px[y * n + x])) filled++
        if (filled < w * h * 0.88f) return null
        fun band(y0: Int, y1: Int): Int? {
            val cs = ArrayList<Int>()
            for (y in y0..y1) for (x in box.left + w / 5 until box.right - w / 5) cs += px[y * n + x]
            val median = android.graphics.Color.rgb(
                cs.map { (it shr 16) and 0xFF }.sorted()[cs.size / 2],
                cs.map { (it shr 8) and 0xFF }.sorted()[cs.size / 2],
                cs.map { it and 0xFF }.sorted()[cs.size / 2],
            )
            return if (cs.count { distance(it, median) <= 48 } < cs.size * 0.6f) null else median
        }
        // Close to the edge: a picture icon's subject often reaches within a few percent of it.
        val top = band(box.top + h * 2 / 100, box.top + h * 5 / 100) ?: return null
        val bottom = band(box.bottom - 1 - h * 5 / 100, box.bottom - 1 - h * 2 / 100) ?: return null
        return if (distance(top, bottom) > 260) null else top to bottom
    }

    private fun opaqueCorners(b: Bitmap): Boolean {
        val i = (b.width * 0.03f).roundToInt()
        val e = b.width - 1 - i
        return listOf(b.getPixel(i, i), b.getPixel(e, i), b.getPixel(i, e), b.getPixel(e, e))
            .all { android.graphics.Color.alpha(it) > 200 }
    }

    // ---- Logos: found in a sample of the icon, then drawn sharp at their size on the tile ----

    private inline fun layer(draw: Canvas.() -> Unit): Bitmap = createBitmap(W, H).also { Canvas(it).draw() }

    private fun raster(d: Drawable, n: Int): IntArray {
        val b = createBitmap(n, n)
        d.setBounds(0, 0, n, n)
        d.draw(Canvas(b))
        return IntArray(n * n).also { b.getPixels(it, 0, n, 0, 0, n, n); b.recycle() }
    }

    /**
     * The box around the pixels of the n×n sample that [isLogo] picks (given the pixel's index
     * and color), searched within [within], as fractions of the sample. Null when there are none.
     */
    private inline fun bounds(
        px: IntArray, n: Int,
        within: android.graphics.Rect = android.graphics.Rect(0, 0, n, n),
        isLogo: (Int, Int) -> Boolean,
    ): RectF? {
        var l = n; var t = n; var r = -1; var b = -1
        for (y in within.top until within.bottom) for (x in within.left until within.right) {
            val i = y * n + x
            if (isLogo(i, px[i])) { l = minOf(l, x); r = maxOf(r, x); t = minOf(t, y); b = maxOf(b, y) }
        }
        return if (r < 0) null else RectF(l / n.toFloat(), t / n.toFloat(), (r + 1) / n.toFloat(), (b + 1) / n.toFloat())
    }

    private fun distance(a: Int, b: Int): Int =
        kotlin.math.abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) +
            kotlin.math.abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) +
            kotlin.math.abs((a and 0xFF) - (b and 0xFF))

    /** True for a layer of (nearly) one color. */
    private fun isFlat(px: IntArray): Boolean {
        val first = px[px.size / 2]
        return px.count { distance(it, first) > 24 || kotlin.math.abs((it ushr 24) - (first ushr 24)) > 24 } < px.size / 20
    }

    /**
     * Where the whole icon goes on the tile so that its [logo] (fractions of the icon's box) sits
     * centered at logo size. Logos are sized by area rather than by their box, so a wide wordmark
     * and a round symbol carry the same weight, and all of them stay inside the tile's safe zone,
     * as on tvOS: focus scale and parallax never take a logo near the edge.
     */
    private fun iconOnTile(logo: RectF, scale: Float = 1f): RectF {
        val aspect = logo.width() / logo.height()
        val h = minOf(scale * LOGO * H / kotlin.math.sqrt(aspect), 0.66f * H, 0.62f * W / aspect)
        val box = h / logo.height()
        val left = W / 2f - box * logo.centerX()
        val top = H / 2f - box * logo.centerY()
        return RectF(left, top, left + box, top + box)
    }

    /** The opaque part of a sample. */
    private fun visible(px: IntArray, n: Int): RectF? = bounds(px, n) { _, c -> c ushr 24 > 24 }

    private fun solid(c: Int) = c ushr 24 > 200

    /** The box around the solid pixels of an n×n sample, in its pixels. */
    private fun solidBox(px: IntArray, n: Int): android.graphics.Rect? {
        var l = n; var t = n; var r = -1; var b = -1
        for (y in 0 until n) for (x in 0 until n) if (solid(px[y * n + x])) {
            l = minOf(l, x); r = maxOf(r, x); t = minOf(t, y); b = maxOf(b, y)
        }
        return if (r < 0) null else android.graphics.Rect(l, t, r + 1, b + 1)
    }

    /**
     * A tile-sized layer with [d] drawn so that the logo [find] picks out of it sits at
     * [iconOnTile], and where that logo ended up on the tile. Null when [find] finds none.
     *
     * The icon is drawn at the size it will have, so it stays sharp, then measured again: a
     * layer-list of dp-sized shapes does not scale with its bounds (遥遥电视助手 on Xiaomi
     * MiTV-MFTR0), and is placed by what was actually drawn.
     */
    private fun placeLogo(d: Drawable, find: (IntArray, Int) -> RectF?, round: Boolean = false, scale: Float = 1f): Pair<Bitmap, RectF>? {
        val first = find(raster(d, SAMPLE), SAMPLE) ?: return null
        val size = iconOnTile(first, scale).width().roundToInt().coerceIn(16, 4 * H)
        val icon = createBitmap(size, size)
        d.setBounds(0, 0, size, size)
        d.draw(Canvas(icon))
        val px = IntArray(size * size).also { icon.getPixels(it, 0, size, 0, 0, size, size) }
        val logo = find(px, size) ?: first
        val at = iconOnTile(logo, scale)
        val out = layer {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            if (round) {
                drawPath(smoothRect(at, at.width() * 0.225f), paint.apply {
                    shader = android.graphics.BitmapShader(icon, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                        setLocalMatrix(android.graphics.Matrix().apply {
                            setScale(at.width() / size, at.height() / size)
                            postTranslate(at.left, at.top)
                        })
                    }
                })
            } else {
                drawBitmap(icon, null, at, paint)
            }
        }
        icon.recycle()
        val box = at.width()
        return out to RectF(at.left + box * logo.left, at.top + box * logo.top, at.left + box * logo.right, at.top + box * logo.bottom)
    }

    /** Fades [layer] out around [keep], so a plate's own edges and corners melt into the tile color. */
    private fun feather(layer: Bitmap, keep: RectF, margin: Float = H * 0.04f, fade: Float = H * 0.22f) {
        val c = Canvas(layer)
        fun mask(from: Float, to: Float, horizontal: Boolean) = Paint().apply {
            val span = to - from + 2 * (margin + fade)
            val f = fade / span
            val start = from - margin - fade
            shader = LinearGradient(
                if (horizontal) start else 0f, if (horizontal) 0f else start,
                if (horizontal) start + span else 0f, if (horizontal) 0f else start + span,
                intArrayOf(0, android.graphics.Color.BLACK, android.graphics.Color.BLACK, 0),
                floatArrayOf(0f, f, 1f - f, 1f), Shader.TileMode.CLAMP,
            )
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
        }
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), mask(keep.left, keep.right, horizontal = true))
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), mask(keep.top, keep.bottom, horizontal = false))
    }

    /** [back] and [fore] are both tile-sized; the foreground is trimmed here. */
    private fun finish(back: Bitmap, fore: Bitmap?): TileArt {
        val composed = if (fore == null) back else back.copy(Bitmap.Config.ARGB_8888, true).also {
            Canvas(it).drawBitmap(fore, 0f, 0f, null)
        }
        val tint = dominant(composed)
        val backdrop = backdropOf(composed)
        if (composed !== back) composed.recycle()
        var foreAt = androidx.compose.ui.geometry.Rect.Zero
        val trimmed = fore?.let { layer ->
            val px = IntArray(W * H).also { layer.getPixels(it, 0, W, 0, 0, W, H) }
            var l = W; var t = H; var r = -1; var b = -1
            for (y in 0 until H) for (x in 0 until W) if (px[y * W + x] ushr 24 != 0) {
                l = minOf(l, x); r = maxOf(r, x); t = minOf(t, y); b = maxOf(b, y)
            }
            if (r < 0) return@let null
            foreAt = androidx.compose.ui.geometry.Rect(l / W.toFloat(), t / H.toFloat(), (r + 1) / W.toFloat(), (b + 1) / H.toFloat())
            Bitmap.createBitmap(layer, l, t, r - l + 1, b - t + 1).also { if (it !== layer) layer.recycle() }
        }
        return TileArt(
            back = back.asImageBitmap(),
            fore = trimmed?.asImageBitmap(),
            foreAt = foreAt,
            tint = Color(tint),
            backdrop = backdrop.asImageBitmap(),
        )
    }

    /** The tile as one picture, as Home draws it at rest: for the phone page. */
    fun flatten(art: TileArt): Bitmap {
        val out = art.back.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, true)
        art.fore?.let { f ->
            val r = art.foreAt
            Canvas(out).drawBitmap(
                f.asAndroidBitmap(), null,
                RectF(r.left * out.width, r.top * out.height, r.right * out.width, r.bottom * out.height),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
        }
        return out
    }

    /** [iw]×[ih] is the size [d] is scaled from: its pixels for a bitmap, whatever density it was loaded at. */
    private fun Canvas.drawCover(
        d: Drawable, w: Int, h: Int,
        iw: Int = d.intrinsicWidth.takeIf { it > 0 } ?: w,
        ih: Int = d.intrinsicHeight.takeIf { it > 0 } ?: h,
    ) {
        val scale = max(w / iw.toFloat(), h / ih.toFloat())
        val dw = (iw * scale).roundToInt()
        val dh = (ih * scale).roundToInt()
        val l = (w - dw) / 2
        val t = (h - dh) / 2
        d.setBounds(l, t, l + dw, t + dh)
        d.draw(this)
    }

    /** Saturation-weighted mean color: the hue a person would name when describing the tile. */
    private fun dominant(b: Bitmap): Int {
        val small = b.scale(24, 14)
        var r = 0.0; var g = 0.0; var bl = 0.0; var total = 0.0
        val hsv = FloatArray(3)
        for (y in 0 until small.height) for (x in 0 until small.width) {
            val c = small.getPixel(x, y)
            if (android.graphics.Color.alpha(c) < 128) continue
            android.graphics.Color.colorToHSV(c, hsv)
            val wgt = hsv[1] * hsv[2] * hsv[2] + 0.04
            r += android.graphics.Color.red(c) * wgt
            g += android.graphics.Color.green(c) * wgt
            bl += android.graphics.Color.blue(c) * wgt
            total += wgt
        }
        if (small !== b) small.recycle()
        if (total == 0.0) return GRAPHITE_TOP
        return android.graphics.Color.rgb((r / total).roundToInt(), (g / total).roundToInt(), (bl / total).roundToInt())
    }

    /**
     * Tile colors, top and bottom, for a logo with no background of its own: its hue, a little
     * deeper than the logo so the logo still stands out. Grey logos get graphite.
     */
    private fun tileFromTint(tint: Int): Pair<Int, Int> {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(tint, hsl)
        if (hsl[1] < 0.18f) return 0xFF6E6E74.toInt() to 0xFF3A3A3C.toInt()
        val s = hsl[1].coerceIn(0.5f, 0.85f)
        return ColorUtils.HSLToColor(floatArrayOf(hsl[0], s, 0.56f)) to ColorUtils.HSLToColor(floatArrayOf(hsl[0], s, 0.4f))
    }

    private fun backdropOf(src: Bitmap): Bitmap {
        val w = 64
        val h = 36
        val small = src.scale(w, h)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        repeat(3) { Images.boxBlur(px, w, h, 4) }
        val blurred = createBitmap(w, h)
        blurred.setPixels(px, 0, w, 0, 0, w, h)
        // Lift saturation and pull brightness down: ambient light, not a picture. Pale artwork
        // (white banners) would spread a grey haze, so it gets dimmed much further.
        val hsv = FloatArray(3)
        var sat = 0f; var value = 0f
        for (c in px) { android.graphics.Color.colorToHSV(c, hsv); sat += hsv[1]; value += hsv[2] }
        sat /= px.size; value /= px.size
        val gain = if (sat < 0.2f) 0.82f - 0.5f * value * (1f - sat / 0.2f) else 0.82f
        val cm = ColorMatrix().apply {
            setSaturation(1.5f)
            postConcat(ColorMatrix().apply { setScale(gain, gain, gain, 1f) })
        }
        val bw = 192
        val bh = 108
        val out = createBitmap(bw, bh)
        Canvas(out).apply {
            drawBitmap(
                blurred, null, android.graphics.Rect(0, 0, bw, bh),
                Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(cm) },
            )
            // Light kept up top where the shelf is, near-black under the grid.
            drawRect(0f, 0f, bw.toFloat(), bh.toFloat(), Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 0f, bh.toFloat(),
                    intArrayOf(black(0.10f), black(0.32f), black(0.80f), black(0.94f)),
                    floatArrayOf(0f, 0.42f, 0.72f, 1f), Shader.TileMode.CLAMP,
                )
            })
            // The left edge shaded so the clock reads over bright artwork.
            drawRect(0f, 0f, bw.toFloat(), bh.toFloat(), Paint().apply {
                shader = LinearGradient(0f, 0f, bw * 0.55f, 0f, black(0.38f), 0, Shader.TileMode.CLAMP)
            })
        }
        small.recycle(); blurred.recycle()
        return out
    }

    private fun black(alpha: Float) = android.graphics.Color.argb((alpha * 255).roundToInt(), 0, 0, 0)
}

/** [r] with continuous corners, as tiles have them. */
private fun smoothRect(r: RectF, radius: Float): android.graphics.Path =
    smoothRectPath(Rect(r.left, r.top, r.right, r.bottom), radius).asAndroidPath()
