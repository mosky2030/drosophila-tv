package app.orchard.tv.ui.home

import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusProperties
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.createBitmap
import app.orchard.tv.apps.AppEntry
import app.orchard.tv.apps.TileArt
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.settings.IconStyle
import app.orchard.tv.system.Sounds
import app.orchard.tv.ui.components.isSelect
import app.orchard.tv.ui.components.baked
import app.orchard.tv.ui.components.frozen
import app.orchard.tv.ui.components.smoothMask
import app.orchard.tv.ui.components.smoothRectPath
import app.orchard.tv.system.DiagLog
import app.orchard.tv.ui.theme.LocalReducedMotion
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Type
import app.orchard.tv.ui.components.DiagLayers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt

// D-pad remotes give no tilt to follow, so the lift itself has to read from across the room.
internal const val FOCUSED_SCALE = 1.15f
private const val PRESSED_SCALE = 1.05f

/**
 * How much a dock's focused icon grows: more than a tile's, because on a bar of icons the size
 * step is the whole point and a 15% lift barely reads beside its neighbours.
 */
internal const val DOCK_FOCUS_SCALE = 1.60f

/**
 * How much the wheel's middle icon grows: less than a dock's, because its neighbours are only one
 * slot away and it must clear them, but plainly taller, which is what makes it read as the middle.
 */
internal const val WHEEL_FOCUS_SCALE = 1.30f

/** Whether Home is wearing the wheel: square icons on a short bar, one app at a time in the middle. */
internal fun onWheel(): Boolean = AerialSettings.iconStyle == IconStyle.Wheel

/**
 * A tile's rest bounds grown about their middle to its size when focused: where the launch
 * animation starts, since the focused tile is what the viewer sees.
 */
fun Rect.asFocusedTile(): Rect {
    val dx = width * (FOCUSED_SCALE - 1f) / 2f
    val dy = height * (FOCUSED_SCALE - 1f) / 2f
    return Rect(left - dx, top - dy, right + dx, bottom + dy)
}

/** Half a sine wave, so a back-and-forth eases through each turn. */
private val SineEasing = Easing { t -> (1f - cos(PI * t).toFloat()) / 2f }
private val Corner = 12.dp
private const val RIM_AT_REST = 0.07f

// High-contrast focus frame: a white ring this far outside the tile, as tvOS draws it.
private val RingGap = 5.dp
private val ShadowBlur = 14.dp
private val ShadowDrop = 12.dp
private val RingWidth = 3.dp

/**
 * How far the focus frame reaches outside the tile when it is fully focused (Original style).
 * Containers that clip their content keep this much clear above a row's tiles, or the ring's
 * top arc is shaved off — the folder's plate, whose first row sits under it, most of all.
 */
internal val FocusFrameOutset = RingGap + RingWidth

/**
 * One app on the home screen, drawn the tvOS way: it lifts toward you when focused, a soft light
 * lies across its top edge, its name appears beneath it, and it leans and springs back when you
 * push against the edge of the grid. Adaptive icons keep their two layers, so the logo shifts
 * against its background when the tile leans.
 */
@Composable
fun AppTile(
    app: AppEntry,
    width: Dp,
    height: Dp,
    focusRequester: FocusRequester,
    enabled: Boolean,
    moving: Boolean,
    /** Extra opacity applied inside each drawing layer, without a layer around the whole tile. */
    opacity: () -> Float = { 1f },
    onFocused: () -> Unit,
    onClick: (Rect?) -> Unit,
    onLongPress: (Rect?) -> Unit,
    modifier: Modifier = Modifier,
    /** Last tile of its row: Right stops here instead of finding the status pill above (#211). */
    rowEnd: Boolean = false,
    /** False on the glass plate: the top row shows no names there, as on tvOS 26. */
    showName: Boolean = true,
    /** The name sits on the glass plate, which ends just under it: it does not step down past the focus frame. */
    nameOnPlate: Boolean = false,
    /**
     * The dock's square icon: the artwork is cut to a square out of its middle (the plate behind
     * it is a colour, the logo in front is kept whole) instead of being stretched into a 16:9 tile.
     */
    square: Boolean = false,
    /** How much this tile grows when it is the focused one (the dock grows its selection more). */
    focusScale: Float = FOCUSED_SCALE,
    /** How much this tile is grown while another is focused: the dock's icons step down with distance. */
    restScale: Float = 1f,
    /** Growth from the bottom edge up, as a dock's icons rise out of its bar instead of off it. */
    growUp: Boolean = false,
    /** The name above the tile, as a dock labels the icon under the pointer. */
    nameAbove: Boolean = false,
    /** A fixed style alpha that replaces the per-style one: inside a folder icons sit at 70%, focus at 80%. */
    forcedAlpha: Float? = null,
    /**
     * A hairline white edge round the icon, on at all times: a folder's icons share one dark plate
     * and their artwork is cut to its corners, where dark artwork on a dark plate lost its shape
     * (the owner: 「群组内图标需要加一个小白边」).
     */
    edge: Boolean = false,
    /**
     * The name fades with its icon. The wheel gives every slot its own alpha by how far it sits
     * from the middle, and a name that stayed at full strength would read as a label for the row
     * rather than for that one icon. Off elsewhere: a label at 70% of the icons' 70% is hard to
     * read over a picture (see the name layer below).
     */
    nameFollowsIcon: Boolean = false,
    /** How far under the tile the name sits: the folder's rows keep it tight (5dp), Home's grid a little looser. */
    nameGap: Dp = 10.dp,
    /**
     * Where the D-pad goes from this tile. Home's wheel turns the row under a focus that stays in
     * the middle, so Left and Right are not simply the neighbours' requesters (see HomeScreen).
     */
    nav: (FocusProperties.() -> Unit)? = null,
    /**
     * Counts the presses of a direction the caller blocked before they reached this tile (Up in a
     * folder opened on the hidden board). Each one bumps the tile up, as a key at the grid's edge
     * does — the press is refused, not silently dropped.
     */
    blockedUp: Int = 0,
) {
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var focused by remember { mutableStateOf(false) }
    // Once per tile: a focused one drawn from its artwork before its face was made (issue #654).
    val rawLogged = remember { booleanArrayOf(false) }
    var pressed by remember { mutableStateOf(false) }
    var bounds by remember { mutableStateOf<Rect?>(null) }
    var longPressJob by remember { mutableStateOf<Job?>(null) }
    var longFired by remember { mutableStateOf(false) }
    var menuPressed by remember { mutableStateOf(false) }
    val click by rememberUpdatedState(onClick)
    val longPress by rememberUpdatedState(onLongPress)

    // Lean (degrees) and push (px) used by the edge nudge.
    val leanY = remember { Animatable(0f) }
    val leanX = remember { Animatable(0f) }
    val push = remember { Animatable(Offset.Zero, Offset.VectorConverter) }

    val scale by animateFloatAsState(
        targetValue = when {
            reduced -> 1f
            pressed -> PRESSED_SCALE
            focused || moving -> focusScale
            else -> restScale
        },
        // Press answers immediately; focus settles on a spring so rapid D-pad presses retarget
        // smoothly. Critically damped, as Android TV's focus zoom (Leanback, Compose for TV) is: an
        // overshoot reads as a wobble on a lit tile and makes the white frame shimmer.
        animationSpec = if (pressed) tween(110, easing = Motion.EaseOut) else spring(dampingRatio = 1f, stiffness = 520f),
        label = "tile-scale",
    )
    val liftState = animateFloatAsState(
        targetValue = if (focused || moving) 1f else 0f,
        animationSpec = spring(dampingRatio = 1f, stiffness = 380f),
        label = "tile-lift",
    )
    val lift by liftState
    // Optional translucent look: unfocused tiles sit back in the wallpaper and the focused one
    // comes forward. Read inside the drawing layers (a State, not a value) so the fade costs no
    // recomposition per frame, and it never adds a layer around the whole tile.
    // Every icon style lifts its focused tile from 70% to 80% (the owner: 「所有图标风格的焦点图标，
    // 图标透明度从70改为80」). Original and Dock keep the rest at 70%, Translucent at 30%; the
    // focused one's white frame and step up in size still do most of the pointing.
    val styleAlphaState = animateFloatAsState(
        targetValue = forcedAlpha ?: when (AerialSettings.iconStyle) {
            IconStyle.Translucent -> if (focused || moving) 0.80f else 0.30f
            // The wheel hands each slot its own alpha (forcedAlpha), by how far it sits from the
            // middle; this is only the fallback for a tile that somehow arrives without one.
            IconStyle.Dock, IconStyle.Original, IconStyle.Wheel -> if (focused || moving) 0.80f else 0.70f
            else -> 1f
        },
        animationSpec = tween(220, easing = Motion.EaseOut),
        label = "tile-style-alpha",
    )
    // The white frame round the focused tile is always on: the focus style it used to belong to is
    // gone (the owner: 「焦点样式功能删除」), and this is what that style drew by default.
    val ring = 1f
    // The frame comes on quickly with the zoom and goes off faster still, so moving along a row
    // never shows two frames at once. It grows out from the tile's edge to its place.
    val ringInState = animateFloatAsState(
        targetValue = if (focused || moving) 1f else 0f,
        animationSpec = if (focused || moving) spring(dampingRatio = 1f, stiffness = 700f) else spring(dampingRatio = 1f, stiffness = 2400f),
        label = "tile-ring-in",
    )
    val ringIn by ringInState
    // Whether the shadow and the frame are there at all changes once per focus move; the values
    // themselves are read only in the layers and draws below. Read in composition, every frame of
    // the springs recomposed the whole tile and threw away its drawing cache.
    val showShadow by remember { derivedStateOf { liftState.value > 0.01f } }
    val showFrame by remember { derivedStateOf { ringInState.value > 0.01f } }

    // tvOS "jiggle" while the app is being moved.
    val wobble = if (moving && !reduced) {
        rememberInfiniteTransition(label = "jiggle").animateFloat(
            initialValue = -1.3f, targetValue = 1.3f,
            // A sine, not a triangle: linear turned back hard at each end and read as a buzz.
            animationSpec = infiniteRepeatable(tween(130, easing = SineEasing), RepeatMode.Reverse),
            label = "jiggle-angle",
        )
    } else null

    /** One small bump back, as tvOS does: at 0.45 damping it swung past rest and rocked once more. */
    fun nudge(dx: Float, dy: Float) {
        if (reduced) return
        scope.launch {
            push.animateTo(Offset(dx * 14f, dy * 14f), tween(90, easing = Motion.EaseOut))
            push.animateTo(Offset.Zero, spring(dampingRatio = 0.65f, stiffness = 520f))
        }
        scope.launch {
            leanY.animateTo(dx * 5f, tween(90, easing = Motion.EaseOut))
            leanY.animateTo(0f, spring(dampingRatio = 0.65f, stiffness = 420f))
        }
        scope.launch {
            leanX.animateTo(-dy * 5f, tween(90, easing = Motion.EaseOut))
            leanX.animateTo(0f, spring(dampingRatio = 0.65f, stiffness = 420f))
        }
    }

    // Up against a ceiling the caller stops before the key ever reaches this tile (the folder
    // opened in the hidden board has nothing above it): the tile answers with the same bump a key
    // at the wall gets, rather than swallowing the press (the owner, 2026-11-17: 「需要图标往上跳
    // 一下，就像往左无效时，往左移动一下一样」). A count, not a flag, so every press bumps again.
    // Taking the focus is not a press: the count is read again on arrival, or a move walking the
    // focus from tile to tile bumped both of them (the owner: 「移动选择图标的时候，两个图标都会
    // 向上撞墙，应该只变大缩小，不向上跳」).
    var blockedSeen by remember { mutableStateOf(blockedUp) }
    var blockedFocused by remember { mutableStateOf(false) }
    LaunchedEffect(focused, blockedUp) {
        if (focused && !blockedFocused) blockedSeen = blockedUp
        blockedFocused = focused
        if (blockedUp != blockedSeen) {
            blockedSeen = blockedUp
            // Only a press for the tile that holds the focus; and never while it is being moved,
            // where the tile's answer is its size and its wiggle alone.
            if (focused && !moving) {
                Sounds.limit()
                nudge(0f, -1f)
            }
        }
    }

    Box(modifier.size(width, height)) {
        // A dock's icons rise out of its bar: growth goes up from their bottom edge, so the bar
        // under them stays where it is while the focused one stands taller.
        val origin = if (growUp) TransformOrigin(0.5f, 1f) else TransformOrigin.Center
        if (showShadow && !AerialSettings.lite && DiagLayers.shows("shadow")) {
            // A lifted tile casts a soft shadow below it, as cards do on tvOS. The system's
            // elevation shadow is too faint to read on a TV; this one is baked, so it costs one
            // image draw while a tile is lifted.
            Canvas(
                Modifier
                    .size(width, height)
                    .graphicsLayer {
                        transformOrigin = origin
                        zoom(scale, focusScale)
                        rotationY = leanY.value
                        rotationX = leanX.value
                        rotationZ = wobble?.value ?: 0f
                        translationX = push.value.x
                        translationY = push.value.y + ShadowDrop.toPx() * lift
                        cameraDistance = 14f * density
                        alpha = lift * opacity() * styleAlphaState.value
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    },
            ) {
                val shadow = tileShadow(size.width.roundToInt(), size.height.roundToInt(), Corner.toPx(), ShadowBlur.toPx())
                translate(-shadow.ext, -shadow.ext) { drawImage(shadow.image) }
            }
        }
        Box(
            Modifier
                .size(width, height)
                .onGloballyPositioned { bounds = it.boundsInWindow() }
                .graphicsLayer {
                    alpha = opacity() * styleAlphaState.value
                    // Fading into the screensaver, without an offscreen buffer per tile: every tile
                    // of both rows fades at once (Xiaomi MiTV-MFTR0 over the aerial film, four
                    // screensaver entries: 49 janky frames with the buffers, 27 without). The logo
                    // over the plate lets a little of it through meanwhile, unseen in a fade that short.
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                    transformOrigin = origin
                    zoom(scale, focusScale)
                    rotationY = leanY.value
                    rotationX = leanX.value
                    rotationZ = wobble?.value ?: 0f
                    translationX = push.value.x
                    translationY = push.value.y
                    cameraDistance = 14f * density
                    // The plain rounded rect holds the continuous outline the tile draws itself,
                    // and the hardware clips to it for free.
                    shape = RoundedCornerShape(Corner)
                    clip = true
                }
                .focusRequester(focusRequester)
                .onFocusChanged {
                    if (it.isFocused && !focused) Sounds.focusMoved()
                    focused = it.isFocused
                    if (it.isFocused) onFocused() else {
                        pressed = false
                        longPressJob?.cancel()
                    }
                }
                .onKeyEvent { e ->
                    val code = e.nativeKeyEvent.keyCode
                    val down = e.type == KeyEventType.KeyDown
                    val dir = when (code) {
                        NativeKeyEvent.KEYCODE_DPAD_LEFT -> FocusDirection.Left
                        NativeKeyEvent.KEYCODE_DPAD_RIGHT -> FocusDirection.Right
                        NativeKeyEvent.KEYCODE_DPAD_UP -> FocusDirection.Up
                        NativeKeyEvent.KEYCODE_DPAD_DOWN -> FocusDirection.Down
                        else -> null
                    }
                    if (dir != null) {
                        // A held key repeats: the wall is told once, on the first press, or the tile
                        // stayed pushed and leaning and the limit sound ran on while the key was held.
                        if (down && !focusManager.moveFocus(dir) && e.nativeKeyEvent.repeatCount == 0) {
                            Sounds.limit()
                            when (dir) {
                                FocusDirection.Left -> nudge(-1f, 0f)
                                FocusDirection.Right -> nudge(1f, 0f)
                                FocusDirection.Up -> nudge(0f, -1f)
                                else -> nudge(0f, 1f)
                            }
                        }
                        return@onKeyEvent true
                    }
                    // The Menu key opens the same menu: old remotes (Phicomm boxes) rarely report a
                    // held OK as held (issue #28). On key-up, and only for a press that began on
                    // this tile, so no release is left over to choose the menu's first row.
                    if (e.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_MENU) {
                        if (down && e.nativeKeyEvent.repeatCount == 0) menuPressed = true
                        else if (e.type == KeyEventType.KeyUp && menuPressed) {
                            menuPressed = false
                            Sounds.select()
                            onLongPress(bounds)
                        }
                        return@onKeyEvent true
                    }
                    if (!e.isSelect()) return@onKeyEvent false
                    if (down) {
                        if (e.nativeKeyEvent.repeatCount > 0 && pressed && !longFired) {
                            // Remotes that auto-repeat a held key report the long press themselves.
                            longPressJob?.cancel()
                            longFired = true
                            pressed = false
                            Sounds.select()
                            onLongPress(bounds)
                        } else if (e.nativeKeyEvent.repeatCount == 0) {
                            pressed = true
                            longFired = false
                            longPressJob?.cancel()
                            longPressJob = scope.launch {
                                delay(550)
                                if (pressed) {
                                    longFired = true
                                    pressed = false
                                    Sounds.select()
                                    onLongPress(bounds)
                                }
                            }
                        }
                    } else if (e.type == KeyEventType.KeyUp) {
                        longPressJob?.cancel()
                        if (pressed && !longFired) {
                            Sounds.select()
                            onClick(bounds)
                        }
                        pressed = false
                    }
                    true
                }
                // Touch (issue #60): a tap opens the app and a long press opens its menu, both
                // focusing the tile first. The lift that ends a long press belongs to this tile's
                // gesture, so it cannot choose the menu's first row. A drag that scrolls the grid
                // cancels both.
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            tryAwaitRelease()
                            pressed = false
                        },
                        onTap = {
                            runCatching { focusRequester.requestFocus() }
                            Sounds.select()
                            click(bounds)
                        },
                        onLongPress = {
                            pressed = false
                            runCatching { focusRequester.requestFocus() }
                            Sounds.select()
                            longPress(bounds)
                        },
                    )
                }
                .focusProperties {
                    if (rowEnd) right = FocusRequester.Cancel
                    nav?.invoke(this)
                }
                .focusable(enabled),
        ) {
            Spacer(Modifier.fillMaxSize().drawWithCache {
                val art = app.art
                // A square slot shows the app's own icon 1:1 when it has one (original look).
                // Other tiles, and dopamine's plate, still use the 16:9 artwork and cut its
                // middle out here.
                val source = if (square) art.square ?: art.back else art.back
                val cropSquare = square && art.square == null
                val w = size.width.roundToInt()
                val h = size.height.roundToInt()
                val full = IntSize(w, h)
                // The plate is a picture made once: the artwork cut to the continuous outline with
                // its rim, so a tile is one image draw, as before. Cut on the GPU (a path, or masks
                // sampled with the artwork) it took the Xiaomi MFTR0 from 11ms to 15-72ms a frame.
                // There are two, each shown pixel for pixel: one at rest size, and one at the focused
                // size, made the first time the tile is focused. With only the rest one, the focus
                // zoom stretched it and banners read soft (issue #307); with only the focused one,
                // tiles at rest were shrunk and lost a little.
                val fw = focusedPx(size.width, focusScale)
                val fh = focusedPx(size.height, focusScale)
                // Made in the background (TileFaces.prepare). Until it is there the artwork is drawn
                // as it is, inside the layer's rounded clip: nearly the same picture, and the tiles
                // show at once instead of waiting for the faces.
                fun face(fw: Int, fh: Int): ImageBitmap? = TileFaces.ready(source, fw, fh, cropSquare)
                    ?: if (TileFaces.background) null
                    else TileFaces.face(source, fw, fh, Corner.toPx() * fw / w, 0.5.dp.toPx() * fw / w, cropSquare)
                val shape = BitmapShader(smoothMask(w, h, Corner.toPx()), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                    .apply { setLocalMatrix(Matrix().apply { setScale(size.width / w, size.height / h) }) }
                val rim = tileRim(w, h, Corner.toPx(), 0.5.dp.toPx())
                onDrawBehind {
                    // Plate and logo are placed in the face's pixels; at focus the zoom undoes the scale.
                    val lifted = scale > (1f + focusScale) / 2f
                    val pw = if (lifted) fw else w
                    val ph = if (lifted) fh else h
                    // Read here so the tile draws again when the faces it waits for are made.
                    TileFaces.version
                    // Lite: no sheen (a shader per frame) and no parallax on the focused tile.
                    val lite = AerialSettings.lite
                    scale(size.width / pw, size.height / ph, pivot = Offset.Zero) {
                        val made = face(pw, ph)
                        if (made != null) drawImage(made)
                        else {
                            // Cut to the outline here too, not only by the layer: Redmi X50 (MiTV-NSOP0,
                            // Novatek, Android 9) showed the focused tile square-cornered as Back
                            // started the screensaver (issue #654). Only until the face is made.
                            if (!rawLogged[0] && (focused || lift > 0.01f)) { rawLogged[0] = true; DiagLog.i("tile ${app.label} drawn before its face, ${pw}x$ph") }
                            clipPath(smoothRectPath(Rect(0f, 0f, pw.toFloat(), ph.toFloat()), Corner.toPx() * pw / size.width)) {
                            // A square slot cut out of the 16:9 artwork takes its middle; a
                            // separate 1:1 artwork is drawn whole.
                            val side = minOf(source.width, source.height)
                            drawImage(
                                source,
                                srcOffset = IntOffset(
                                    if (cropSquare) (source.width - side) / 2 else 0,
                                    if (cropSquare) (source.height - side) / 2 else 0,
                                ),
                                srcSize = if (cropSquare) IntSize(side, side) else IntSize(source.width, source.height),
                                dstSize = IntSize(pw, ph),
                                filterQuality = FilterQuality.Medium,
                            )
                            }
                        }
                        art.fore?.let { fore ->
                            // The 1:1 square artwork already contains the mark and any badge.
                            if (square && art.square != null) return@let
                            val at = art.foreAt
                            // Parallax: the logo travels further than the plate when the tile leans.
                            val px = if (lite) 0f else leanY.value / 7f * pw * 0.035f
                            val py = if (lite) 0f else -leanX.value / 7f * ph * 0.035f
                            if (square) {
                                // The same cut, applied to the logo: same scale both ways so it keeps
                                // its shape, centred where it sat in the 16:9 artwork, and shrunk
                                // only if the cut would take the ends off a wide one.
                                val artW = art.back.width.toFloat()
                                val artH = art.back.height.toFloat()
                                val k = ph / artH
                                val cx = (at.center.x * artW - (artW - artH) / 2f) * k
                                val cy = at.center.y * artH * k
                                val fit = minOf(1f, pw * 0.88f / (fore.width * k).coerceAtLeast(1f))
                                val dw = fore.width * k * fit
                                val dh = fore.height * k * fit
                                drawImage(
                                    fore,
                                    dstOffset = IntOffset((cx - dw / 2f + px).roundToInt(), (cy - dh / 2f + py).roundToInt()),
                                    dstSize = IntSize(dw.roundToInt(), dh.roundToInt()),
                                    filterQuality = FilterQuality.Medium,
                                )
                            } else {
                                drawImage(
                                    fore,
                                    dstOffset = IntOffset((at.left * pw + px).roundToInt(), (at.top * ph + py).roundToInt()),
                                    dstSize = IntSize((at.width * pw).roundToInt(), (at.height * ph).roundToInt()),
                                    filterQuality = FilterQuality.Medium,
                                )
                            }
                        }
                    }
                    if (lift > 0.01f && !lite) {
                        // Light from above: a soft sheen across the top, brighter where the tile leans toward it.
                        val glintX = size.width * (0.3f + leanY.value / 7f * 0.25f)
                        val glint = RadialGradient(
                            glintX, -size.height * 0.25f, size.width * 0.75f,
                            Color.White.copy(alpha = 0.28f * lift).toArgb(), Color.Transparent.toArgb(), Shader.TileMode.CLAMP,
                        )
                        drawRect(ShaderBrush(ComposeShader(glint, shape, PorterDuff.Mode.DST_IN)), blendMode = BlendMode.Screen)
                    }
                    // A focused tile's rim catches a little more light — but not in the dock: the
                    // hairline round one grown square there reads as a stray white edge.
                    if (lift > 0.01f && !nameAbove) drawImage(rim, dstSize = full, alpha = 0.08f * lift)
                }
            })
        }

        // The outer frame belongs to the original style alone. Translucent icons are read as glass
        // on the wallpaper and a frame around one would put a box back around what the style means
        // to float free — it gets a hairline edge instead (below), as the dock has. The dock needs
        // one because a square grown by a third among squares of nearly the same size is not
        // enough to tell at a glance, but a heavy ring would be noise on one small square of a row.
        val framed = AerialSettings.iconStyle == IconStyle.Original
        if (showFrame && framed) {
            // The frame sits outside the tile, so it is drawn on its own unclipped layer that moves
            // exactly as the tile does: same lift, lean, push and jiggle.
            Canvas(
                Modifier
                    .size(width, height)
                    .graphicsLayer {
                        transformOrigin = origin
                        zoom(scale, focusScale)
                        rotationY = leanY.value
                        rotationX = leanX.value
                        rotationZ = wobble?.value ?: 0f
                        translationX = push.value.x
                        translationY = push.value.y
                        cameraDistance = 14f * density
                        // Not through styleAlphaState: the original style keeps its icons at 70–80%,
                        // so the frame is what has to stay whole and say where the focus is.
                        alpha = ring * ringIn * opacity()
                        // No offscreen buffer while fading: it would be only tile-sized and clip the
                        // frame down to the four corner arcs that reach inside the tile's bounds.
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    },
            ) {
                // The frame grows out from the tile to its place: drawn at its full gap, pulled in
                // by what the gap still lacks.
                val frame = ringFrame(size.width.roundToInt(), size.height.roundToInt(), Corner.toPx(), RingGap.toPx(), RingWidth.toPx(), 1.dp.toPx())
                val e = frame.ext - RingGap.toPx() * 0.6f * (1f - ringIn)
                translate(-e, -e) {
                    scale((size.width + 2 * e) / frame.image.width, (size.height + 2 * e) / frame.image.height, pivot = Offset.Zero) {
                        drawImage(frame.image)
                    }
                }
            }
        }

        // The focused icon's hairline edge: thin and white, on the tile's own edge, drawn on its
        // own layer so it moves and scales exactly as the tile does.
        //  - The dock marks its grown icon with one: a square a third larger among squares of
        //    nearly the same size is not enough to tell at a glance.
        //  - The translucent style asks for one too (the owner: 「半透明图标模式下，焦点图标加个细白
        //    边」). There the tile wears no frame and floats free on the wallpaper, at 80% against
        //    the others' 30%, so the edge is the one mark that says which icon holds the focus. It
        //    comes on whatever the focus style, being that style's only mark.
        //  - The wheel as well (the owner: 「轮盘模式的焦点图标需要加个细白边」). Its edge used to
        //    take the icon's own alpha, and the wheel holds even its focused icon at 80%, so on the
        //    TV's bright wallpaper the line all but vanished. Like the translucent style's, it is
        //    whole now, whatever the icon under it does.
        val solidEdge = AerialSettings.iconStyle == IconStyle.Translucent || AerialSettings.iconStyle == IconStyle.Wheel
        val showEdge by remember {
            derivedStateOf {
                ringInState.value > 0.01f && (nameAbove || AerialSettings.iconStyle == IconStyle.Translucent)
            }
        }
        if (showEdge) {
            Canvas(
                Modifier
                    .size(width, height)
                    .graphicsLayer {
                        transformOrigin = origin
                        zoom(scale, focusScale)
                        rotationY = leanY.value
                        rotationX = leanX.value
                        rotationZ = wobble?.value ?: 0f
                        translationX = push.value.x
                        translationY = push.value.y
                        cameraDistance = 14f * density
                        // Not through the icon's alpha where that is dimmed (translucent, wheel):
                        // the edge is what says where the focus is, so it stays whole.
                        alpha = if (solidEdge) ringIn else ring * ringIn * opacity()
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    },
            ) {
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.92f),
                    cornerRadius = CornerRadius(Corner.toPx(), Corner.toPx()),
                    style = Stroke(1.5.dp.toPx()),
                )
            }
        }

        // A folder's icons wear a hairline edge of their own (see [edge]): one dark plate under
        // every one of them, and the edge is what keeps the artwork's corners readable on it.
        if (edge) {
            Canvas(
                Modifier
                    .size(width, height)
                    .graphicsLayer {
                        transformOrigin = origin
                        zoom(scale, focusScale)
                        rotationY = leanY.value
                        rotationX = leanX.value
                        rotationZ = wobble?.value ?: 0f
                        translationX = push.value.x
                        translationY = push.value.y
                        cameraDistance = 14f * density
                        alpha = opacity()
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    },
            ) {
                val line = 1.dp.toPx()
                val s = size
                // Drawn inside the bounds, not on them: a stroke on the outline is cut in half.
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.34f),
                    topLeft = Offset(line / 2f, line / 2f),
                    size = Size(s.width - line, s.height - line),
                    cornerRadius = CornerRadius(Corner.toPx(), Corner.toPx()),
                    style = Stroke(line),
                )
            }
        }

        if (showName && DiagLayers.shows("names")) BasicText(
            text = app.label,
            style = (if (nameAbove)
                // In the dock the icons are small and sit close together: the name takes a size
                // down, so the row of names does not crowd.
                Type.Headline.copy(fontSize = 12.sp)
            else Type.Headline).copy(
                textAlign = TextAlign.Center,
                // Names sit on the wallpaper; a soft shadow keeps them readable over bright photos.
                shadow = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 8f),
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = if (nameAbove) -(34.dp) else height + nameGap)
                .requiredSize(width + 36.dp, 24.dp)
                .graphicsLayer {
                    // Only the focused app shows its name. With every name shown, the others stay a
                    // little dimmer than the focused one. The 70% is for the icons: a name at 70% of
                    // that is hard to read over a picture, so only the translucent style dims it —
                    // and the wheel, where the fade with distance is the whole point of the row.
                    val nameDim = if (nameFollowsIcon || AerialSettings.iconStyle == IconStyle.Translucent)
                        styleAlphaState.value else 1f
                    alpha = (if (AerialSettings.showAllNames) 0.7f + 0.3f * lift else lift) * opacity() * nameDim
                    // Keep the shadow outside the text bounds when the name is dimmed or fading.
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                    // Sits just under the lifted tile, and rises into place as it appears. Above it,
                    // as a dock does, it goes up by as much as the icon grew.
                    translationY = if (nameAbove) -(height.toPx() * (scale - 1f)) + (1f - lift) * 6.dp.toPx()
                    else height.toPx() * (scale - 1f) / 2f - (1f - lift) * 6.dp.toPx() +
                        if (nameOnPlate) 0f else ring * ringIn * (RingGap + RingWidth).toPx() * scale
                },
        )
    }
}


/**
 * The tile at rest: [back] cut to the continuous outline, with a hairline rim as on tvOS icons so
 * dark artwork still reads against black.
 */
/**
 * A side of the focused tile in pixels: about [ref] times [px], grown by the same whole number of
 * pixels at both ends, so a face of this size lands exactly on the screen's pixels. At
 * 294 px × 1.15 = 338.1 the face drifted across pixel boundaries and was resampled on the screen,
 * which cost about as much sharpness as the stretch it replaced (Xiaomi MiTV-MFTR0, issue #307).
 */
private fun focusedPx(px: Float, ref: Float = FOCUSED_SCALE): Int {
    val r = px.roundToInt()
    return r + 2 * (r * (ref - 1f) / 2f).roundToInt()
}

/**
 * The tile's zoom for [scale], per axis, so that at [ref] each side is exactly [focusedPx]. [ref]
 * is this tile's own focused scale: a dock's icon grows further than a tile a row on the shelf.
 */
private fun GraphicsLayerScope.zoom(scale: Float, ref: Float = FOCUSED_SCALE) {
    val t = (scale - 1f) / (ref - 1f)
    scaleX = 1f + t * (focusedPx(size.width, ref) / size.width - 1f)
    scaleY = 1f + t * (focusedPx(size.height, ref) / size.height - 1f)
}

/**
 * Tile faces by artwork and pixel size. A face is a path drawn with anti-aliasing on the CPU: made
 * on a tile's first draw, the whole grid's took the main thread about 120 ms in the frame the tiles
 * first appear on a cold start (Xiaomi MiTV-MFTR0, release build; more on slower TVs). Home makes
 * them on background threads before it shows the tiles ([prepare]); a tile only draws them.
 * Kept while the artwork is: AppCatalog keeps that as long as the app is installed and unchanged.
 */
object TileFaces {
    private val made = java.util.WeakHashMap<ImageBitmap, HashMap<Long, ImageBitmap>>()

    /** Bumped when [prepare] has made a batch; tiles read it while drawing. */
    var version by mutableIntStateOf(0); private set

    /** Set while [prepare] is making faces: a tile then draws its artwork until its face is made. */
    @Volatile var background = false; private set

    /** The size [prepare] last made the faces at rest, packed as in [made]'s keys. */
    @Volatile private var restKey = 0L

    /**
     * Drops the faces at any other size than at rest (the focused ones, about 250 KB each): a tile
     * makes its own again when it is next focused. For the system's low-memory signals.
     */
    fun trimMemory() = synchronized(made) {
        val keep = restKey
        for (sizes in made.values) sizes.keys.retainAll { it == keep }
    }

    fun ready(back: ImageBitmap, w: Int, h: Int, crop: Boolean = false): ImageBitmap? =
        synchronized(made) { made[back]?.get(faceKey(w, h, crop)) }

    fun face(back: ImageBitmap, w: Int, h: Int, radius: Float, rimWidth: Float, crop: Boolean = false): ImageBitmap {
        val key = faceKey(w, h, crop)
        synchronized(made) { made[back]?.get(key) }?.let { return it }
        val face = tileFace(back, w, h, radius, rimWidth, crop)
        synchronized(made) { made.getOrPut(back) { HashMap(2) }[key] = face }
        return face
    }

    /** One face per artwork, size and shape: the high bit marks a square cut out of a 16:9 tile. */
    private fun faceKey(w: Int, h: Int, crop: Boolean): Long =
        (w.toLong() shl 32) or h.toLong() or if (crop) (1L shl 62) else 0L

    /**
     * Makes the faces of [arts] at rest on a screen [screenW] px wide at [density], as AppTile will
     * draw them, several at a time; with [focused], the focused faces instead. Off the main thread.
     * Sizes worked out exactly as AppTile's: a face of another size is simply made there.
     * [dockCount] is how many icons the dock is holding, when Home wears one: their square side
     * comes from it, so Home has to say rather than be counted by the list of artworks it passes.
     */
    suspend fun prepare(arts: List<TileArt>, screenW: Int, density: Float, focused: Boolean = false, dockCount: Int = 0) = coroutineScope {
        val dock = AerialSettings.iconStyle == IconStyle.Dock
        // The wheel's icons are square like a dock's, and sized by its own slot rather than by
        // how many apps there are: it shows the same few whatever the list holds.
        val slotSquare = dock || AerialSettings.iconStyle == IconStyle.Wheel
        val tileW = when {
            dock -> dockSide((screenW / density).dp, if (dockCount > 0) dockCount else arts.size).value * density
            slotSquare -> wheelSide((screenW / density).dp).value * density
            else -> restTileWidth((screenW / density).dp, AerialSettings.homeColumns).value * density
        }
        val tileH = if (slotSquare) tileW else tileW * 9f / 16f
        val w = tileW.roundToInt()
        val h = tileH.roundToInt()
        val ref = when {
            dock -> DOCK_FOCUS_SCALE
            slotSquare -> WHEEL_FOCUS_SCALE
            else -> FOCUSED_SCALE
        }
        val fw = if (focused) focusedPx(tileW, ref) else w
        val fh = if (focused) focusedPx(tileH, ref) else h
        val radius = Corner.value * density * fw / w
        val rim = 0.5f * density * fw / w
        if (!focused) {
            val key = faceKey(w, h, slotSquare)
            // Another number of apps a row: the faces at the old size go, focused ones too.
            val resized = restKey != 0L && restKey != key
            restKey = key
            if (resized) trimMemory()
        }
        // An app with a 1:1 artwork draws that whole in a square slot; everything else still
        // takes the middle square out of its 16:9 artwork.
        val todo = arts.distinctBy { System.identityHashCode(it.back) }
            .map { art ->
                val source = if (slotSquare) art.square ?: art.back else art.back
                Triple(art, source, slotSquare && art.square == null)
            }
            .filter { (_, source, crop) -> ready(source, fw, fh, crop) == null }
        if (todo.isEmpty()) return@coroutineScope
        // Only for the faces at rest: the focused ones are made ahead of need, and a tile focused
        // before its turn still makes its own.
        if (!focused) background = true
        try {
            // A few at a time, so the tiles appear in order (and Home's first frames keep a core).
            for (batch in todo.chunked(BATCH)) {
                batch.map { (_, source, crop) -> async { runCatching { face(source, fw, fh, radius, rim, crop) } } }.awaitAll()
                withContext(Dispatchers.Main) { version++ }
            }
        } finally {
            if (!focused) background = false
        }
    }

    private const val BATCH = 5
}

private fun tileFace(back: ImageBitmap, w: Int, h: Int, radius: Float, rimWidth: Float, crop: Boolean = false): ImageBitmap {
    val src = back.asAndroidBitmap()
    val bitmap = createBitmap(w, h)
    android.graphics.Canvas(bitmap).apply {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                .apply {
                    setLocalMatrix(
                        if (crop) {
                            // A square slot without its own artwork takes the middle of the 16:9
                            // picture, scaled by its height alone so nothing changes shape.
                            val side = minOf(src.width, src.height).toFloat()
                            val s = maxOf(w, h) / side
                            Matrix().apply {
                                setScale(s, s)
                                postTranslate(-(src.width - side) / 2f * s, -(src.height - side) / 2f * s)
                            }
                        } else {
                            Matrix().apply { setScale(w.toFloat() / src.width, h.toFloat() / src.height) }
                        },
                    )
                }
        }
        drawPath(smoothRectPath(Rect(0f, 0f, w.toFloat(), h.toFloat()), radius).asAndroidPath(), fill)
        drawBitmap(tileRim(w, h, radius, rimWidth, RIM_AT_REST).asAndroidBitmap(), 0f, 0f, null)
    }
    return bitmap.frozen().asImageBitmap()
}

/** The tile's rim: a hairline just inside its continuous outline, white at [alpha] on clear. */
private fun tileRim(w: Int, h: Int, radius: Float, width: Float, alpha: Float = 1f): ImageBitmap = baked(listOf("tile-rim", w, h, radius, width, alpha)) {
    val bitmap = createBitmap(w, h)
    android.graphics.Canvas(bitmap).apply {
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.White.copy(alpha = alpha).toArgb() }
        drawPath(smoothRectPath(Rect(0f, 0f, w.toFloat(), h.toFloat()), radius).asAndroidPath(), white)
        val inner = smoothRectPath(Rect(width, width, w - width, h - width), radius - width).asAndroidPath()
        drawPath(inner, Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) })
    }
    bitmap.frozen().asImageBitmap()
}

/** A tile's shadow, soft black under its continuous outline; [ext] px of it lie outside the tile on each side. */
private class TileShadow(val image: ImageBitmap, val ext: Float)

private fun tileShadow(w: Int, h: Int, corner: Float, blur: Float): TileShadow = baked(listOf("shadow", w, h, corner, blur)) {
    val ext = ceil(blur * 1.5f)
    val bitmap = createBitmap(w + 2 * ext.toInt(), h + 2 * ext.toInt())
    android.graphics.Canvas(bitmap).apply {
        translate(ext, ext)
        drawPath(
            smoothRectPath(Rect(0f, 0f, w.toFloat(), h.toFloat()), corner).asAndroidPath(),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.Black.copy(alpha = 0.7f).toArgb()
                maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
            },
        )
    }
    TileShadow(bitmap.frozen().asImageBitmap(), ext)
}

/** The high-contrast frame at its full gap; [ext] px of the picture lie outside the tile on each side. */
private class RingFrame(val image: ImageBitmap, val ext: Float)

private fun ringFrame(w: Int, h: Int, corner: Float, gap: Float, width: Float, dp: Float): RingFrame =
    baked(listOf("ring", w, h, corner, gap, width, dp)) {
        val out = gap + width / 2
        val halo = gap + width + dp
        val ext = ceil(halo + dp + 1f)
        val bitmap = createBitmap(w + 2 * ext.toInt(), h + 2 * ext.toInt())
        val canvas = android.graphics.Canvas(bitmap).apply { translate(ext, ext) }
        // Each line follows the tile's continuous corner, centred [d] outside its edge.
        fun around(d: Float) = smoothRectPath(Rect(-d, -d, w + d, h + d), corner + d).asAndroidPath()
        fun line(lineWidth: Float, color: Color) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = lineWidth
            this.color = color.toArgb()
        }
        // A faint dark edge outside the white one keeps the frame visible over bright sky or snow.
        canvas.drawPath(around(halo), line(2 * dp, Color.Black.copy(alpha = 0.3f)))
        // Lit like the glass it sits on: brightest where light falls from the top left and at the
        // far corner, still near white everywhere so it reads from across the room.
        canvas.drawPath(around(out), line(width, Color.White).apply {
            shader = LinearGradient(
                -out, -out, w + out, h + out,
                intArrayOf(Color.White.toArgb(), Color.White.copy(alpha = 0.78f).toArgb(), Color.White.copy(alpha = 0.94f).toArgb()),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
        })
        // A thin inner line of light, the glass's thickness.
        canvas.drawPath(around(out - width / 2 - 0.75f * dp), line(dp, Color.White.copy(alpha = 0.35f)))
        RingFrame(bitmap.frozen().asImageBitmap(), ext)
    }
