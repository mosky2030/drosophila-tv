package app.orchard.tv.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.orchard.tv.apps.AppEntry
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.ui.components.DockGlassTint
import app.orchard.tv.ui.components.drawFrostRect
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.wallpaper.Wallpapers
import kotlin.math.roundToInt

/**
 * The Hidden style's glass board (the owner, 2026-11-17: 「按下键，电视下边框弹出玻璃底板和方形
 * 图标…dock占据屏幕三分之二面积…如果APP很多，焦点到达最后一排就整体向上翻滚，打开文件夹就在本
 * 玻璃底板内原地打开，玻璃底板内显示文件夹名和图标即可…返回回到上一级，再返回自动全隐藏dock」;
 * round two: the bottom has no corners and sits flush with the screen's edge, the icons are the
 * wheel's square size, and Up on the first row goes straight to the settings pill).
 *
 * Under this style Home hides its tiles; Down slides this board up from the bottom edge and focus
 * lands on its first tile. The board is exactly as tall as the rows it holds (one row of apps, a
 * one-row board; three rows, a three-row board), six to a row; past most of the screen it stops
 * growing and Down on the last visible row scrolls the whole board a row at a time (the scroll
 * container follows focus). Opening a folder swaps the board's own content in place — the folder's
 * name over its apps — instead of the full-screen folder; Back returns from the folder to the
 * tiles, then again and the board slides back down.
 */
@Composable
internal fun HiddenDockPanel(
    visible: Boolean,
    apps: List<AppEntry>,
    folderName: String?,
    folderApps: List<AppEntry>,
    enabled: Boolean,
    focusedKey: String?,
    /** The app being rearranged, if any — its tile wiggles, the one it trades with slides aside. */
    movingKey: String? = null,
    /** Counts Up presses blocked inside a folder: the focused tile bumps up for each one. */
    blockedUp: Int = 0,
    requester: (String) -> FocusRequester,
    pillFocus: FocusRequester,
    onFocused: (String, Int) -> Unit,
    onLaunch: (AppEntry, Rect?) -> Unit,
    onOpenFolder: (AppEntry) -> Unit,
    onLongPress: (AppEntry, Rect?) -> Unit,
    /** While an app moves, a tap on another tile puts it there, as on Home's grid. */
    onMovingTap: (AppEntry) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        val den = LocalDensity.current
        val boardW = maxWidth
        // The board is only as tall as the rows it has to hold — one row of apps gets a board a
        // row tall, three rows one three rows tall (the owner, 2026-11-17: 「有一排就弹出够一排
        // 用的玻璃底板，有三排就弹出够三排用的」) — and stands flush with the bottom edge, as in
        // the sketch. Past ~0.86 of the screen it stops growing and the rows scroll instead.
        val gap = 22.dp
        val rowGap = 26.dp
        val columns = 6
        // The icons are the wheel's squares (the owner: 「图标大小按照轮盘的方形图标大小来」).
        val side = wheelSide(boardW)
        val shown = if (folderName != null) folderApps else apps
        val gridRows = ((shown.size + columns - 1) / columns).coerceAtLeast(1)
        val gridH = side * gridRows + rowGap * (gridRows - 1)
        // The glass's own margins: above the folder's name, around the grid, under the last
        // row's name — plus the name's band when a folder is open in the board.
        val padTop = 34.dp
        val gridPadTop = 20.dp
        // Below the last row sits its name (a tile's height, its gap and the name's line) and
        // the room the focused tile's zoom needs; less than this and the scroll clips them.
        val gridPadBottom = 48.dp
        val padBottom = 28.dp
        val headH = if (folderName != null) 38.dp else 0.dp
        val boardH = (padTop + headH + gridPadTop + gridH + gridPadBottom + padBottom)
            .coerceAtMost(maxHeight * 0.86f)
        AnimatedVisibility(
            visible = visible,
            // Both ways travel the board's own height, not the full-screen content's: sliding by
            // the content height (1080px for a 640px board) meant most of the exit ran with the
            // board already off the screen — and with the ease-out front-loading what was left,
            // the retraction covered its visible stretch in a few frames and read as a snap
            // (the owner, 2026-11-17: 「弹出来比下缩回去要流畅」). The exit is eased in, not out,
            // so the board leaves slowly and gathers speed under the edge.
            enter = slideInVertically(tween(300, easing = Motion.EaseOut)) { with(den) { boardH.roundToPx() } } +
                fadeIn(tween(220, easing = Motion.EaseOut)),
            // Out the way it came in: the board slides back down under the screen's edge — the
            // focus reached the pill, or Back — never a fade that leaves it flashing out
            // (the owner: 「玻璃地板下缩（就像出来是上移），不是闪没」).
            exit = slideOutVertically(tween(280, easing = Motion.EaseInOut)) { with(den) { boardH.roundToPx() } } +
                fadeOut(tween(110, delayMillis = 170, easing = Motion.EaseOut)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Box(Modifier.fillMaxSize()) {
                // The glass is drawn on a full-screen canvas, the way Home's dock bar is: the
                // frosted picture is a copy of the whole screen's wallpaper, and the board's
                // rect has to be named in the screen's coordinates for the right part of it to
                // be sampled. A rect in the board's own coordinates sampled the wallpaper's top
                // left corner instead of what lies under the board.
                val frost = (if (Wallpapers.moves()) Wallpapers.filmFrost else null) ?: Wallpapers.frosted
                Canvas(Modifier.fillMaxSize()) {
                    val left = 44.dp.toPx()
                    val top = size.height - boardH.toPx()
                    val r = 30.dp.toPx()
                    if (frost != null) {
                        // The bottom corners sit a radius below the screen edge, so the bottom is
                        // flat and flush with it (the owner: 「底部没有倒角，和屏幕平齐」).
                        drawFrostRect(
                            frost = frost,
                            rect = Rect(left, top, size.width - left, size.height + r),
                            root = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                            corner = r,
                            tint = DockGlassTint,
                            milk = 0.10f,
                            edge = 1.2.dp.toPx(),
                        )
                    } else {
                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                listOf(Color.White.copy(alpha = 0.26f), Color.White.copy(alpha = 0.12f)),
                                startY = top, endY = size.height,
                            ),
                            topLeft = Offset(left, top),
                            size = Size(size.width - left * 2, size.height - top),
                            cornerRadius = CornerRadius(r, r),
                        )
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0.30f),
                            topLeft = Offset(left, top),
                            size = Size(size.width - left * 2, size.height - top),
                            cornerRadius = CornerRadius(r, r),
                            style = Stroke(width = 1.dp.toPx()),
                        )
                    }
                }
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 44.dp)
                        .height(boardH)
                        .padding(horizontal = 46.dp)
                        .padding(top = padTop, bottom = padBottom),
                ) {
                // The folder opened in place: its name heads the board. Home's plain board
                // has no title and starts straight on the tiles. A title, not a headline: the
                // board's icons are its subject (the owner, 2026-11-17: 「文件夹内系统应用字体可以
                // 小一些。现在太大了」).
                if (folderName != null) {
                    BasicText(
                        folderName,
                        style = TextStyle(fontSize = 20.sp, color = Color.White.copy(alpha = 0.92f), fontWeight = FontWeight.Medium),
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        // The scroll container clips, and the focused tile grows taller than its
                        // cell; the rows began right at the container's edges and the growth was
                        // cut off (the owner: 「焦点图标上边被裁剪」). Room inside the scroll, on
                        // both ends, keeps the growth whole — and the name below the tile needs
                        // more still: it sits the tile's height plus its gap under the icon and
                        // drops again by as much as the focus zoom grows the tile.
                        .padding(top = gridPadTop, bottom = gridPadBottom),
                ) {
                    // The tiles are placed by hand rather than flowed in rows: each one animates to
                    // its slot, so a move trades two tiles by sliding them across each other
                    // instead of reflowing them into place with a hard cut (the owner: 「移动时选中
                    // 图标晃来晃去的动画和换位动画也需要加上」). Home's grid does the same.
                    BoxWithConstraints(Modifier.fillMaxWidth().height(gridH)) {
                        val cellW = (maxWidth - gap * (columns - 1)) / columns
                        // A tile narrower than its cell stands centred in it.
                        val cellInset = ((cellW - side) / 2).coerceAtLeast(0.dp)
                        val rowStep = side + rowGap
                        shown.forEachIndexed { i, app ->
                            val col = i % columns
                            val row = i / columns
                            val slotPx = with(den) {
                                IntOffset(
                                    (cellW * col + gap * col + cellInset).roundToPx(),
                                    (rowStep * row).roundToPx(),
                                )
                            }
                            key(app.key) {
                                val slot = animateIntOffsetAsState(
                                    slotPx,
                                    spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow),
                                    label = "hd-slot",
                                )
                                AppTile(
                                    app = app,
                                    width = side,
                                    height = side,
                                    focusRequester = requester(app.key),
                                    enabled = enabled,
                                    // The tile being rearranged wiggles, as it does on Home's grid.
                                    moving = app.key == movingKey,
                                    onFocused = { onFocused(app.key, i) },
                                    onClick = { bounds ->
                                        when {
                                            // A tap while moving puts the app there; it does not open it.
                                            movingKey != null -> onMovingTap(app)
                                            app.folder == null -> onLaunch(app, bounds)
                                            else -> onOpenFolder(app)
                                        }
                                    },
                                    // The app's own menu, as on Home's tiles: rearrange, group,
                                    // hide, info (the owner: 「移动（右键菜单）机制还没有完善」).
                                    onLongPress = { bounds -> if (movingKey == null) onLongPress(app, bounds) },
                                    // Up on the board's first row reaches the settings pill (the
                                    // owner: 「在第一排图标点遥控器往上键，直接到设置按钮」). Rows
                                    // below keep the default step up, which rolls the board. A
                                    // folder opened in the board has nothing above it to go to:
                                    // Up does nothing there (the owner, 2026-11-17: 「在文件夹内
                                    // 按上键应该无效，现在直接到设置按钮了」).
                                    nav = when {
                                        folderName != null -> ({ up = FocusRequester.Cancel })
                                        row == 0 -> ({ up = pillFocus })
                                        else -> null
                                    },
                                    // The name under the focused tile alone, or every name
                                    // when Personalize says so — the wheel's rule (the owner:
                                    // 「图标名称机制还没有完善」, then 「名称放图标下面」). It rides
                                    // its own icon's alpha.
                                    showName = AerialSettings.showAllNames ||
                                        app.key == focusedKey || app.key == movingKey,
                                    nameFollowsIcon = true,
                                    square = true,
                                    forcedAlpha = if (app.key == focusedKey || app.key == movingKey) 0.80f else 0.70f,
                                    edge = true,
                                    // Inside a folder Up is refused; the focused tile bumps up to
                                    // say so, as it does at any wall. Not while an app is being
                                    // moved: then the tile's answer is its size and wiggle only.
                                    blockedUp = if (movingKey == null && app.key == focusedKey) blockedUp else 0,
                                    modifier = Modifier
                                        // The moving tile rides over the one it trades with.
                                        .zIndex(if (app.key == movingKey) 1f else 0f)
                                        .offset { slot.value },
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
