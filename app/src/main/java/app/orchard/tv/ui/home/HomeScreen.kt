package app.orchard.tv.ui.home

import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent as NativeKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.animateValueAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.orchard.tv.CcPage
import app.orchard.tv.apps.SourceLooks
import app.orchard.tv.HomeViewModel
import app.orchard.tv.Overlay
import app.orchard.tv.R
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.settings.BackAction
import app.orchard.tv.settings.ClockPlace
import app.orchard.tv.settings.IconStyle
import app.orchard.tv.settings.Restricted
import app.orchard.tv.guard.KidsWatch
import app.orchard.tv.apps.AppEntry
import app.orchard.tv.ui.control.ControlCenter
import app.orchard.tv.ui.wallpaper.SheetCovers
import app.orchard.tv.ui.wallpaper.WallpaperSheet
import app.orchard.tv.wallpaper.AerialFormats
import app.orchard.tv.wallpaper.ScreensaverMode
import app.orchard.tv.wallpaper.Wallpapers
import app.orchard.tv.ui.components.liquidGlass
import app.orchard.tv.ui.components.DockGlassTint
import app.orchard.tv.ui.components.drawFrostRect
import app.orchard.tv.ui.theme.LocalReducedMotion
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Type
import app.orchard.tv.ui.components.diagLayer
import androidx.compose.runtime.snapshotFlow
import app.orchard.tv.system.DiagLog
import app.orchard.tv.system.LaunchFallback
import app.orchard.tv.system.Sleep
import app.orchard.tv.system.SystemProbe
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The plate's distance from the bottom edge with no second row peeking: as from the sides. */
private val PlateMarginBottom = 30.dp

/**
 * The gap between the two rows on one plate in two-row mode, with no names under them there: the
 * 50 dp the grid keeps for names left a wide empty band across the glass, and with four a row
 * pushed the plate up into the weather line.
 */
private val ShelfRowGap = 30.dp

/**
 * Under the second row in two-row mode without a plate: its names and a margin. As much as the
 * plate's margin and padding, so with and without the plate the rows take the same height: at
 * 70 dp the rows without one stood 20 dp higher and, four a row, pushed the clock up.
 */
private val RowNameRoom = 50.dp

/** Where the large clock's date sits: just under the status pill. */
private val LargeClockTop = 80.dp

/**
 * From the large clock's top to where the shelf may start: its weather line ends some 136 dp down
 * (216 dp from the top, MiTV-MFTR0, 1.65), and a gap.
 */
private val LargeClockHeight = 152.dp

/** The same, for the extra-large clock: the time 148 sp instead of 104, so some 44 dp deeper. */
private val LargeClockHeightXL = 196.dp

/**
 * The highest the large clock may go to make room for two rows, level with the status pill. Only
 * at the left: at the right it would run into the pill.
 */
private val LargeClockTopMin = 28.dp

/** How much taller the glass plate grows to hold the top row's names, with the second row kept clear of it. */
private val PlateNameRoom = 28.dp

/** Control Center has been drawn once in this process (HomeScreen's warm-up). */
private object ControlCenterWarmed { var done = false }

private const val CC_WARM_AFTER_MS = 3_000L

private val GridMargin = 56.dp

/** The highest the row above may sit once Home has scrolled into the grid: clear of the status pill. */
private val RowAboveTop = 72.dp

/**
 * The gap between tiles for [columns] a row (AerialSettings.homeColumns): about a fifth of a tile,
 * as tvOS keeps it when it fits six, so four or six look like five scaled rather than packed.
 */
internal fun gridGap(columns: Int): Dp = when (columns) {
    4 -> 34.dp
    6 -> 24.dp
    else -> 28.dp
}

/** A tile's width at rest on a screen [screenW] wide; TileFaces works out the same size. */
internal fun restTileWidth(screenW: Dp, columns: Int): Dp = (screenW - GridMargin * 2 - gridGap(columns) * (columns - 1)) / columns

/** The most icons a dock holds at once: past this the row scrolls rather than shrinking further. */
private const val DockCapacity = 8

/**
 * The dock's margin at either end of its bar, less than the grid's: a dock is a bar along the
 * bottom of the screen, not a shelf across it, so it may come closer to the sides.
 */
private val DockMargin = 26.dp

/** Icons on the dock sit closer together than tiles a row. */
private val DockGap = 8.dp

/**
 * The dock's whole scale, icons and bar together. Eight across a 1080p screen came out as a band
 * of fat icons that read as posters rather than as a dock; at a little over half that they are a
 * strip of modest ones, and eight of them plus a folder still fit without scrolling.
 */
private const val DockIconScale = 0.55f

/**
 * A dock icon's side at rest, for [count] icons on a screen [screenW] wide: eight of them across
 * the screen at [DockIconScale], and fewer keep that size instead of growing to fill it — a bar of
 * three or four icons is then as narrow as they are (AppTile and TileFaces must work out the same).
 */
internal fun dockSide(screenW: Dp, count: Int): Dp {
    val n = count.coerceIn(1, DockCapacity)
    val avail = screenW - DockMargin * 2 - DockGap * (n - 1)
    return ((avail / n) * DockIconScale).coerceIn(26.dp, 74.dp)
}

/**
 * The dock's geometry: every app on one bar of glass at the bottom of the screen, as squares. The
 * focused icon grows a good deal and the ones beside it step down with distance, and each takes
 * its own room along the bar, so growing never overlaps a neighbour — the row spreads instead.
 * Past eight icons the row is wider than the screen and its left end slides along under the focus.
 */
private class DockMetrics(private val screenW: Dp, screenH: Dp, count: Int, private val groupAt: Int) {
    private val n = count.coerceAtLeast(1)

    /** The icon's side at rest. */
    val side = dockSide(screenW, count)

    private val viewport = screenW - DockMargin * 2
    private val padH = 12.dp
    private val padV = 8.dp

    /**
     * Room kept between the last app and the first group. Nothing is drawn in it: a focused app
     * grows ([DOCK_FOCUS_SCALE] − 1) / 2 out of its right side and a focused group the same out of
     * its left, and this gap is wider than both together, so the two kinds stay apart at every
     * focus without a rule between them — a line moving a slot at a time read as a stutter.
     */
    val seamGap: Dp =
        if (groupAt in 1 until n) side * (((DOCK_FOCUS_SCALE - 1f) + (zoom(1) - 1f)) / 2f) + DockGap + 6.dp else 0.dp

    /** The bar's top edge: the icons' rest height plus a margin of glass above and below. */
    private val barTop = screenH - PlateMarginBottom - padV - side - padV

    /** Icons stand on the bar's inner bottom, so growth goes up through its top rather than off its foot. */
    val iconTop = barTop + padV

    /**
     * How much an icon [distance] places from the focused one grows. The step is wide enough to be
     * read from across the room: the focused one is the tall one, the next is plainly smaller, and
     * the rest of the bar settles under its rest size.
     */
    fun zoom(distance: Int): Float = when (distance) {
        0 -> DOCK_FOCUS_SCALE
        1 -> 1.12f
        2 -> 1.02f
        3 -> 0.96f
        else -> 0.94f
    }

    private fun widths(focusAt: Int): List<Float> = List(n) { i ->
        if (focusAt < 0) 1f else zoom(kotlin.math.abs(i - focusAt))
    }

    private fun content(focusAt: Int): Dp =
        widths(focusAt).fold(0.dp) { acc, f -> acc + side * f } + DockGap * (n - 1) + seamGap

    /**
     * Each icon's slot, measured from the row's left end. The slot is the tile's layout box, so
     * half of what an icon gains by its own zoom is given back here: drawn from its middle, it
     * then fills exactly the room this row allots it, and icons never overlap however big they grow.
     */
    private fun row(focusAt: Int): List<Dp> {
        val w = widths(focusAt)
        var x = 0.dp
        return List(n) { i ->
            // The room the seam needs is taken before the first group, so both kinds stay apart.
            if (i == groupAt) x += seamGap
            val sideW = side * w[i]
            val at = x + (sideW - side) / 2
            x += sideW + DockGap
            at
        }
    }

    /** Where the row starts on screen: centred while it fits, from the margin once it does not. */
    private fun originX(focusAt: Int): Dp {
        val cw = content(focusAt)
        return if (cw > viewport) DockMargin else (screenW - cw) / 2
    }

    /** How far the row slides left to keep the focused icon in the middle of the screen. */
    private fun scroll(focusAt: Int): Dp {
        val cw = content(focusAt)
        if (cw <= viewport || focusAt < 0) return 0.dp
        val centre = row(focusAt)[focusAt] + side * widths(focusAt)[focusAt] / 2
        return (centre + DockMargin - screenW / 2).coerceIn(0.dp, cw - viewport)
    }

    /** Where each icon sits on screen, left to right, for the focus at [focusAt] (−1: none). */
    fun lefts(focusAt: Int): List<Dp> {
        val origin = originX(focusAt) - scroll(focusAt)
        return row(focusAt).map { it + origin }
    }

    /**
     * The bar of glass under the row, in px. It is as wide as the icons need, so three or four of
     * them get a short bar; a row too long for the screen gets a bar the width of the screen, with
     * its icons sliding along inside it.
     */
    fun bar(focusAt: Int, density: Float): Rect {
        val w = minOf(content(focusAt) + padH * 2, screenW)
        val h = side + padV * 2
        val left = (screenW - w) / 2
        return Rect(
            left.value * density, barTop.value * density,
            (left + w).value * density, (barTop + h).value * density,
        )
    }
}

/**
 * How many icons the wheel shows at once, the middle one included. Seven: the focused one, two
 * either side at full size, and a further pair past those, smaller and fainter, so the row reads as
 * a ring turning rather than a row that ends. Odd on purpose — an even count would put the focused
 * one between two slots and the row half a slot off the middle of the screen.
 */
private const val WheelSlots = 7

/** The wheel's icons stand further apart than a dock's: the middle one grows between its neighbours. */
private val WheelGap = 20.dp

/**
 * A wheel icon's side: as large as seven across the screen allow, taken down to two thirds of
 * that — the owner wanted the whole ring modest, the focus frame and the size step still to read.
 */
internal fun wheelSide(screenW: Dp): Dp =
    ((screenW - DockMargin * 2 - WheelGap * (WheelSlots - 1)) / WheelSlots * 2f / 3f).coerceIn(40.dp, 104.dp)

/** How much a slot [distance] from the middle shrinks: the two beside the focus keep its size. */
private fun wheelRest(distance: Int): Float = when (distance) {
    0, 1 -> 1f
    2 -> 0.88f
    else -> 0.76f
}

/** How solid a slot [distance] from the middle is: 80 at the focus, 50 beside it, then 20 and 12. */
private fun wheelAlpha(distance: Int): Float = when (distance) {
    0 -> 0.80f
    1 -> 0.50f
    2 -> 0.20f
    3 -> 0.12f
    else -> 0f
}

/** How far apart two apps sit on the ring, the short way round: what one press of Left turns by. */
private fun ringDistance(from: Int, to: Int, n: Int): Int {
    if (n <= 0) return 0
    var d = (to - from) % n
    if (d > n / 2) d -= n
    if (d < -n / 2) d += n
    return d
}

/**
 * The wheel's geometry: the same square icons [WheelSlots] at a time along the bottom, the focused
 * one held in the middle of the screen, on the wallpaper rather than on a bar. The row slides by a
 * slot a press and comes round at either end — the apps are a ring, so there is no end to stop at.
 */
private class WheelMetrics(screenW: Dp, screenH: Dp) {
    val side = wheelSide(screenW)

    /** One slot's width: what a press slides the row by. */
    val pitch = side + WheelGap

    private val padV = 9.dp
    private val barTop = screenH - PlateMarginBottom - padV - side - padV

    /** Icons stand on the same line a dock's bar would put them on. */
    val iconTop = barTop + padV

    /** Where the middle icon sits: the middle of the screen, with the ring centred on it. */
    val cx = screenW / 2
}

/** Grid geometry for the screen at hand. On a 1080p TV this is a 960×540 dp canvas. */
private class GridMetrics(screenW: Dp, screenH: Dp, val columns: Int, oneRow: Boolean, plateNames: Boolean, peek: Boolean, plate: Boolean, largeClock: Boolean, clockXL: Boolean, clockPlace: ClockPlace) {
    val margin = GridMargin
    val gap = gridGap(columns)
    val tileW = restTileWidth(screenW, columns)
    val tileH = tileW * 9f / 16f
    val pitchX = tileW + gap
    val pitchY = tileH + 50.dp

    /**
     * Extra room under the top row while it is the one in focus, for names on a plate grown to
     * hold them. Down in the grid the plate is gone and the rows close up to even spacing.
     */
    val firstGap = if (plateNames) PlateNameRoom else 0.dp

    /**
     * How far the second row sits under the top row in two-row mode while both are on the shelf:
     * a grid row where names go between them (no plate, or every name shown), closer on a plate
     * with none. Not the grid's row plus [firstGap]: that stacked two names' room between them.
     */
    val shelfSecond = if (plate && !plateNames) tileH + ShelfRowGap else pitchY

    /**
     * Two rows, laid from the bottom up: under the second row the plate's margin (and its names'
     * room), or without a plate room for names; then both rows.
     */
    private val twoRowFit = screenH - (if (plate) PlateMarginBottom + PlatePadV + firstGap else RowNameRoom) - shelfSecond - tileH

    /**
     * The room the clock takes: a size deeper when it is the extra-large one. The flip clock's
     * cards fit inside either height, so they change nothing here.
     */
    private val clockHeight = if (clockXL) LargeClockHeightXL else LargeClockHeight

    /**
     * Top of the large clock: just under the status pill, left or right, in both layouts, as on the
     * lock screen. Hung from the row it floated mid-screen in one-row mode and pressed on the plate
     * in two-row mode; up here the middle of the screen stays the wallpaper's. This is the date's
     * cap top: the clock's lines are boxed to their capitals. Two rows that do not fit under it
     * (four a row with every name) take it up beside the pill, at the left.
     */
    val clockY = if (oneRow || !largeClock) LargeClockTop else
        (twoRowFit - (if (plate) PlatePadV else 0.dp) - clockHeight)
            .coerceIn(if (clockPlace != ClockPlace.Right) LargeClockTop else LargeClockTopMin, LargeClockTop)

    /** The highest two rows may start: under the large clock's weather line. What does not fit lifts in on the second row ([shelfLift]). */
    private val twoRowFloor = if (largeClock) clockY + clockHeight + if (plate) PlatePadV else 0.dp else 0.dp

    /**
     * In two-row mode both rows sit low, laid from the bottom up ([twoRowFit]). In one-row mode, or
     * when every app fits in one row, the row sits low, with only the top of any second row showing
     * under the focused app's name. Every name shown changes nothing there: the names fit in the gap
     * between the rows, or on the plate, which grows by [firstGap]. Lifting the row for them as
     * well showed the second row nearly three times as far (issue #540).
     */
    val topRowY = when {
        !oneRow -> maxOf(twoRowFit, twoRowFloor)
        peek -> screenH - pitchY - 12.dp - firstGap
        // Nothing peeks under the row, so it goes as low as it fits: the plate as far from the
        // bottom edge as from the sides, or without a plate, room for the focused app's name.
        plate -> screenH - tileH - (PlateMarginBottom + PlatePadV) - firstGap
        else -> screenH - tileH - 70.dp
    }

    /**
     * Where a row rests once you go down into the grid: the row above stays in view, its top under
     * the status pill (28 dp down, 34 tall). Four a row are tall enough that 37% of the screen put
     * that row over the pill; six still rest at 37%, five some 5 dp lower.
     */
    val restY = maxOf(screenH * 0.37f, RowAboveTop + pitchY)
    val screenH = screenH

    /**
     * How far the grid scrolls before the shelf has given way: the clock and the plate fade over
     * it, the grid's glass comes in, the film stops past half of it. The top row's way up to
     * [restY], but never less than half a row: two rows four a row sit as high as [restY] or
     * higher, and at zero or less Home counted as scrolled past the shelf while still on it,
     * and the aerial film never played.
     */
    val collapse = (topRowY - restY).coerceAtLeast(pitchY / 2)

    /**
     * How far Home lifts, still on its shelf, for the second row in two-row mode: what did not fit
     * under the large clock, so the second row and its names clear the bottom edge. Nothing in
     * almost every layout; it used to lift whenever a name went under the second row.
     */
    val shelfLift = if (oneRow) 0.dp else topRowY - twoRowFit

    /** The rows on the shelf, over the plate: the top row, and in two-row mode the second too. */
    val shelfRows = if (oneRow) 1 else 2

    fun slot(index: Int, onTop: Boolean): Pair<Dp, Dp> {
        val row = index / columns
        val y = when {
            row == 1 && onTop && shelfRows > 1 -> topRowY + shelfSecond
            else -> topRowY + pitchY * row + if (row > 0 && onTop) firstGap else 0.dp
        }
        return Pair(margin + pitchX * (index % columns), y)
    }

    fun scrollFor(row: Int, rows: Int): Dp {
        if (row <= 0) return 0.dp
        val wanted = topRowY + pitchY * row - restY
        val bottom = topRowY + pitchY * rows + 36.dp - screenH
        return minOf(wanted, maxOf(bottom, topRowY + pitchY - restY))
    }
}

/** How long the screensaver waits for the wallpaper's film to move before playing its own. */
private const val FILM_SAVER_WAIT_MS = 10_000L

/** How long the large clock's time takes back from the screensaver's corner (ShelfClock waits for it). */
internal const val CLOCK_BACK_MS = 1000

/** How long Control Center takes to come in before a picture of Home stands in for it. */
private const val HOME_SHOT_AFTER_MS = 450L

/** How often that picture is taken again while Control Center stays open, for the clock. */
private const val HOME_SHOT_EVERY_MS = 30_000L

@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onLaunch: (AppEntry, Rect?) -> Unit,
    onOpen: (Intent) -> Unit,
    onAppInfo: (String) -> Unit,
    onUninstall: (String) -> Unit,
    onStartScreensaver: () -> Unit,
) {
    val apps = vm.apps
    val overlay = vm.overlay
    val moving = vm.movingKey
    val reduced = LocalReducedMotion.current
    val density = LocalDensity.current
    val now by rememberNow()
    val scope = rememberCoroutineScope()

    val requesters = remember { HashMap<String, FocusRequester>() }
    fun requester(key: String) = requesters.getOrPut(key) { FocusRequester() }
    val pillRequester = remember { FocusRequester() }
    val appContext = LocalContext.current
    val firmwareInputPanel = remember {
        SystemProbe.tclInputPanel(appContext) != null || SystemProbe.coocaaInputPanel(appContext) ||
            SystemProbe.tianciInputPanel(appContext)
    }

    var focusedKey by remember { mutableStateOf<String?>(null) }
    // Where focus goes when the overlay closes, when that is not the tile it was on. Kept apart
    // from focusedKey: a closing menu hands focus to whatever tile lies under it first.
    var focusAfterOverlay by remember { mutableStateOf<String?>(null) }
    var indexAfterOverlay by remember { mutableStateOf(0) }
    var pillFocused by remember { mutableStateOf(false) }
    var lastFocusedIndex by remember { mutableStateOf(0) }
    val focusedIndex = apps.indexOfFirst { it.key == focusedKey }
    val columns = AerialSettings.homeColumns
    // The dock is Home's shape, not its glass: one bar holding every app, whatever the row and
    // column settings say. Kids and Elders mode keep the plain grid they were set up with.
    val dock = AerialSettings.iconStyle == IconStyle.Dock && !Restricted.active
    // The wheel is Home's other bar: the same one row of every app along the bottom, but the focus
    // stays in the middle and the row slides through it instead of the focus walking along it.
    val wheel = AerialSettings.iconStyle == IconStyle.Wheel && !Restricted.active
    /** Either bar: one row holding every app, whatever the row and column settings say. */
    val bar = dock || wheel
    // The Hidden style (the owner, 2026-11-17): Home shows nothing but the wallpaper — no clock,
    // no pill, no tiles. Up fades the pill in with focus on it, Down slides the glass board up
    // from the bottom edge (HiddenDockPanel); Back steps out of the folder, then out of the board.
    val hidden = AerialSettings.iconStyle == IconStyle.Hidden && !Restricted.active
    // Saved, not just remembered: an app launched from the board and Back to Home may have
    // rebuilt Home, and the owner expects the board where they left it.
    var hiddenDock by rememberSaveable { mutableStateOf(false) }
    var hiddenFolderId by rememberSaveable { mutableStateOf<String?>(null) }
    var hiddenPillShown by rememberSaveable { mutableStateOf(false) }
    // Up pressed inside a folder opened in the board: refused, and the tile bumps for each press.
    var hiddenUpBump by remember { mutableStateOf(0) }
    val pillAlpha = remember { Animatable(1f) }
    // With nothing on the screen there is nothing to hold the focus, and a remote that has no focus
    // says nothing to Home at all: this invisible box keeps it, so Up and Down still arrive.
    val idleRequester = remember { FocusRequester() }
    LaunchedEffect(hidden, hiddenPillShown) {
        if (!hidden) {
            pillAlpha.snapTo(1f)
            return@LaunchedEffect
        }
        // 「当用户按上键，设置图标迅速淡入，焦点到达」— in fast, out a shade slower.
        pillAlpha.animateTo(
            if (hiddenPillShown) 1f else 0f,
            tween(if (hiddenPillShown) 160 else 240, easing = Motion.EaseOut),
        )
    }
    // Another style chosen, or Kids and Elders mode taking Home over: the board and the pill go.
    // The wallpaper's idle focus is picked up below, once focusApp exists.
    LaunchedEffect(hidden) {
        if (hidden) return@LaunchedEffect
        hiddenDock = false
        hiddenFolderId = null
        hiddenPillShown = false
    }
    // The board just slid up, or a folder just opened in it: focus its first tile. Leaving a
    // folder is not covered — Back hands focus back to the folder's own tile first.
    LaunchedEffect(hiddenDock) {
        if (!hidden || !hiddenDock || hiddenFolderId != null) return@LaunchedEffect
        withFrameNanos { }
        apps.firstOrNull()?.let { runCatching { requester(it.key).requestFocus() } }
    }
    // A folder opened in the board takes the focus to its first app, one frame on: the board is
    // swapping its content in on the frame this state changed.
    LaunchedEffect(hiddenFolderId) {
        val id = hiddenFolderId ?: return@LaunchedEffect
        withFrameNanos { }
        vm.folderApps[id]?.firstOrNull()?.let { runCatching { requester(it.key).requestFocus() } }
    }
    val focusedRow = if (bar || hidden || pillFocused || focusedIndex < 0) 0 else focusedIndex / columns
    // Kids mode's lock covers Home: nothing under it takes focus.
    val kidsLock = if (Restricted.active) Restricted.lock else null
    val gridEnabled = overlay == Overlay.None && kidsLock == null

    // The aerial wallpaper's film, and how far it shows through.
    val filmCover = remember { Animatable(0f) }
    val liveFilm = remember { LiveFilm() }
    DisposableEffect(liveFilm) {
        LiveFilm.onHome = liveFilm
        onDispose { if (LiveFilm.onHome === liveFilm) LiveFilm.onHome = null }
    }
    val homeVideo = remember { HomeVideo() }
    DisposableEffect(homeVideo) {
        HomeVideo.onHome = homeVideo
        onDispose { if (HomeVideo.onHome === homeVideo) HomeVideo.onHome = null }
    }
    // A screensaver of aerials over the aerial wallpaper, or of the owner's videos over their video
    // wallpaper, carries the wallpaper's film on, one shot from Home to screensaver: the apps fade,
    // the clock moves to the corner (saverCarriesWallpaper). Whether the film is moving already or
    // still on its frame (Back on the top row), as long as Home shows it: on the top row with
    // nothing over it. A film that does not move within FILM_SAVER_WAIT_MS hands over to the
    // screensaver's own films.
    var filmFailed by remember(vm.screensaverVisible) { mutableStateOf(false) }
    val filmSaverChosen = remember(vm.screensaverVisible) {
        vm.screensaverVisible && saverCarriesWallpaper(appContext) &&
            (filmCover.value > 0.5f || focusedRow == 0 && overlay == Overlay.None && moving == null)
    }
    val filmSaver = filmSaverChosen && !filmFailed
    LaunchedEffect(filmSaverChosen) {
        if (!filmSaverChosen) return@LaunchedEffect
        val moved = withTimeoutOrNull(FILM_SAVER_WAIT_MS) { snapshotFlow { filmCover.value > 0.99f }.first { it } }
        if (moved == null) {
            DiagLog.i("wallpaper film did not move for the screensaver; its own films instead")
            filmFailed = true
        }
    }
    // The plate over a stopped film, once its glass is ready; out as the film moves again.
    val filmStopped by animateFloatAsState(
        if (liveFilm.stopped) 1f else 0f,
        tween(if (liveFilm.stopped) 250 else 900, easing = Motion.EaseOut), label = "film-stopped",
    )
    // A photo screensaver over a picture wallpaper carries the picture on the same way: its shading
    // lifts and it slowly draws in from just as Home shows it (HomeBackground), and the photos move
    // on from it.
    val stillSaver = remember(vm.screensaverVisible) {
        if (vm.screensaverVisible && focusedRow == 0 && overlay == Overlay.None && moving == null && Wallpapers.backdrop != null) {
            Wallpapers.screensaverPicture()
        } else null
    }
    val stillCarry = remember(stillSaver) { stillSaver?.let { SaverCarry.Still(it) } }
    var saverImage by remember { mutableStateOf<ImageBitmap?>(null) }
    val saverReveal = remember { Animatable(0f) }
    val saverPush = remember { Animatable(0f) }
    LaunchedEffect(stillSaver) {
        if (stillSaver != null) {
            saverPush.snapTo(0f)
            saverImage = withContext(Dispatchers.Default) {
                Wallpapers.picture(stillSaver, Wallpapers.screenW, Wallpapers.screenH)?.asImageBitmap()
            } ?: return@LaunchedEffect
            coroutineScope {
                val pushing = if (reduced) null else launch { drawIn(saverPush) }
                launch { saverReveal.animateTo(1f, tween(1800, easing = Motion.EaseInOut)) }
                // Covered by the next photo: nothing of it shows, so it stops drawing.
                snapshotFlow { stillCarry?.covered == true }.first { it }
                pushing?.cancel()
                saverReveal.snapTo(0f)
                saverPush.snapTo(0f)
            }
        } else if (saverImage != null) {
            // Back to Home: the picture settles back as the shading returns.
            coroutineScope {
                launch { saverPush.animateTo(0f, tween(380, easing = Motion.EaseOut)) }
                saverReveal.animateTo(0f, tween(380, easing = Motion.EaseOut))
            }
            saverImage = null
        }
    }
    val carrying = filmSaver || stillSaver != null
    val toSaver = remember { Animatable(0f) }
    LaunchedEffect(carrying) {
        when {
            reduced -> toSaver.snapTo(if (carrying) 1f else 0f)
            carrying -> toSaver.animateTo(1f, tween(2000, easing = Motion.EaseInOut))
            // A key woke it: Home comes back at once and settles in, the way it went. At 380 ms the
            // apps' part of it (60% of the way) was over in a blink and read as a cut (issue #357).
            else -> toSaver.animateTo(0f, tween(650, easing = Motion.EaseOut))
        }
    }
    // The large clock's time goes back the way it came, at half the pace. On Home's 650 ms ease-out
    // it was most of the way back in two or three frames (Xiaomi MiTV-MFTR0: 45-57 ms a frame as it
    // wakes) and leapt; out of the corner and back are one motion run both ways.
    val clockToSaver = remember { Animatable(0f) }
    LaunchedEffect(carrying) {
        when {
            reduced -> clockToSaver.snapTo(if (carrying) 1f else 0f)
            carrying -> clockToSaver.animateTo(1f, tween(2000, easing = Motion.EaseInOut))
            else -> clockToSaver.animateTo(0f, tween(CLOCK_BACK_MS, easing = Motion.EaseInOut))
        }
    }

    // Grid scroll offset (px). The remote sets it from the focused row; a finger drags it freely
    // (issue #60), and then it stays where the finger left it until the next key press.
    val scroll = remember { Animatable(0f) }
    var dragged by remember { mutableStateOf(false) }
    val movingNow by rememberUpdatedState(moving)

    fun focusApp(index: Int) {
        val app = apps.getOrNull(index.coerceIn(0, (apps.size - 1).coerceAtLeast(0))) ?: return
        runCatching { requester(app.key).requestFocus() }
    }

    // The Hidden style swapped in or out while a tile held the focus: its node is gone (in) or
    // brand new (out), so the focus is handed over here rather than left nowhere. In, the
    // wallpaper's invisible box takes it; out, the tile Home had last.
    LaunchedEffect(hidden) {
        withFrameNanos { }
        if (hidden) runCatching { idleRequester.requestFocus() }
        else focusApp(lastFocusedIndex)
    }

    // First focus once the apps are in; afterwards, put focus back wherever it was when an overlay closes.
    LaunchedEffect(vm.loaded, overlay) {
        if (!vm.loaded || overlay != Overlay.None) return@LaunchedEffect
        withFrameNanos { }
        val wanted = focusAfterOverlay
        focusAfterOverlay = null
        if (wanted != null) { focusedKey = wanted; pillFocused = false }
        val index = apps.indexOfFirst { it.key == focusedKey }
        // A tile that went while the overlay was up (hidden, put in a folder, a folder ungrouped)
        // leaves focus where it was.
        // The Hidden style has no row of its own: the focus goes back to the board's tile the menu
        // came from, to the pill that opened the panel, or to the wallpaper alone — never to a
        // tile that is not drawn.
        if (hidden) {
            if (hiddenDock && apps.any { it.key == focusedKey }) runCatching { requester(focusedKey!!).requestFocus() }
            else runCatching { (if (hiddenPillShown) pillRequester else idleRequester).requestFocus() }
        }
        else if (pillFocused && focusedKey != null) runCatching { pillRequester.requestFocus() }
        else focusApp(if (index >= 0) index else if (wanted != null) indexAfterOverlay else lastFocusedIndex)
    }
    // A folder whose last app left closes.
    LaunchedEffect(overlay, vm.folderApps) {
        val open = (overlay as? Overlay.Folder)?.id ?: (overlay as? Overlay.AppMenu)?.inFolder ?: return@LaunchedEffect
        if (vm.folderApps[open].isNullOrEmpty()) vm.overlay = Overlay.None
    }
    // An app vanished from under the focus (uninstalled or hidden): take its neighbour.
    LaunchedEffect(apps) {
        if (focusedKey != null && apps.none { it.key == focusedKey } && overlay == Overlay.None) {
            withFrameNanos { }
            focusApp(lastFocusedIndex)
        }
        // While moving, focus follows the app to its new slot.
        moving?.let { withFrameNanos { }; runCatching { requester(it).requestFocus() } }
    }
    // Moving starts from the menu, which is still on screen for a frame: focus the app once it is gone.
    LaunchedEffect(moving) {
        val key = moving ?: return@LaunchedEffect
        withFrameNanos { }
        runCatching { requester(key).requestFocus() }
    }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(windowFocused, vm.homeResetPending, vm.loaded) {
        if (windowFocused && vm.homeResetPending && vm.loaded) {
            withFrameNanos { }
            dragged = false
            focusApp(0)
            vm.homeResetPending = false
        }
    }

    BackHandler(enabled = overlay == Overlay.None && moving == null) {
        // The Hidden style steps back one level at a time (the owner, 2026-11-17: 「返回回到上一级，
        // 再返回自动全隐藏dock」): the folder opened in the board, then the board itself, then the
        // pill, and only then what Personalize says Back does.
        if (hidden) {
            val folder = hiddenFolderId
            when {
                folder != null -> {
                    hiddenFolderId = null
                    val back = apps.firstOrNull { it.folder?.id == folder }
                    scope.launch {
                        withFrameNanos { }
                        if (back != null) runCatching { requester(back.key).requestFocus() }
                        else runCatching { idleRequester.requestFocus() }
                    }
                }
                hiddenDock -> {
                    hiddenDock = false
                    hiddenFolderId = null
                    scope.launch { withFrameNanos { }; runCatching { idleRequester.requestFocus() } }
                }
                hiddenPillShown -> {
                    hiddenPillShown = false
                    scope.launch { withFrameNanos { }; runCatching { idleRequester.requestFocus() } }
                }
                else -> when (AerialSettings.backAction) {
                    BackAction.Screensaver -> onStartScreensaver()
                    BackAction.Off -> Unit
                }
            }
            return@BackHandler
        }
        // tvOS: Back from inside the grid returns to the top row; Back on the top row does what
        // Personalize says — the screensaver or nothing. A grid scrolled by touch counts as
        // being inside it.
        val scrolled = dragged && scroll.value > 1f
        dragged = false
        // Handled even when that is turned off: unhandled, Back would close Home itself.
        val perRow = if (bar) apps.size.coerceAtLeast(1) else columns
        if (focusedRow > 0 || scrolled) focusApp(focusedIndex.coerceAtLeast(0) % perRow)
        else when (AerialSettings.backAction) {
            BackAction.Screensaver -> onStartScreensaver()
            BackAction.Off -> Unit
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { e ->
                // The remote takes the grid back from the finger: scroll follows focus again.
                // Back leaves it to BackHandler, which needs to know the grid was dragged
                // (gesture navigation sends Back as a key too).
                if (e.nativeKeyEvent.keyCode != NativeKeyEvent.KEYCODE_BACK) dragged = false
                // Moving takes the remote here rather than on the moving tile: the tile does not
                // always get focus back when the menu closes, and focus then landed on the first
                // tile, which moved focus instead of the app (#58).
                if (moving != null) {
                    val code = e.nativeKeyEvent.keyCode
                    // The Hidden style's board is always six wide whatever Home's grid says, and
                    // the move happens over the board's order.
                    val step = if (hidden) 6 else columns
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent code != NativeKeyEvent.KEYCODE_HOME
                    when (code) {
                        NativeKeyEvent.KEYCODE_DPAD_LEFT -> vm.moveBy(-1)
                        NativeKeyEvent.KEYCODE_DPAD_RIGHT -> vm.moveBy(1)
                        NativeKeyEvent.KEYCODE_DPAD_UP -> vm.moveBy(-step)
                        NativeKeyEvent.KEYCODE_DPAD_DOWN -> vm.moveBy(step)
                        NativeKeyEvent.KEYCODE_BACK, NativeKeyEvent.KEYCODE_DPAD_CENTER,
                        NativeKeyEvent.KEYCODE_ENTER, NativeKeyEvent.KEYCODE_NUMPAD_ENTER -> vm.endMove()
                        else -> return@onPreviewKeyEvent false
                    }
                    return@onPreviewKeyEvent true
                }
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // A folder opened in the board has nothing above it: Up there does nothing at
                // all, rather than carrying the focus up to the settings pill (the owner,
                // 2026-11-17: 「在文件夹内按上键应该无效，现在直接到设置按钮了」) — but it is not
                // swallowed either: the tile bumps up as it does at the grid's edge, so the press
                // is answered (「需要图标往上跳一下，就像往左无效时，往左移动一下一样」).
                if (hidden && hiddenDock && hiddenFolderId != null &&
                    e.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_DPAD_UP
                ) {
                    if (e.nativeKeyEvent.repeatCount == 0) hiddenUpBump++
                    return@onPreviewKeyEvent true
                }
                // The Hidden style's two ways in, before anything else looks at the D-pad: Up
                // fades the pill in and puts the focus on it, Down slides the glass board up.
                // The board's own board keeps the D-pad once it is open (grid navigation in it).
                if (hidden && overlay == Overlay.None && !hiddenDock) {
                    when (e.nativeKeyEvent.keyCode) {
                        NativeKeyEvent.KEYCODE_DPAD_UP -> {
                            if (!hiddenPillShown) {
                                hiddenPillShown = true
                                // The pill is only focusable once it is shown: a frame later.
                                scope.launch {
                                    withFrameNanos { }
                                    runCatching { pillRequester.requestFocus() }
                                }
                            }
                            return@onPreviewKeyEvent true
                        }
                        NativeKeyEvent.KEYCODE_DPAD_DOWN -> {
                            hiddenPillShown = false
                            hiddenFolderId = null
                            hiddenDock = true
                            return@onPreviewKeyEvent true
                        }
                    }
                }
                when (e.nativeKeyEvent.keyCode) {
                    NativeKeyEvent.KEYCODE_MENU -> {
                        if (kidsLock != null) return@onPreviewKeyEvent true
                        // On an app tile, Menu opens that app's menu (AppTile): remotes whose held
                        // OK never reads as held had no other way to it (issue #28). Control
                        // Center stays one press up, on the status pill, where Menu still opens it.
                        if (overlay == Overlay.None && !pillFocused && focusedIndex >= 0) return@onPreviewKeyEvent false
                        // Inside a folder, Menu is the focused app's, as on Home.
                        if (overlay is Overlay.Folder) return@onPreviewKeyEvent false
                        vm.overlay = if (overlay is Overlay.ControlCenter) Overlay.None else Overlay.ControlCenter()
                        true
                    }
                    NativeKeyEvent.KEYCODE_SETTINGS -> {
                        // The remote's gear key reaches Home where no firmware answers it: a stock
                        // Android TV launcher opens the settings on it, and with Aerial as Home it did
                        // nothing (Riviera RLED-GLT32TPQ10, MediaTek, Android 14, issue #1190).
                        // Kids and Elders mode keeps the settings shut, as Control Center does.
                        if (kidsLock != null || Restricted.active) return@onPreviewKeyEvent true
                        vm.whenUnlocked {
                            LaunchFallback.arm(SystemProbe.settingsFallbacks(appContext))
                            onOpen(SystemProbe.settingsIntent(appContext))
                        }
                        true
                    }
                    NativeKeyEvent.KEYCODE_TV_INPUT -> {
                        // Kids and Elders mode switches only to the inputs it shows, as tiles.
                        if (Restricted.active && !firmwareInputPanel) return@onPreviewKeyEvent true
                        // TCL's firmware opens its own input panel on this key; answering it here
                        // as well drew both panels at once (TCL MT5879, PR #334).
                        if (firmwareInputPanel) return@onPreviewKeyEvent false
                        vm.overlay = Overlay.ControlCenter(CcPage.Inputs)
                        true
                    }
                    else -> false
                }
            },
    ) {
        val rows = if (bar) 1 else (apps.size + columns - 1) / columns
        // With a single row of apps the two-row layout would leave the bottom of the screen empty.
        val oneRow = bar || AerialSettings.oneRowHome || rows <= 1
        // In two rows the second is on screen already: down to it stays on the shelf, under the
        // same picture and plate, and only the row after it goes into the grid (issue #633: the
        // grid's frosted picture flashed up on the way to an app in the second row).
        val inGrid = focusedRow >= if (oneRow) 1 else 2
        // With every name shown, the top row's go on its glass plate, which grows to hold them.
        // The translucent icon style drops the plate: glass behind glass read as clutter. The dock
        // draws its own bar and no shelf plate, so neither applies there — and the Hidden style
        // keeps nothing on Home at all, plate included.
        val plateGlass = !bar && !hidden && AerialSettings.homePlate && AerialSettings.iconStyle != IconStyle.Translucent
        val plateNames = !bar && AerialSettings.showAllNames && plateGlass
        val peek = AerialSettings.peekNextRow
        val hasPlate = plateGlass
        val largeClock = AerialSettings.largeClock
        val clockXL = AerialSettings.clockXL
        // The full-screen clock hangs over the icons instead of standing above them, so the grid
        // lays itself out as if there were no shelf clock at all: nothing to leave room for.
        val clockFull = AerialSettings.clockFull
        val clockShelf = largeClock && !clockFull
        val clockPlaceNow = AerialSettings.clockPlace
        val metrics = remember(maxWidth, maxHeight, columns, oneRow, plateNames, peek, hasPlate, clockShelf, clockXL, clockPlaceNow) {
            GridMetrics(maxWidth, maxHeight, columns, oneRow, plateNames, peek, hasPlate, clockShelf, clockXL, clockPlaceNow)
        }
        // Where the groups begin on the bar: the place of the first grouped tile, if there is one.
        // Past it the bar is only folders, with a wider gap before the first of them.
        val dockGroupAt = if (dock) apps.count { it.folder == null } else 0
        // The dock: one bar, its icons sized by how many there are, its slots spread out from the
        // focused one so a grown icon never sits on its neighbour.
        val dockM = remember(maxWidth, maxHeight, apps.size, dockGroupAt) {
            DockMetrics(maxWidth, maxHeight, apps.size, dockGroupAt)
        }
        // Which place on the bar each app takes: the groups all go to its right end, as a dock
        // keeps its extras there, and everything else keeps the order Home already had.
        val dockPlace = remember(apps, dock) {
            if (!dock) IntArray(0) else IntArray(apps.size).also { at ->
                apps.indices.sortedBy { if (apps[it].folder != null) 1 else 0 }
                    .forEachIndexed { place, index -> at[index] = place }
            }
        }
        // Focus on the pill (the settings at the top right) rests the whole row: no icon stays
        // grown while nothing on the bar has the focus.
        val dockFocusAt = if (dock && !pillFocused) dockPlace.getOrNull(focusedIndex) ?: -1 else -1
        val dockLefts = remember(dockM, dockFocusAt) { dockM.lefts(dockFocusAt) }
        val dockBar by animateValueAsState(
            dockM.bar(dockFocusAt, density.density),
            Rect.VectorConverter,
            if (reduced) snap() else spring(dampingRatio = 1f, stiffness = 420f),
            label = "dock-bar",
        )
        // The wheel: seven slots, the focused one in the middle of the screen. One float holds where
        // the row rests, so every icon slides together on a press and the one coming in carries the
        // focus frame into the middle with it. It walks the ring rather than the list, so the last
        // app and the first are a press apart instead of a sweep across the whole row.
        val wheelM = remember(maxWidth, maxHeight) { WheelMetrics(maxWidth, maxHeight) }
        val wheelPos = remember { Animatable(0f) }
        LaunchedEffect(wheel, focusedIndex, apps.size) {
            if (!wheel || apps.isEmpty()) return@LaunchedEffect
            val n = apps.size
            val at = focusedIndex.coerceAtLeast(0)
            val resting = ((wheelPos.value % n) + n) % n
            // The short way round, from wherever the row really rests — fraction and all. A press
            // interrupts the slide before it, and the row stops between slots; aiming from its
            // rounded slot then (resting.toInt()) left that fraction standing, and the whole row
            // drifted a press's worth off the middle, one interrupted slide at a time.
            var step = at - resting
            if (n > 0) step -= n * kotlin.math.round(step / n)
            if (reduced) wheelPos.snapTo(at.toFloat())
            // Anything more than a few slots is a move, not a turn (the wheel just switched on):
            // it lands rather than sweeping the ring past the middle.
            else if (kotlin.math.abs(step) > 3.5f) wheelPos.snapTo(at.toFloat())
            else wheelPos.animateTo(wheelPos.value + step, spring(dampingRatio = 1f, stiffness = 420f))
        }
        val collapsePx = with(density) { metrics.collapse.toPx() }

        // Into the grid as from one row: the first row whole at the top. Two rows go in a row
        // further down, from the second shelf row to the third, so they scroll a row less; scrolled
        // for the focused row itself, they opened on the first row cut in half under the pill.
        val target = with(density) { metrics.scrollFor(if (inGrid) focusedRow - (metrics.shelfRows - 1) else 0, rows).toPx() }
        val lift by animateFloatAsState(
            with(density) { if (focusedRow > 0 && !inGrid) metrics.shelfLift.toPx() else 0f },
            if (reduced) snap() else spring(dampingRatio = 1f, stiffness = 280f), label = "shelf-lift",
        )
        val maxScroll = with(density) { metrics.scrollFor(rows - 1, rows).toPx() }.coerceAtLeast(0f)
        LaunchedEffect(maxScroll) { scroll.updateBounds(0f, maxScroll) }
        val pastShelf by remember(collapsePx) { derivedStateOf { scroll.value > collapsePx / 2 } }
        val decay = rememberSplineBasedDecay<Float>()
        LaunchedEffect(target, dragged) {
            if (dragged) return@LaunchedEffect
            if (reduced) scroll.snapTo(target)
            // A spring keeps its velocity when a quick second press retargets it mid-flight. At 170
            // a row took about 300 ms to get 90% of the way and half a second to settle.
            else scroll.animateTo(target, spring(dampingRatio = 1f, stiffness = 280f))
        }

        // The folder open, or under its app's menu.
        val openFolder = (overlay as? Overlay.Folder)?.id ?: (overlay as? Overlay.AppMenu)?.inFolder
        val folderOpen = overlay is Overlay.HiddenApps || openFolder != null
        val ccOpen = overlay is Overlay.ControlCenter
        var sheetIn by remember { mutableStateOf(false) }
        var sheetWarm by remember { mutableStateOf(false) }
        // Folders open on Home frosted, as on tvOS; Control Center keeps Home sharp, dimmed.
        val backdropFrost by animateFloatAsState(
            if (folderOpen) 1f else 0f,
            tween(320, easing = Motion.EaseOut), label = "backdrop-frost",
        )
        val backdropVeil by animateFloatAsState(
            if (ccOpen) 1f else 0f,
            tween(320, easing = Motion.EaseOut), label = "backdrop-veil",
        )
        // With Control Center open, Home is still: once the panel is in, a picture of it stands in
        // for it, one full-screen image a frame instead of every tile again under the panel
        // (MiTV-MFTR0: GPU 18-21 ms a frame drawing Home under it, as against 9-15 ms frosted).
        // Taken again now and then for the clock. Not before Android 7: on Android 6 the picture's
        // ImageReader surface leaves a RenderNode that the finalizer thread later frees together
        // with GPU layers, and hwui aborts with "Wrong thread!" (MiTV4-ANSM0, Android 6.0.1,
        // issue #466: the reporter rebuilt the same steps in a small app and got the same 16 frames).
        val homeLayer = rememberGraphicsLayer()
        var homeShot by remember { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(ccOpen) {
            homeShot = null
            if (!ccOpen || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return@LaunchedEffect
            delay(HOME_SHOT_AFTER_MS)
            while (true) {
                homeShot = runCatching { homeLayer.toImageBitmap() }.getOrNull()
                delay(HOME_SHOT_EVERY_MS)
            }
        }

        val ambientApp = apps.getOrNull(focusedIndex) ?: apps.firstOrNull()

        val dragState = rememberDraggableState { delta ->
            dragged = true
            scope.launch(start = CoroutineStart.UNDISPATCHED) { scroll.snapTo(scroll.value - delta) }
        }
        // A moving wallpaper's film, under everything; it moves only on the shelf with nothing
        // over it (in two rows the second row too, which is on screen over the same film, issue
        // #810); the owner's videos there only once Home is still unless set to keep moving.
        // Beside Home rather than in it: Home is not drawn (alpha 0) under a whole frost or once
        // its picture stands in under Control Center, and a SurfaceView in a layer that is not
        // drawn cuts no hole in the window, so the paused film showed as the window's black
        // (Xiaomi MiTV-MFTR0, the aerial wallpaper under Control Center).
        val filmMoves = vm.filmFront && overlay == Overlay.None && !vm.screensaverVisible && !pastShelf &&
            !inGrid && moving == null
        val sourceInput = Wallpapers.sourceInput
        // Nothing over Home blurs it: a paused film stays (why: at LiveWallpaper below).
        val filmStandsIn = overlay == Overlay.None || overlay is Overlay.ControlCenter || overlay is Overlay.Wallpaper ||
            (overlay is Overlay.AppMenu && overlay.inFolder == null)
        when {
            Wallpapers.selectedId == Wallpapers.LIVE -> LiveWallpaper(
                moving = filmMoves,
                keep = vm.filmFront && (!vm.screensaverVisible || filmSaver),
                cover = filmCover,
                hold = filmSaver && vm.filmFront,
                // Control Center, Personalize and an app's menu on Home leave Home sharp (dimmed,
                // above the sheet, beside the menu): the paused film stays. Swapped for its picture
                // there, the background jumped (another plane and processing; a key frame a second
                // off where the exact frame did not decode, Xiaomi MiTV-MFTR0 and TCL MT5879); the
                // menu flashed so as it opened (the owner, MFTR0). In a folder Home is frosted.
                standIn = filmStandsIn,
                panel = overlay != Overlay.None,
                otherFormatSaver = vm.screensaverVisible && !filmSaver && Wallpapers.screensaver == ScreensaverMode.Aerial &&
                    !AerialFormats.screensaverCarriesWallpaper(appContext),
                lastInputAt = { vm.lastInputAt },
                live = liveFilm,
            )
            // Anew for each choice, so another of the owner's videos does not keep the last one's player.
            Wallpapers.isVideo() -> key(Wallpapers.selectedId) {
                VideoWallpaper(
                    moving = filmMoves,
                    keep = vm.filmFront && (!vm.screensaverVisible || filmSaver),
                    hold = filmSaver && vm.filmFront,
                    home = homeVideo,
                    standIn = filmStandsIn,
                    lastInputAt = vm.lastInputAt,
                    cover = filmCover,
                    behindApp = !vm.filmFront,
                )
            }
            sourceInput != null -> SourceWallpaper(sourceInput, show = vm.resumed && !vm.screensaverVisible, cover = filmCover)
        }
        Box(
            Modifier
                .fillMaxSize()
                .draggable(
                    dragState,
                    Orientation.Vertical,
                    enabled = gridEnabled && rows > 1,
                    onDragStopped = { velocity ->
                        if (!reduced) scroll.animateDecay(-velocity, decay)
                    },
                )
                // While moving an app, a tap on empty space ends the move, as OK does.
                .pointerInput(Unit) { detectTapGestures { if (movingNow != null) vm.endMove() } }
                .graphicsLayer {
                    // Under the frost once it is whole, Home is not drawn at all: on MFTR0 the
                    // picture, plate and tiles cost the GPU as much again as the panel over them.
                    alpha = if (backdropFrost > 0.999f || homeShot != null) 0f else 1f
                }
                // Recorded while Control Center is open, for the picture that stands in for Home.
                .drawWithContent {
                    if (ccOpen && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        homeLayer.record { this@drawWithContent.drawContent() }
                        drawLayer(homeLayer)
                    } else if (sheetIn) {
                        // Under Personalize's settled sheet, only what shows above it.
                        clipRect(bottom = size.height - SheetCovers.toPx()) { this@drawWithContent.drawContent() }
                    } else drawContent()
                },
        ) {
            // The glass plate under the top row (tvOS 26), the row's full width however few apps
            // there are, which sit at its left: sized to three apps it read as a stray panel (#284).
            // In two rows it holds both: under the top row alone, the second sat beside it on the
            // bare wallpaper, as if left off the shelf.
            val plate = if (apps.isEmpty() || !hasPlate) null else with(density) {
                val second = if (metrics.shelfRows > 1) metrics.shelfSecond else 0.dp
                Rect(
                    (metrics.margin - PlatePadH).toPx(),
                    (metrics.topRowY - PlatePadV).toPx(),
                    (metrics.margin + metrics.pitchX * (columns - 1) + metrics.tileW + PlatePadH).toPx(),
                    (metrics.topRowY + metrics.tileH + PlatePadV + metrics.firstGap + second).toPx(),
                )
            }
            // Another wallpaper chosen: nothing shows through until the new one has a frame.
            LaunchedEffect(Wallpapers.selectedId) { filmCover.snapTo(0f) }
            // The aerial film's own glass for the grid: the one playing or stopped, else the one
            // the picture was taken from.
            val pictureClip = remember(Wallpapers.backdrop) { Wallpapers.livePictureSpot()?.first }
            val clip = if (Wallpapers.selectedId == Wallpapers.LIVE) liveFilm.film?.id ?: pictureClip else null
            LaunchedEffect(clip) { Wallpapers.showClipGlass(clip) }
            val clipGlass = Wallpapers.clipGlass?.takeIf { it.first == clip }?.second
            HomeBackground(
                // The chosen wallpaper; in "Follow App" mode, light from the focused app.
                backdrop = Wallpapers.backdrop ?: ambientApp?.art?.backdrop,
                frosted = Wallpapers.frosted.takeIf { Wallpapers.backdrop != null },
                plate = plate,
                scroll = { scroll.value },
                collapse = collapsePx,
                cover = { filmCover.value },
                // An input's picture gets the shade a picture has; in the grid it gives way to the
                // glass, as any wallpaper does (it cannot be blurred). Not a film, moving or not
                // (the owner, 2026-10-29): since the aerial wallpaper keeps moving under keys
                // (Wallpapers.keepsMoving) it was still shaded here, a full-screen draw each frame
                // (Xiaomi MiTV-MFTR0: GPU 9.8 -> 6.9 ms a frame moving focus over the film).
                shadeMoving = sourceInput != null,
                filmStopped = { filmStopped },
                toSaver = { toSaver.value },
                filmGlass = (Wallpapers.filmFrost ?: Wallpapers.frosted).takeIf { Wallpapers.moves() && Wallpapers.backdrop != null },
                saverImage = saverImage,
                saverReveal = { saverReveal.value },
                saverPush = { saverPush.value },
                lift = { lift },
                wallpaper = Wallpapers.selectedId,
                glassAwaitsStop = Wallpapers.selectedId == Wallpapers.LIVE && clipGlass == null,
                gridGlass = clipGlass,
            )

            // Before Android 7 there is no picture of Home under Control Center (issue #466), so the
            // GPU draws every tile again under the panel each frame. Lite there once let the clock and
            // tiles fade out under the veil; Home looked gone behind the panel (issue #823), so they stay.
            Box(
                Modifier
                    .fillMaxSize()
                    .offset { IntOffset(0, -scroll.value.roundToInt()) },
            ) {
                val clockPlace = AerialSettings.clockPlace
                // The large clock stands over the top row; the small one is the time and weather in
                // one line, top left or beside the pill, so the wallpaper keeps the screen. The
                // full-screen one (§满屏) is the time alone, centred over the icons at half
                // strength, clear of them by its own gap. The Hidden style keeps the clock too:
                // the owner wants the time on the wallpaper, just as the time settings say.
                if (clockFull) {
                    FullClock(
                        now = now,
                        topRowY = metrics.topRowY,
                        modifier = Modifier
                            .diagLayer("clock")
                            .fillMaxWidth()
                            .padding(horizontal = metrics.margin)
                            .graphicsLayer {
                                // Half strength, as the owner asked: the wallpaper reads through
                                // the biggest type on the screen.
                                alpha = 0.50f * (1f - (scroll.value / collapsePx * 1.6f).coerceIn(0f, 1f))
                                compositingStrategy = CompositingStrategy.ModulateAlpha
                            },
                    )
                } else if (largeClock) {
                    ShelfClock(
                        now = now,
                        place = clockPlace,
                        modifier = Modifier
                            .diagLayer("clock")
                            .align(
                                when (clockPlace) {
                                    ClockPlace.Left -> Alignment.TopStart
                                    ClockPlace.Center -> Alignment.TopCenter
                                    ClockPlace.Right -> Alignment.TopEnd
                                },
                            )
                            .padding(horizontal = metrics.margin)
                            .padding(top = metrics.clockY)
                            // No offscreen buffer while it fades: it would be the clock's capBox, and the
                            // Chinese, the icon and the shadows above and below it were cut off (#727).
                            .graphicsLayer {
                                // On the shelf the clock keeps the icons' 70%, so it sits in the
                                // wallpaper the way they do instead of fighting them for the eye.
                                // It comes up to full as the screensaver takes it over, where it is
                                // the only thing on the screen — and so the handoff to the
                                // screensaver's own clock, at full, shows no step.
                                val restAlpha = 0.70f + 0.30f * clockToSaver.value
                                alpha = restAlpha * (1f - (scroll.value / collapsePx * 1.6f).coerceIn(0f, 1f))
                                compositingStrategy = CompositingStrategy.ModulateAlpha
                            },
                        toSaver = { clockToSaver.value },
                        saving = vm.screensaverVisible,
                        away = carrying,
                    )
                } else if (clockPlace != ClockPlace.Right) {
                    // The small clock: in a corner, or in the middle of the top edge, where it
                    // keeps the same line under the middle of the screen (the owner: 居中).
                    CornerClock(
                        now = now,
                        modifier = Modifier
                            .then(if (clockPlace == ClockPlace.Center) Modifier.align(Alignment.TopCenter) else Modifier)
                            .diagLayer("clock")
                            .padding(
                                start = if (clockPlace == ClockPlace.Center) 0.dp else metrics.margin,
                                top = 28.dp,
                            )
                            .graphicsLayer {
                                alpha = 1f - (scroll.value / collapsePx * 1.6f).coerceIn(0f, 1f)
                                compositingStrategy = CompositingStrategy.ModulateAlpha
                            },
                        toSaver = { toSaver.value },
                        saving = vm.screensaverVisible,
                    )
                }

                // The rows below the shelf stay out of sight until Home scrolls to them: in one row
                // unless set to peek; in two rows always, as the second row already shows there are more.
                val hiddenFrom = when {
                    bar -> Int.MAX_VALUE
                    !oneRow -> columns * 2
                    !peek -> columns
                    else -> Int.MAX_VALUE
                }
                // The dock's bar of glass, under its row and as wide as the row has become. Drawn
                // here rather than as Home's shelf plate: it moves with every focus change, and the
                // plate is baked to a picture at one size. The wheel has no bar: its icons ring the
                // wallpaper itself, and a slab of glass under a turning row only boxed it in.
                if (dock && apps.isNotEmpty()) {
                    // The bar goes with its icons when the screensaver carries Home away: the tiles
                    // faded out and left the glass behind, a lit slab with nothing on it.
                    Canvas(
                        Modifier
                            .fillMaxSize()
                            .diagLayer("dock")
                            .graphicsLayer {
                                alpha = 1f - (toSaver.value * 2f).coerceIn(0f, 1f)
                                compositingStrategy = CompositingStrategy.ModulateAlpha
                            },
                    ) {
                        val r = dockBar
                        val corner = CornerRadius((r.height * 0.30f).coerceAtMost(r.height / 2f))
                        val topLeft = Offset(r.left, r.top)
                        val size = Size(r.width, r.height)
                        // Its own shade on the wallpaper, so the bar lifts off the picture.
                        drawRoundRect(
                            color = Color.Black.copy(alpha = 0.18f),
                            topLeft = Offset(r.left, r.top + 8.dp.toPx()),
                            size = size,
                            cornerRadius = corner,
                        )
                        // Glass, not a haze: the picture that lies under the bar, frosted (the
                        // wallpaper's own blurred copy) and lifted to milk, so a photo reads
                        // through it dimmed and soft instead of sharp under a white film. The bar
                        // moves with every focus change, so it is drawn, not baked to a plate.
                        val frost = (if (Wallpapers.moves()) Wallpapers.filmFrost else null) ?: Wallpapers.frosted
                        if (frost != null) {
                            drawFrostRect(
                                frost = frost,
                                rect = r,
                                root = IntSize(this.size.width.roundToInt(), this.size.height.roundToInt()),
                                corner = corner.x,
                                tint = DockGlassTint,
                                milk = 0.10f,
                                edge = 1.2.dp.toPx(),
                            )
                        } else {
                            // "Follow App": there is no frosted picture, so the plain glass, a
                            // lighter fill and a hairline where the light catches its rim.
                            drawRoundRect(
                                brush = Brush.verticalGradient(
                                    listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.10f)),
                                    startY = r.top, endY = r.bottom,
                                ),
                                topLeft = topLeft,
                                size = size,
                                cornerRadius = corner,
                            )
                            drawRoundRect(
                                color = Color.White.copy(alpha = 0.28f),
                                topLeft = topLeft,
                                size = size,
                                cornerRadius = corner,
                                style = Stroke(width = 1.dp.toPx()),
                            )
                        }
                    }
                }
                // The Hidden style draws no tile on Home at all: the board (HiddenDockPanel) is the
                // only place its apps appear, and it is not made of these.
                if (!hidden) apps.forEachIndexed { index, app ->
                    key(app.key) {
                        val (x, y) = when {
                            // The bar's own order: groups last, as a dock keeps them at its right.
                            dock -> dockLefts.getOrElse(dockPlace.getOrElse(index) { index }) { 0.dp } to dockM.iconTop
                            // The wheel's own x comes from the sliding row, read at layout below.
                            wheel -> 0.dp to wheelM.iconTop
                            else -> metrics.slot(index, onTop = !inGrid)
                        }
                        val slotPx = with(density) { IntOffset(x.roundToPx(), y.roundToPx()) }
                        val slot = animateIntOffsetAsState(
                            slotPx,
                            spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow),
                            label = "slot",
                        )
                        val saverFade = { ((toSaver.value - 0.05f * (index % columns)) / 0.6f).coerceIn(0f, 1f) }
                        AppTile(
                            app = app,
                            width = if (dock) dockM.side else if (wheel) wheelM.side else metrics.tileW,
                            height = if (dock) dockM.side else if (wheel) wheelM.side else metrics.tileH,
                            focusRequester = requester(app.key),
                            enabled = gridEnabled,
                            moving = moving == app.key,
                            opacity = { 1f - saverFade() },
                            onFocused = {
                                focusedKey = app.key
                                lastFocusedIndex = index
                                pillFocused = false
                            },
                            onClick = { bounds ->
                                val mover = moving
                                if (mover == null) {
                                    val folder = app.folder
                                    if (folder == null) onLaunch(app, bounds)
                                    else {
                                        // Closing the folder comes back to its tile.
                                        focusAfterOverlay = app.key
                                        indexAfterOverlay = index
                                        vm.overlay = Overlay.Folder(folder.id)
                                    }
                                }
                                // While moving, tapping a tile puts the app there; tapping the app
                                // itself is done.
                                else if (mover == app.key) vm.endMove()
                                else {
                                    vm.moveBy(index - apps.indexOfFirst { it.key == mover })
                                    runCatching { requester(mover).requestFocus() }
                                }
                            },
                            onLongPress = { bounds ->
                                // Nothing to rearrange in Kids and Elders mode (#1013), nor on a locked Home.
                                if (moving == null && !Restricted.active && !vm.noteHomeLocked()) {
                                    focusAfterOverlay = app.key
                                    indexAfterOverlay = index
                                    vm.overlay = Overlay.AppMenu(app.key, bounds ?: Rect.Zero)
                                }
                            },
                            modifier = Modifier
                                .diagLayer("tiles")
                                // Lifted on the shelf with the plate; the clock above stays put.
                                .offset {
                                    // The wheel slides as one row: each icon stands its distance
                                    // from the middle, and the middle is where the row rests. Read
                                    // here, at layout, so the slide costs no recomposition. The
                                    // distance is taken the short way round the ring, so the apps
                                    // past either end come in from the other side instead of the
                                    // row running out: an app only ever jumps while it is off the
                                    // screen, half a ring away.
                                    val base = if (wheel) {
                                        val n = apps.size
                                        var d = index - wheelPos.value
                                        if (n > 0) d -= n * kotlin.math.round(d / n)
                                        IntOffset(
                                            (wheelM.cx + wheelM.pitch * d - wheelM.side / 2).roundToPx(),
                                            wheelM.iconTop.roundToPx(),
                                        )
                                    } else slot.value
                                    base - IntOffset(0, lift.roundToInt())
                                }
                                // Into the screensaver, the apps go left to right, sinking a little.
                                // Opacity lives in the tile's own layers: another layer around it dropped
                                // the focus zoom and rounded clip on Android 9 (issue #725).
                                .offset { IntOffset(0, (saverFade() * 18.dp.toPx()).roundToInt()) }
                                .then(
                                    if (index >= hiddenFrom) Modifier.graphicsLayer {
                                        alpha = (scroll.value / collapsePx * 2.5f).coerceIn(0f, 1f)
                                        // No offscreen buffer: it would clip the focus frame to the tile.
                                        compositingStrategy = CompositingStrategy.ModulateAlpha
                                    } else Modifier,
                                ),
                            rowEnd = when {
                                // The wheel sets its own four ways out (nav below).
                                wheel -> false
                                dock -> dockPlace.getOrElse(index) { index } == apps.lastIndex
                                else -> index % columns == columns - 1 || index == apps.lastIndex
                            },
                            // On the glass plate only with every name shown (an earlier build showed
                            // the focused one alone there, but it sat oddly on the glass). The wheel
                            // names the middle one alone: seven icons a slot apart leave no room.
                            // With every name on, only the slots the ring shows get one — a name
                            // would else hang alone at the screen's edge over a tile faded out.
                            showName = if (wheel) (AerialSettings.showAllNames || index == focusedIndex) &&
                                kotlin.math.abs(ringDistance(index, focusedIndex, apps.size)) <= WheelSlots / 2
                            else plate == null || index >= columns * metrics.shelfRows || plateNames,
                            nameOnPlate = plate != null && index < columns * metrics.shelfRows,
                            square = bar,
                            growUp = bar,
                            nameAbove = bar,
                            focusScale = when {
                                dock -> dockM.zoom(0)
                                wheel -> WHEEL_FOCUS_SCALE
                                else -> FOCUSED_SCALE
                            },
                            restScale = when {
                                wheel -> wheelRest(kotlin.math.abs(ringDistance(index, focusedIndex, apps.size)))
                                dock && dockFocusAt >= 0 ->
                                    dockM.zoom(kotlin.math.abs(dockPlace.getOrElse(index) { index } - dockFocusAt))
                                else -> 1f
                            },
                            // The wheel steps down from the middle: solid at the focus, 50 a step
                            // either side, then 20 and 12 for the pair past those, and nothing
                            // further out — the ring shows seven at a time.
                            forcedAlpha = if (wheel) wheelAlpha(kotlin.math.abs(ringDistance(index, focusedIndex, apps.size)))
                            else null,
                            // The label fades with its own icon: seven names at full strength
                            // would read as one row of text, not seven labels.
                            nameFollowsIcon = wheel,
                            nav = if (!wheel) null else {
                                {
                                    val n = apps.size
                                    // The row follows the key: Left brings the icon on its left
                                    // into the middle, so the row slides right; Right brings the one
                                    // on its right and slides the row left. Round the ring, so the
                                    // last app and the first are one press apart.
                                    left = if (n > 0) requester(apps[(index - 1).mod(n)].key) else FocusRequester.Cancel
                                    right = if (n > 0) requester(apps[(index + 1).mod(n)].key) else FocusRequester.Cancel
                                    up = pillRequester
                                    // Down takes the one farthest from the middle: the wheel turned
                                    // three slots round, the far end of the seven it shows.
                                    down = if (n > 1) requester(apps[(index + WheelSlots / 2).mod(n)].key)
                                    else FocusRequester.Cancel
                                }
                            },
                        )
                    }
                }
            }
        }

        homeShot?.let { shot -> Canvas(Modifier.fillMaxSize()) { drawImage(shot) } }
        PanelBackdrop(Wallpapers.frosted.takeIf { Wallpapers.backdrop != null }, { backdropFrost }, { backdropVeil })

        // The Hidden style keeps the focus on nothing: this invisible box holds the remote while the
        // screen is the wallpaper alone, so Up and Down still reach Home (a Compose window with no
        // focused node hands the keys straight to the Activity and they never come back here).
        if (hidden) {
            Box(
                Modifier
                    .fillMaxSize()
                    .focusRequester(idleRequester)
                    .focusable(),
            )
        }

        // The Hidden style's board: Down slides it up from the bottom edge, a folder opened in it
        // swaps its own name and apps in place, and Back steps out of one then out of the other.
        if (hidden) {
            val folderId = hiddenFolderId
            HiddenDockPanel(
                visible = hiddenDock && !vm.screensaverVisible,
                apps = apps,
                folderName = folderId?.let { id -> apps.firstOrNull { it.folder?.id == id }?.label },
                folderApps = folderId?.let { vm.folderApps[it] }.orEmpty(),
                enabled = gridEnabled,
                focusedKey = focusedKey,
                // The app being rearranged: its tile wiggles and the board slides the tiles into
                // their new slots rather than reflowing them with a cut.
                movingKey = moving,
                // Each refused Up inside a folder bumps the focused tile up.
                blockedUp = hiddenUpBump,
                requester = { key -> requester(key) },
                pillFocus = pillRequester,
                onFocused = { key, index ->
                    focusedKey = key
                    lastFocusedIndex = index
                    pillFocused = false
                },
                onLaunch = onLaunch,
                // Opened where it stands, in this glass: the full-screen folder is not part of this
                // style (the owner: 「打开文件夹就在本玻璃底板内原地打开…不再另外打开」).
                onOpenFolder = { app -> hiddenFolderId = app.folder?.id },
                // The app's own menu, as on Home's tiles — rearrange, group, hide — and the move
                // that follows it works over the board: the moving key is one of the board's
                // tiles, and Home's move handling moves the app in the arrangement under it.
                onLongPress = { app, bounds ->
                    if (moving == null && !Restricted.active && !vm.noteHomeLocked()) {
                        focusAfterOverlay = app.key
                        indexAfterOverlay = apps.indexOfFirst { it.key == app.key }.coerceAtLeast(0)
                        vm.overlay = Overlay.AppMenu(app.key, bounds ?: Rect.Zero)
                    }
                },
                // A tap on another tile while moving trades the two places, as Home's grid does;
                // a tap on the moving app itself ends the move (OK does too).
                onMovingTap = { app ->
                    val mover = moving
                    if (mover != null) {
                        if (mover == app.key) vm.endMove()
                        else {
                            val here = apps.indexOfFirst { it.key == app.key }
                            val from = apps.indexOfFirst { it.key == mover }
                            if (here >= 0 && from >= 0) vm.moveBy(here - from)
                            runCatching { requester(mover).requestFocus() }
                        }
                    }
                },
            )
        }

        // The pill holds only the network and Control Center. The time joins it once the clock
        // above the top row has scrolled away, so the screen never shows the time twice. The small
        // clock on the right puts the time and weather just left of the pill.
        Row(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 28.dp, end = metrics.margin - 14.dp)
                .graphicsLayer {
                    // Under the Hidden style the whole row waits at nothing until Up calls it up.
                    alpha = (if (overlay is Overlay.ControlCenter) 0f else 1f) *
                        (if (hidden) pillAlpha.value else 1f)
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!AerialSettings.largeClock && AerialSettings.clockPlace == ClockPlace.Right) {
                CornerClock(
                    now = now,
                    modifier = Modifier
                        .diagLayer("clock")
                        .padding(end = 14.dp)
                        .graphicsLayer {
                            alpha = 1f - (scroll.value / collapsePx * 1.6f).coerceIn(0f, 1f)
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                        },
                    toSaver = { toSaver.value },
                    saving = vm.screensaverVisible,
                )
            }
            StatusPill(
                now = now,
                network = vm.network,
                showTime = if (dragged) pastShelf else inGrid,
                enabled = gridEnabled && moving == null,
                focusRequester = pillRequester,
                modifier = Modifier
                    .diagLayer("pill")
                    // Down goes back to the top-row tile Up came from, not to the nearest one,
                    // which under the pill is the last of the row (issue #703). Left and right do
                    // too: an elder who strayed up here pressed them and nothing moved (#1215).
                    // Without that tile, left is the row's last and right its first.
                    .focusProperties {
                        val key = focusedKey
                        val rowEnd = if (bar) apps.lastIndex else minOf(apps.size, columns) - 1
                        if (key != null && apps.indexOfFirst { it.key == key } in 0..rowEnd) {
                            down = requester(key); left = requester(key); right = requester(key)
                        } else if (rowEnd >= 0) {
                            left = requester(apps[rowEnd].key); right = requester(apps[0].key)
                        }
                    }
                    .graphicsLayer {
                        alpha = 1f - (toSaver.value * 2f).coerceIn(0f, 1f)
                        // No offscreen buffer: it would cut the focused pill's 8% growth to a box.
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    },
                onFocused = { gained ->
                    // The window hands its first focus to the top-most control; the top row is where Home starts.
                    if (gained) {
                        // Hidden: the focus reached the pill from the wallpaper (Up) or from the
                        // board's first row (nav.up) — the pill fades in as it takes it, and the
                        // board steps out of its way, sliding back down under the edge the way it
                        // came up (the owner: 「玻璃地板下缩（就像出来是上移），不是闪没」).
                        if (hidden) {
                            hiddenPillShown = true
                            pillFocused = true
                            if (hiddenDock) {
                                hiddenDock = false
                                hiddenFolderId = null
                            }
                        } else if (focusedKey == null) {
                            if (apps.isNotEmpty()) focusApp(0)
                        } else {
                            pillFocused = true
                        }
                    }
                },
                onClick = {
                    // A tap focuses the pill and opens Control Center in the same frame, before
                    // onFocused hears of it: note it here so focus comes back to the pill.
                    pillFocused = true
                    vm.overlay = Overlay.ControlCenter()
                },
            )
        }

        // Once per process, while Home is idle: Control Center, then Personalize, drawn for a few
        // frames, nearly transparent, so that their first opening is not their first draw (see
        // ControlCenter, WallpaperSheet).
        var ccWarm by remember { mutableStateOf(false) }
        LaunchedEffect(vm.loaded) {
            if (!vm.loaded || ControlCenterWarmed.done) return@LaunchedEffect
            while (true) {
                delay(CC_WARM_AFTER_MS)
                val idle = SystemClock.uptimeMillis() - vm.lastInputAt > CC_WARM_AFTER_MS
                if (idle && vm.overlay == Overlay.None && !vm.screensaverVisible && vm.resumed) break
            }
            ControlCenterWarmed.done = true
            ccWarm = true
            repeat(3) { withFrameNanos { } }
            ccWarm = false
            // A frame apart, so that the two first draws do not land in the same frame.
            withFrameNanos { }
            if (vm.overlay != Overlay.None) return@LaunchedEffect
            sheetWarm = true
            repeat(3) { withFrameNanos { } }
            sheetWarm = false
        }
        ControlCenter(
            visible = overlay is Overlay.ControlCenter,
            page = (overlay as? Overlay.ControlCenter)?.page ?: CcPage.Main,
            vm = vm,
            onOpen = { vm.overlay = Overlay.None; onOpen(it) },
            onClose = { vm.overlay = Overlay.None },
            warm = ccWarm,
            onShelf = !pastShelf,
        )

        FolderView(
            name = openFolder?.let { id -> apps.firstOrNull { it.folder?.id == id }?.label },
            apps = openFolder?.let { vm.folderApps[it] }.orEmpty(),
            visible = openFolder != null,
            enabled = overlay is Overlay.Folder,
            movingKey = moving?.takeIf { vm.movingInFolder != null },
            gap = metrics.gap,
            // Five across, whatever Home's row holds: six crowd the plate and squeeze the last
            // tile, and a folder is usually a handful of apps anyway.
            columns = 5,
            onLaunch = onLaunch,
            onLongPress = { app, bounds ->
                if (!vm.noteHomeLocked()) vm.overlay = Overlay.AppMenu(app.key, bounds ?: Rect.Zero, inFolder = openFolder)
            },
            onDismiss = { vm.overlay = Overlay.None },
        )

        val menu = overlay as? Overlay.AppMenu
        // A menu opened in a folder closes back to the folder.
        fun closeMenu() { vm.overlay = menu?.inFolder?.let { Overlay.Folder(it) } ?: Overlay.None }
        AppMenu(
            app = menu?.let { vm.entry(it.key) },
            anchor = menu?.anchor,
            inFolder = menu?.inFolder != null,
            folders = vm.folders(),
            onMove = { vm.startMove(it, menu?.inFolder) },
            onHide = { key ->
                closeMenu()
                vm.hide(key)
            },
            onInfo = { closeMenu(); onAppInfo(it) },
            onUninstall = { closeMenu(); onUninstall(it) },
            onUnpin = { key ->
                closeMenu()
                vm.entry(key)?.source?.let { vm.setSourcePinned(it, false) }
            },
            onResetIcon = { key ->
                closeMenu()
                vm.resetIcon(key)
            },
            onBootTarget = { key ->
                closeMenu()
                val entry = vm.entry(key)
                val source = entry?.source
                when {
                    source != null -> vm.chooseBootTarget(sourceId = source.id.takeIf { it != AerialSettings.bootSource })
                    entry != null -> vm.chooseBootTarget(pkg = key.takeIf { it != AerialSettings.bootApp })
                }
            },
            onSourceDevice = { id, device ->
                closeMenu()
                SourceLooks.chooseDevice(id, device)
            },
            onToFolder = { key, id ->
                val folderKey = vm.putInFolder(key, id)
                // From Home, focus goes to the folder the app went into.
                if (menu?.inFolder == null) focusAfterOverlay = folderKey
                closeMenu()
            },
            onOutOfFolder = { key ->
                vm.takeOutOfFolder(key)
                closeMenu()
            },
            onRenameFolder = { id, name ->
                vm.renameFolder(id, name)
                closeMenu()
            },
            onUngroup = { id ->
                vm.ungroup(id)
                closeMenu()
            },
            onDismiss = { closeMenu() },
        )

        // Over an open folder too, where an app can move as well (issue #1344).
        AnimatedVisibility(
            visible = moving != null,
            enter = fadeIn(tween(200, easing = Motion.EaseOut)) + slideInVertically(tween(260, easing = Motion.EaseOut)) { it / 2 },
            exit = fadeOut(tween(150, easing = Motion.EaseOut)) + slideOutVertically(tween(150, easing = Motion.EaseOut)) { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
        ) {
            // Screens that can be touched also say how to move with a finger.
            val touch = LocalConfiguration.current.touchscreen != Configuration.TOUCHSCREEN_NOTOUCH
            BasicText(
                stringResource(if (touch) R.string.move_hint_touch else R.string.move_hint),
                style = Type.Body,
                modifier = Modifier
                    .liquidGlass(20.dp)
                    .height(40.dp)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }

        KidsSetupSheet(
            visible = overlay is Overlay.Kids,
            candidates = vm.kidsCandidates,
            onStart = {
                if (!Restricted.enabled) Restricted.chooseEnabled(true)
                Restricted.start()
                vm.overlay = Overlay.None
                vm.homeResetPending = true
                KidsWatch.poke()
            },
            onDismiss = { vm.overlay = Overlay.Wallpaper },
        )

        // Locked mid-panel (the day's time ran out with Control Center open): the lock is all there is.
        LaunchedEffect(kidsLock != null) {
            if (kidsLock != null && vm.overlay !is Overlay.Pin) vm.overlay = Overlay.None
        }
        KidsLockScreen(
            lock = kidsLock,
            pinOpen = overlay is Overlay.Pin,
            onParent = { right -> vm.overlay = Overlay.Pin(PinMode.Verify) { vm.overlay = Overlay.None; right() } },
            onLeave = { Restricted.suspend(); KidsWatch.poke() },
        )

        HiddenAppsSheet(
            visible = overlay is Overlay.HiddenApps,
            apps = vm.hiddenApps,
            onUnhide = { key ->
                vm.unhide(key)
                if (vm.hiddenApps.isEmpty()) vm.overlay = Overlay.Wallpaper
            },
            // Hidden apps are reached from Personalization, and Back returns there.
            onDismiss = { vm.overlay = Overlay.Wallpaper },
        )

        WallpaperSheet(
            visible = overlay is Overlay.Wallpaper,
            onSettled = { sheetIn = it },
            warm = sheetWarm,
            // Back (or a tap beside the sheet) leaves for Home, not for Control Center: once the
            // settings are made the viewer wants the screen again, and Control Center in between
            // was a stop nobody asked for (the owner, 2026-11-17: 「大部分时间都应该立刻回到主页面，
            // 回控制中心没意义」). The pages reached from here — hidden apps, Kids — still come
            // back to this sheet.
            onDismiss = { vm.overlay = Overlay.None },
            hiddenCount = vm.hiddenApps.size,
            onHiddenApps = { vm.whenUnlocked { vm.overlay = Overlay.HiddenApps } },
            // Locking needs nothing; unlocking, the PIN where one is set.
            onHomeLock = { on ->
                if (on == AerialSettings.homeLocked) Unit
                else if (on) AerialSettings.chooseHomeLocked(true)
                else vm.whenUnlocked { AerialSettings.chooseHomeLocked(false); vm.overlay = Overlay.Wallpaper }
            },
            onKids = vm::setUpKidsMode,
            onPin = { on ->
                if (on) vm.overlay = Overlay.Pin(PinMode.Create) { vm.overlay = Overlay.Wallpaper }
                // Kids mode cannot be left without a PIN: it goes with it.
                else vm.whenUnlocked { AerialSettings.choosePin(null); Restricted.chooseEnabled(false); vm.overlay = Overlay.Wallpaper }
            },
        )

        val pin = overlay as? Overlay.Pin
        PinPad(
            visible = pin != null,
            mode = pin?.mode,
            onDone = { pin?.onDone?.invoke() },
            onCancel = { vm.overlay = Overlay.None },
        )

        NoticeBanner(vm.notice, Modifier.align(Alignment.TopCenter).padding(top = 24.dp), vm.noticeGlyph)

        Screensaver(visible = vm.screensaverVisible, apps = apps, now = now, skips = vm.screensaverSkips, carry = when {
                filmSaver -> if (Wallpapers.isVideo()) SaverCarry.Video(homeVideo) else SaverCarry.Film(liveFilm)
                stillCarry != null -> stillCarry
                else -> null
            },
        )
    }
}
