package app.orchard.tv.ui.components

import android.graphics.BitmapShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.wallpaper.Wallpapers
import kotlin.math.roundToInt

/**
 * Liquid Glass, the material of tvOS 26's panels, without a live blur: the TVs here mostly run
 * Android 9–11 (no RenderEffect) and their GPUs could not afford one. The surface shows the part
 * of the frosted wallpaper (Wallpapers.frosted, baked small) that lies behind it, dimmed so white
 * text reads on any picture, and light catches its rim. The frosted picture is stretched over the
 * screen through a shader, so a panel costs one fill of its own area, as a flat color did. In
 * "Follow App" mode there is no frosted wallpaper and the panel is the plain dark glass color.
 *
 * Replaces clip + background + border; [bottomCorners] false for a sheet rising from the bottom.
 * The continuous outline is a baked mask (see SmoothCorners.kt); children are clipped to a plain
 * rounded rect, which holds it. [seeThroughFilm]: nothing but the film lies under the panel, which
 * over a film without frames (Wallpapers.filmGlassless) is then see-through like the plate.
 * [litePicture]: Lite draws the frosted picture itself where the panel lies, not its average
 * colour — see the branch below.
 */
fun Modifier.liquidGlass(
    corner: Dp,
    bottomCorners: Boolean = true,
    seeThroughFilm: Boolean = false,
    litePicture: Boolean = false,
    /** A lift toward white, strongest at the top, over the glass: the plate reads lighter without
     *  losing the picture under it, so a panel keeps its frosted-glass look over a dark wallpaper
     *  (the owner: 「设置菜单和个性化菜单的颜色似乎变深了，以前是透明模糊玻璃效果」). */
    milk: Float = 0f,
    /** A brighter dim of the picture than [PanelTint], for panels that must read as glass over
     *  dark wallpapers too: ×0.48 left the control centre a brown slab on one (owner, again). */
    tint: ColorFilter? = null,
    /** The pale, white frosted material used by Control Center. Its solid fallbacks are light too,
     *  so a film without frames or a wallpaper not yet loaded cannot flash a dark slab back in. */
    light: Boolean = false,
): Modifier = composed {
    // The glass a panel opened with stays while it is open, unless the wallpaper itself changes
    // (chosen in Personalize). A moving film that stops as a panel opens gets its glass made again
    // twice within the next second or so, from a frame near where it stopped and then from that
    // very frame (LiveWallpaper): followed, the menu's and Control Center's glass changed twice
    // just after they opened (the owner, Xiaomi MiTV-MFTR0). The next opening takes the newest.
    // Over a moving wallpaper whose frames cannot be had (Wallpapers.filmGlassless) one solid
    // colour: the frosted frame there is one from minutes before, and Control Center and
    // Personalize showed its colours over the paused film, another shot (TCL MT5879, 2026-10-30).
    // See-through instead would show the tiles under the panel; Control Center over the top row,
    // with only the film under it, is see-through as the plate is: solid, it was a grey slab beside
    // a plate the film shows through (seen on Xiaomi MiTV-MFFU1 while a slow copy kept it from
    // the film's frames, 2026-11-01; TCL MT5879 is such a TV for good).
    // Now the picture's glass, as over a still picture, once the picture is there: the frame
    // decoded in the app where the film was let go, which the film goes on from (owner, TCL
    // MT5879, issue #1238). Solid only until a picture exists.
    val solid = Wallpapers.moves() && Wallpapers.filmGlassless && Wallpapers.frosted == null
    val panelTint = tint ?: PanelTint
    // Where the film's frames cannot be had otherwise, the latest glass of the moving film, decoded
    // in the app (Wallpapers.filmFrost), over the picture's, which may be another shot.
    val latest = (if (Wallpapers.moves() && Wallpapers.filmGlassless) Wallpapers.filmFrost ?: Wallpapers.frosted else Wallpapers.frosted)
        .takeIf { Wallpapers.backdrop != null }
    val held = remember(Wallpapers.selectedId, latest == null) { latest }
    val (frost, under, swap) = rememberFrostSwap(held)
    var at by remember { mutableStateOf(Offset.Zero) }
    var root by remember { mutableStateOf(IntSize.Zero) }
    val shape = if (bottomCorners) RoundedCornerShape(corner) else RoundedCornerShape(topStart = corner, topEnd = corner)
    this
        .onGloballyPositioned { c ->
            at = c.positionInRoot()
            var r = c
            while (true) r = r.parentLayoutCoordinates ?: break
            root = r.size
        }
        .clip(shape)
        .drawWithCache {
            val shader = frost?.let { BitmapShader(it.asAndroidBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
            val underShader = under?.let { BitmapShader(it.asAndroidBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
            val radius = corner.toPx()
            val w = size.width.roundToInt().coerceAtLeast(1)
            val h = size.height.roundToInt().coerceAtLeast(1)
            val mask = smoothMask(w, h, radius, if (bottomCorners) radius else 0f)
            val brush = shader?.let { ShaderBrush(it) }
            val flat = mask.asImageBitmap()
            // The glass in a layer the GPU keeps: drawn again only when it moves or its picture
            // fades, not with every frame of what lies on it. Drawn each frame, the Personalize
            // sheet cost MiTV-MFTR0's GPU about 29 ms a frame while focus moved along its cards.
            val glass = obtainGraphicsLayer().apply { compositingStrategy = CompositingStrategy.Offscreen }
            var drawnAt: Offset? = null
            var drawnRoot = IntSize.Zero
            var drawnT = -1f
            onDrawBehind {
                if (solid) {
                    val solidGlass = if (light) {
                        if (seeThroughFilm) FilmGlassLight else FilmPanelLight
                    } else if (seeThroughFilm) FilmGlass else FilmPanel
                    drawImage(flat, dstSize = IntSize(w, h), colorFilter = ColorFilter.tint(solidGlass))
                    if (seeThroughFilm) drawImage(filmSheen, dstSize = IntSize(w, h), filterQuality = FilterQuality.Low)
                    drawGlassEdge(size, radius, bottomCorners)
                } else if (shader == null || brush == null || root == IntSize.Zero) {
                    drawImage(flat, dstSize = IntSize(w, h), colorFilter = ColorFilter.tint(if (light) GlassLight else Palette.Glass))
                    drawGlassEdge(size, radius, bottomCorners)
                } else if (AerialSettings.lite) {
                    // Lite (AerialSettings.lite): one solid color, the frosted wallpaper's average as
                    // the glass would dim it, without the layer the glass needs every frame, which
                    // weak GPUs feel most while a panel slides in. Solid: the plain glass color is
                    // see-through and showed the tiles under the Personalize sheet.
                    drawImage(flat, dstSize = IntSize(w, h), colorFilter = ColorFilter.tint(if (light) GlassLight else liteGlass(frost)))
                    // Its average has nothing left of the picture in it: a plate of one colour reads
                    // as a slab of glass, not as glass (the owner: "模糊太狠了，看不出毛玻璃"). The
                    // frosted picture where the panel lies has the wallpaper's shapes in it, and one
                    // textured rect costs what the flat fill did — no layer, so Lite keeps its point.
                    // Opened up from PanelTint, so more of the picture shows through.
                    if (litePicture && root != IntSize.Zero) {
                        drawFrostWithin(frost, at, root, size, LitePictureTint)
                    }
                    drawGlassEdge(size, radius, bottomCorners)
                } else {
                    val t = swap()
                    if (drawnAt != at || drawnRoot != root || drawnT != t) {
                        drawnAt = at
                        drawnRoot = root
                        drawnT = t
                        fun place(s: BitmapShader, image: ImageBitmap) = s.setLocalMatrix(Matrix().apply {
                            setScale(root.width.toFloat() / image.width, root.height.toFloat() / image.height)
                            postTranslate(-at.x, -at.y)
                        })
                        place(shader, frost)
                        // The outline is drawn first in the layer and the glass kept where it lies.
                        // One shader per draw: a ComposeShader of the glass and the mask (1.27-1.30)
                        // came out an opaque dark slab on Xiaomi MiTV-ASTP0 (Android 9, Mali-G31),
                        // and before Android 9 hardware drawing cannot compose two bitmaps.
                        glass.record(this, layoutDirection, IntSize(w, h)) {
                            drawImage(flat, dstSize = IntSize(w, h))
                            if (underShader != null && under != null && t < 1f) {
                                // A new frosted picture fades in over the old within the outline.
                                place(underShader, under)
                                drawRect(ShaderBrush(underShader), colorFilter = panelTint, blendMode = BlendMode.SrcIn)
                                drawRect(brush, alpha = t, colorFilter = panelTint, blendMode = BlendMode.SrcAtop)
                            } else {
                                drawRect(brush, colorFilter = panelTint, blendMode = BlendMode.SrcIn)
                            }
                            drawGlassEdge(size, radius, bottomCorners)
                        }
                    }
                    drawLayer(glass)
                }
                if (milk > 0f) {
                    drawRect(
                        Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = milk), Color.White.copy(alpha = milk * 0.35f)),
                        ),
                    )
                }
            }
        }
}

/**
 * The frosted wallpaper as glass and backdrops show it: a new one fades in over the last
 * ([FROST_SWAP_MS]) rather than cutting to it. A moving film that stops gives Home its frosted
 * picture in two or three steps (a small frame, then the exact one, LiveWallpaper), and with a
 * panel up each cut was a jump in its glass (Xiaomi MiTV-MFTR0, TCL MT5879). Returns the picture,
 * the one under it while it fades in, and how far it has (0 to 1).
 */
@Composable
internal fun rememberFrostSwap(frost: ImageBitmap?): Triple<ImageBitmap?, ImageBitmap?, () -> Float> {
    var shown by remember { mutableStateOf(frost) }
    var under by remember { mutableStateOf<ImageBitmap?>(null) }
    val swap = remember { Animatable(1f) }
    LaunchedEffect(frost) {
        if (frost === shown) return@LaunchedEffect
        val old = shown
        shown = frost
        // From nothing, or to nothing: it simply takes over.
        if (old == null || frost == null) {
            under = null
            swap.snapTo(1f)
            return@LaunchedEffect
        }
        // Mid-fade, the one further in stays under the next.
        under = if (swap.value > 0.5f) old else under ?: old
        swap.snapTo(0f)
        swap.animateTo(1f, tween(FROST_SWAP_MS, easing = Motion.EaseOut))
        under = null
    }
    return Triple(shown, under, { swap.value })
}

private const val FROST_SWAP_MS = 450

/**
 * The lit edge of a piece of glass: a sheen inside the top, and a rim bright where light falls on
 * it from the top left, faint along the sides, and catching again at the far corner.
 */
private fun DrawScope.drawGlassEdge(size: Size, radius: Float, bottomCorners: Boolean) {
    val w = size.width.roundToInt()
    val h = size.height.roundToInt()
    if (w <= 0 || h <= 0) return
    drawImage(glassEdge(w, h, radius, 1.2.dp.toPx(), bottomCorners), dstSize = IntSize(w, h))
}

/** The lit edge of a [w]×[h] piece of glass at full strength, white with alpha; made once per size. */
fun glassEdge(w: Int, h: Int, radius: Float, rim: Float, bottomCorners: Boolean = true): ImageBitmap =
    baked(listOf("glass-edge", w, h, radius, rim, bottomCorners)) {
        val bitmap = createBitmap(w, h)
        val canvas = android.graphics.Canvas(bitmap)
        // A sheet's rim runs off the bottom with its corners.
        val bottom = if (bottomCorners) h.toFloat() else h + radius
        fun white(alpha: Float) = Color.White.copy(alpha = alpha).toArgb()
        // The sheen fades out by the middle.
        canvas.drawPath(
            smoothRectPath(Rect(0f, 0f, w.toFloat(), bottom), radius).asAndroidPath(),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, 0f, 0f, bottom, intArrayOf(white(0.10f), 0), floatArrayOf(0f, 0.5f), Shader.TileMode.CLAMP)
            },
        )
        canvas.drawPath(
            smoothRectPath(Rect(rim / 2, rim / 2, w - rim / 2, bottom - rim / 2), radius - rim / 2).asAndroidPath(),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = rim
                shader = LinearGradient(
                    0f, 0f, w.toFloat(), bottom,
                    intArrayOf(white(0.55f), white(0.10f), white(0.06f), white(0.30f)),
                    floatArrayOf(0f, 0.35f, 0.7f, 1f),
                    Shader.TileMode.CLAMP,
                )
            },
        )
        bitmap.frozen().asImageBitmap()
    }

/** Panels: the frosted picture at half its light, lifted a little, so white text reads on snow. */
/** Lite's panel color for [frost]: its average color, dimmed as [PanelTint] dims the glass. Made once per picture. */
private fun liteGlass(frost: ImageBitmap): Color = synchronized(liteColors) {
    liteColors.getOrPut(frost) {
        val small = android.graphics.Bitmap.createScaledBitmap(frost.asAndroidBitmap(), 16, 9, true)
        var r = 0f; var g = 0f; var b = 0f
        for (y in 0 until 9) for (x in 0 until 16) {
            val c = small.getPixel(x, y)
            r += android.graphics.Color.red(c); g += android.graphics.Color.green(c); b += android.graphics.Color.blue(c)
        }
        fun dim(sum: Float) = ((sum / 144f) * 0.48f + 14f).coerceIn(0f, 255f) / 255f
        Color(dim(r), dim(g), dim(b))
    }
}

private val liteColors = java.util.WeakHashMap<ImageBitmap, Color>()

/**
 * The part of the frosted picture that lies behind a panel, drawn in the panel's own box: a plate
 * of the wallpaper's own shapes rather than one colour, which is what makes it read as glass.
 * [at] and [root] place the panel in the screen the picture covers; [tint] dims it (see [PanelTint]).
 */
private fun DrawScope.drawFrostWithin(frost: ImageBitmap, at: Offset, root: IntSize, size: Size, tint: ColorFilter) {
    val sx = frost.width.toFloat() / root.width
    val sy = frost.height.toFloat() / root.height
    val x0 = (at.x * sx).roundToInt().coerceIn(0, frost.width - 1)
    val y0 = (at.y * sy).roundToInt().coerceIn(0, frost.height - 1)
    val x1 = ((at.x + size.width) * sx).roundToInt().coerceIn(x0 + 1, frost.width)
    val y1 = ((at.y + size.height) * sy).roundToInt().coerceIn(y0 + 1, frost.height)
    drawImage(
        image = frost,
        srcOffset = IntOffset(x0, y0),
        srcSize = IntSize(x1 - x0, y1 - y0),
        dstSize = IntSize(size.width.roundToInt().coerceAtLeast(1), size.height.roundToInt().coerceAtLeast(1)),
        colorFilter = tint,
        // The picture is 320 px wide and stretched over the screen: smooth is what a blur looks like.
        filterQuality = FilterQuality.Low,
    )
}

/**
 * A plate of glass drawn straight into a Canvas, where [rect] lies on the screen ([root]): the
 * frosted picture there, [tint]ed, inside [corner], lifted [milk] of the way to white — and, where
 * [edge] is given, finished with the lit rim of a pane ([glassEdge]). Home's dock bar and the
 * full-screen clock's digits are glass this way: they are not composables of their own size, and
 * [liquidGlass]'s shader-and-layer machinery would be a layer each for a shape that moves.
 */
fun DrawScope.drawFrostRect(
    frost: ImageBitmap,
    rect: Rect,
    root: IntSize,
    corner: Float,
    tint: ColorFilter = PanelTint,
    milk: Float = 0f,
    edge: Float = 0f,
) {
    if (root.width <= 0 || root.height <= 0 || rect.width < 1f || rect.height < 1f) return
    val sx = frost.width.toFloat() / root.width
    val sy = frost.height.toFloat() / root.height
    val x0 = (rect.left * sx).roundToInt().coerceIn(0, frost.width - 1)
    val y0 = (rect.top * sy).roundToInt().coerceIn(0, frost.height - 1)
    val x1 = (rect.right * sx).roundToInt().coerceIn(x0 + 1, frost.width)
    val y1 = (rect.bottom * sy).roundToInt().coerceIn(y0 + 1, frost.height)
    val w = rect.width.roundToInt().coerceAtLeast(1)
    val h = rect.height.roundToInt().coerceAtLeast(1)
    val at = IntOffset(rect.left.roundToInt(), rect.top.roundToInt())
    clipPath(Path().apply { addRoundRect(RoundRect(rect, corner, corner)) }) {
        drawImage(
            image = frost,
            srcOffset = IntOffset(x0, y0),
            srcSize = IntSize(x1 - x0, y1 - y0),
            dstOffset = at,
            dstSize = IntSize(w, h),
            colorFilter = tint,
            // Small picture stretched over the screen: smooth is what a blur looks like.
            filterQuality = FilterQuality.Low,
        )
        // The milky body over the picture's own light and shade, lighter at the top as a pane
        // catching the room's light.
        if (milk > 0f) {
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = milk), Color.White.copy(alpha = milk * 0.62f)),
                    startY = rect.top,
                    endY = rect.bottom,
                ),
                topLeft = rect.topLeft,
                size = rect.size,
            )
        }
    }
    if (edge > 0f) {
        drawImage(glassEdge(w, h, corner, edge), dstOffset = at, dstSize = IntSize(w, h))
    }
}

/**
 * The dock's glass: the picture at a little over half its light and lifted, so a photo reads
 * through it as milk rather than as a grey slab, and the icons sit on something lighter than the
 * wallpaper behind them.
 */
internal val DockGlassTint = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
    0.58f, 0f, 0f, 0f, 20f,
    0f, 0.58f, 0f, 0f, 20f,
    0f, 0f, 0.58f, 0f, 20f,
    0f, 0f, 0f, 1f, 0f,
)))

/**
 * [PanelTint] opened up for the plate that shows the picture itself: 0.62 of its light and 10 of
 * lift, against 0.48 and 14. The average colour is flat, and a flat plate can be as dark as it
 * likes and still read as a slab; the picture's own light and shade carry the glass, so it can be
 * given more of the wallpaper (the owner: the plate's frosted glass, shown too little of).
 */
private val LitePictureTint = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
    0.62f, 0f, 0f, 0f, 10f,
    0f, 0.62f, 0f, 0f, 10f,
    0f, 0f, 0.62f, 0f, 10f,
    0f, 0f, 0f, 1f, 0f,
)))

/**
 * A panel over a moving wallpaper whose frames cannot be had: opaque, so the tiles under it do not
 * show, and as light as the frosted glass is elsewhere (PanelTint over a daytime picture comes to
 * about this grey). The old near-black (0x26262A) read as a dark slab, not glass (the owner, TCL
 * MT5879, 2026-11-01).
 */
private val FilmPanel = Color(0xFF4A4B51)

/**
 * The same see-through, over the film and Control Center's dimming (28% black): a smoke dense
 * enough that what a blur would soften is quieted instead. The film shows at about a fifth of its
 * contrast (19% of its light, lifted by 46), so its colours and movement still come through but
 * not its edges. At 33% black it came out at 48% of its light: the trees and cliffs behind it were
 * as sharp as outside it, under the white text, a frame drawn over the film more than glass (the
 * owner, TCL MT5879, 2026-11-03).
 */
private val FilmGlass = Color(red = 62, green = 63, blue = 69, alpha = 189)

/**
 * Light from above over [FilmGlass]: white, 10% at the top edge, gone by 45% of the way down, so
 * the smoke reads as a pane catching the room's light. Baked as one column and stretched, as no
 * gradient is drawn per frame here.
 */
private val filmSheen: ImageBitmap by lazy {
    val h = 96
    val bitmap = android.graphics.Bitmap.createBitmap(1, h, android.graphics.Bitmap.Config.ARGB_8888)
    for (y in 0 until h) {
        val t = (1f - (y + 0.5f) / h / 0.45f).coerceIn(0f, 1f)
        bitmap.setPixel(0, y, android.graphics.Color.argb((t * t * 0.10f * 255).roundToInt(), 255, 255, 255))
    }
    bitmap.asImageBitmap()
}

private val PanelTint = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
    0.48f, 0f, 0f, 0f, 14f,
    0f, 0.48f, 0f, 0f, 14f,
    0f, 0f, 0.48f, 0f, 14f,
    0f, 0f, 0f, 1f, 0f,
)))

/**
 * The panels' own dim of the picture, lighter than [PanelTint] so a panel reads as frosted glass
 * over a dark wallpaper too: ×0.48 left Control Center a flat brown slab on the owner's dark
 * wallpaper (「透明模糊玻璃」 is what they remember, 2026-11-16).
 */
internal val PanelBright = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
    0.72f, 0f, 0f, 0f, 36f,
    0f, 0.72f, 0f, 0f, 36f,
    0f, 0f, 0.72f, 0f, 36f,
    0f, 0f, 0f, 1f, 0f,
)))

/**
 * Control Center's white frosted glass. The baked, blurred wallpaper still carries the texture
 * (0.30 of its light), but it is lifted into the near-white range instead of the brown slab the
 * old tint gave a dark wallpaper (「希望是白色磨砂玻璃半透明」, 2026-11-16). The [milk] gradient on
 * top takes it the rest of the way to white without hiding the picture behind it.
 */
internal val PanelFrostWhite = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
    0.30f, 0f, 0f, 0f, 185f,
    0f, 0.30f, 0f, 0f, 185f,
    0f, 0f, 0.30f, 0f, 185f,
    0f, 0f, 0f, 1f, 0f,
)))

/** Pale stand-ins for the dark solid fallbacks, so the light panel never flashes a grey slab. */
private val FilmPanelLight = Color(0xEDF2F3F5)
private val FilmGlassLight = Color(red = 245, green = 246, blue = 248, alpha = 205)
private val GlassLight = Color(0xE6F2F3F5)
