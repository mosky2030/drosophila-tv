package app.orchard.tv.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalDensity
import app.orchard.tv.apps.AppEntry
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.settings.IconStyle
import app.orchard.tv.ui.components.dismissOnTap
import app.orchard.tv.ui.components.liquidGlass
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Type
import app.orchard.tv.wallpaper.Wallpapers
import kotlin.math.roundToInt

/**
 * An open folder, as on tvOS: its name over its apps, at Home's tile size, on Home blurred and
 * dimmed. Apps open from here as from Home; holding one gives its menu, which comes back here.
 * Back closes the folder, and focus returns to its tile.
 */
@Composable
fun FolderView(
    name: String?,
    apps: List<AppEntry>,
    /** Open; the folder may still be on screen under its app's menu without taking keys. */
    visible: Boolean,
    enabled: Boolean,
    /** The app being moved inside this folder, which follows the remote; null when none is. */
    movingKey: String? = null,
    gap: Dp,
    columns: Int,
    onLaunch: (AppEntry, Rect?) -> Unit,
    onLongPress: (AppEntry, Rect?) -> Unit,
    onDismiss: () -> Unit,
) {
    // Held on to, so the view still has its content while it animates out. Composed always, even
    // closed: only then does the AnimatedVisibility below see a false -> true flip and play its
    // enter animation. It used to return early while closed, so on opening it was composed fresh
    // already visible, and the whole view — backdrop included — snapped in in one frame: the
    // background flashed (the owner: 「打开组图标的时候，背景会闪一下」).
    val shownName = rememberLatest(name)
    val shownApps = rememberLatest(apps.takeIf { it.isNotEmpty() })
    val requesters = remember { HashMap<String, FocusRequester>() }
    fun requester(key: String) = requesters.getOrPut(key) { FocusRequester() }
    var focusedKey by remember(shownName) { mutableStateOf<String?>(null) }
    // The app whose menu is open: a closing menu hands focus to whatever lies under it first.
    var menuKey by remember(shownName) { mutableStateOf<String?>(null) }
    val density = LocalDensity.current

    BackHandler(enabled = visible && enabled, onBack = onDismiss)
    // On opening, the first app; back from a menu, the app it was for, or its neighbour if it left.
    LaunchedEffect(visible, enabled, shownApps) {
        if (!visible || !enabled) return@LaunchedEffect
        val shown = shownApps ?: return@LaunchedEffect
        withFrameNanos { }
        val wanted = menuKey ?: focusedKey
        menuKey = null
        val key = wanted?.takeIf { k -> shown.any { it.key == k } } ?: shown.first().key
        runCatching { requester(key).requestFocus() }
    }
    // While moving, focus follows the app to its new slot (Home takes the keys, as on Home).
    LaunchedEffect(movingKey, shownApps) {
        val shown = shownApps ?: return@LaunchedEffect
        val key = movingKey?.takeIf { k -> shown.any { it.key == k } } ?: return@LaunchedEffect
        withFrameNanos { }
        runCatching { requester(key).requestFocus() }
    }

    Box(Modifier.fillMaxSize()) {
        // Home's own picture, sharp: the folder sits over the grid's frosted glass otherwise,
        // which read as the wallpaper blurred twice over. It fades on its own clock, as long as
        // Home's frost (HomeScreen's backdropFrost, 320 ms): the plate leaves in 150 ms, and
        // fading this with it left the frosted Home bare for a moment — the background flashed
        // on close as well as on open (the owner: 「打开组图标的时候，背景会闪一下，关闭也会闪」).
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(200, easing = Motion.EaseOut)),
            exit = fadeOut(tween(320, easing = Motion.EaseOut)),
        ) {
            Wallpapers.backdrop?.let { picture ->
                Image(
                    picture, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(200, easing = Motion.EaseOut)) + scaleIn(tween(300, easing = Motion.Drawer), initialScale = 0.94f),
            exit = fadeOut(tween(150, easing = Motion.EaseOut)) + scaleOut(tween(150, easing = Motion.EaseOut), targetScale = 0.97f),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize().dismissOnTap(onDismiss)) {
                BoxWithConstraints(
                Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 44.dp),
                contentAlignment = Alignment.Center,
            ) {
                val cardMaxHeight = maxHeight
                // Five across the plate's own width, so a full row fills it exactly and the space
                // left and right of the icons is the plate's padding on both sides. A row of one
                // or two then starts at that left edge, as a list does, instead of floating in
                // the middle of the glass.
                // The icons wear the same shape as Home: a square slot under the dock and the
                // wheel, the 16:9 tile under the original and translucent looks. A folder used to
                // force 16:9 whatever Home wore, so a square-style grid opened into wide icons and
                // the icon mechanism read as broken (the owner: 「文件夹内也应该是正方形图标」).
                val slotSquare = AerialSettings.iconStyle == IconStyle.Dock ||
                    AerialSettings.iconStyle == IconStyle.Wheel
                val plateInner = maxWidth - 80.dp
                // A square tile must be the size of Home's own square app icon, not the width of a
                // 16:9 slot — using the latter made the folder's icons far bigger than the ones
                // outside (the owner: 「文件夹里的正方形图标变得很大，和外面独立的图标一样大」).
                // dockSide/wheelSide are the very functions Home uses for its resting app icons.
                val screenWide = maxWidth + 112.dp // undo this Box's 56.dp padding on each side
                val squareSide = if (AerialSettings.iconStyle == IconStyle.Dock)
                    dockSide(screenWide, (shownApps?.size ?: 4).coerceAtLeast(1))
                else wheelSide(screenWide)
                val tileW = if (slotSquare) squareSide else ((plateInner - gap * 4) / 5).coerceAtLeast(1.dp)
                val tileH = if (slotSquare) squareSide else tileW * 9f / 16f
                // With smaller square icons, five to a row would leave the plate half empty; fit as
                // many of Home's-size squares across it as do, so the folder still reads as a grid.
                val flowColumns = if (slotSquare)
                    ((plateInner + gap) / (squareSide + gap)).roundToInt().coerceIn(1, shownApps?.size?.coerceAtLeast(1) ?: 8)
                else columns
                Column(Modifier.fillMaxWidth()) {
                    // The name stands on the plate, over it, not in it: it was the folder's title
                    // inside the glass, where it read as the first thing on the glass rather than
                    // the name of the thing the glass is.
                    shownName?.let {
                        BasicText(
                            it,
                            style = Type.LargeTitle.copy(
                                // It sits on the picture now, so it needs a shade of its own to read
                                // over a bright wallpaper.
                                shadow = Shadow(Color.Black.copy(alpha = 0.55f), blurRadius = 18f),
                            ),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 48.dp, bottom = 20.dp),
                        )
                    }
                    shownApps?.let { shown ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            // A shadow under the slab lifts it off the picture; the glass itself hides
                            // whatever it covers, so the shadow has to be drawn round the same corners.
                            .shadow(elevation = 40.dp, shape = RoundedCornerShape(48.dp), clip = false)
                            // The plate shows the wallpaper's own shapes, blurred, not one colour
                            // (Lite flattens the glass to its average otherwise): a plate of one
                            // colour is a slab over the picture, and reads as no glass at all.
                            .liquidGlass(48.dp, litePicture = true)
                            // The sheen the dock's bar wears, over the glass and under the icons:
                            // light down the plate and a hairline where its rim catches it. Over a
                            // sharp wallpaper, and with Lite on (which flattens the glass itself to
                            // one colour), this is what makes the plate read as glass.
                            .drawBehind {
                                val r = CornerRadius(48.dp.toPx())
                                drawRoundRect(
                                    brush = Brush.verticalGradient(
                                        listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.10f)),
                                    ),
                                    cornerRadius = r,
                                )
                                drawRoundRect(
                                    color = Color.White.copy(alpha = 0.28f),
                                    cornerRadius = r,
                                    style = Stroke(width = 1.dp.toPx()),
                                )
                            }
                            .padding(start = 40.dp, end = 40.dp, top = 30.dp, bottom = 58.dp),
                    ) {
                        // Only the apps scroll, and only once they outgrow the plate: a folder of two keeps
                        // a card that fits them. The room above the first row lives inside the scroll
                        // (after it in the chain, so it is part of the scrolling content): that is
                        // where a focused tile's zoom reaches up — and its focus frame with it, a ring
                        // outside the grown tile which the scroll would otherwise shave off.
                        val zoomRoom = tileH * (FOCUSED_SCALE - 1f) / 2f + FocusFrameOutset + 6.dp
                        Column(
                            Modifier
                                .heightIn(max = cardMaxHeight - 190.dp - zoomRoom)
                                .verticalScroll(rememberScrollState())
                                .padding(top = zoomRoom),
                        ) {
                            // Where each app's tile came from, in slots, so a move slides the two
                            // tiles across each other as Home's grid does. Home's tiles sit in a Box
                            // and animate their slot offset; the folder's sit in flow rows, where a
                            // reorder just reflows — which read as a hard cut (the owner: 「群组内
                            // 移动图标的时候是硬切换…不像单独图标交换的那么流畅」). The state lives
                            // here, above the rows: a tile moved to another row is composed under a
                            // different Row, and remember under its key there would start over.
                            val lastSlots = remember { HashMap<String, IntOffset>() }
                            val shifts = remember { HashMap<String, Animatable<IntOffset, AnimationVector2D>>() }
                            fun shifter(key: String) = shifts.getOrPut(key) {
                                Animatable(IntOffset.Zero, IntOffset.VectorConverter)
                            }
                            // Room for the label under each row: 10dp nameGap + 24dp label box +
                            // the focus zoom's drop. 44dp left the focused name touching the row
                            // below on the smaller tiles (the owner, 2026-11-18).
                            val rowRoom = 52.dp
                            shown.chunked(flowColumns).forEachIndexed { r, row ->
                                // The row keeps room under its tiles for their names, which the scroll
                                // container would otherwise clip at its own bounds. Tiles start at
                                // the plate's left padding and run on from there.
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(gap),
                                    modifier = Modifier.padding(bottom = rowRoom),
                                ) {
                                    row.forEachIndexed { c, app ->
                                        val slot = IntOffset(c, r)
                                        key(app.key) {
                                            val shift = shifter(app.key)
                                            LaunchedEffect(slot, app.key) {
                                                val last = lastSlots[app.key]
                                                lastSlots[app.key] = slot
                                                if (last == null || last == slot) return@LaunchedEffect
                                                val d = last - slot
                                                with(density) {
                                                    shift.snapTo(
                                                        IntOffset(
                                                            d.x * (tileW + gap).roundToPx(),
                                                            d.y * (tileH + rowRoom).roundToPx(),
                                                        ),
                                                    )
                                                }
                                                shift.animateTo(
                                                    IntOffset.Zero,
                                                    spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow),
                                                )
                                            }
                                            AppTile(
                                                app = app,
                                                width = tileW,
                                                height = tileH,
                                                square = slotSquare,
                                                focusRequester = requester(app.key),
                                                enabled = enabled,
                                                moving = app.key == movingKey,
                                                // No forced alpha: a folder's icon looks through exactly as
                                                // the same app does on Home — the icon style decides both
                                                // its resting and its focused alpha (70/80 for the plain,
                                                // dock and wheel looks, 30/80 for the translucent one).
                                                // Folders used to pin every icon at 70% and read as solid
                                                // inside, whatever Home wore (the owner, 2026-11-17: 「无论
                                                // 哪种图标风格，文件夹内的图标好像都是不透明的，改成和单独图标
                                                // 一样的透明机制」).
                                                // One plate under every icon: a hairline edge of its own keeps
                                                // dark artwork from melting into it.
                                                edge = true,
                                                // Breathing room under a square's name, as on the
                                                // hidden board (the owner, 2026-11-18); rowRoom
                                                // above keeps the next row clear of it.
                                                nameGap = 10.dp,
                                                onFocused = { focusedKey = app.key },
                                                // While an app moves, a tap opens nothing (OK ends the move on Home's keys).
                                                onClick = { bounds -> if (movingKey == null) onLaunch(app, bounds) },
                                                onLongPress = { bounds -> if (movingKey == null) { menuKey = app.key; onLongPress(app, bounds) } },
                                                modifier = Modifier
                                                    // The moving tile rides over the one it trades with.
                                                    .zIndex(if (app.key == movingKey) 1f else 0f)
                                                    .offset { shift.value },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    }
                }
            }
        }
    }
    }
}
