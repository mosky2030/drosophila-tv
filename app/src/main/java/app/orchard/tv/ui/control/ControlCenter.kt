package app.orchard.tv.ui.control

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
import android.view.KeyEvent as NativeKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.Image
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import app.orchard.tv.ui.components.smoothBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.orchard.tv.CcPage
import app.orchard.tv.BuildConfig
import app.orchard.tv.HomeViewModel
import app.orchard.tv.Overlay
import app.orchard.tv.R
import app.orchard.tv.apps.SourceLooks
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.settings.Restricted
import app.orchard.tv.settings.RestrictedKind
import app.orchard.tv.ui.home.kidsTimeLine
import app.orchard.tv.system.HisenseLaserPanel
import app.orchard.tv.system.HomeStatus
import app.orchard.tv.system.LaunchFallback
import app.orchard.tv.system.NetKind
import app.orchard.tv.system.SourceKind
import app.orchard.tv.system.Sounds
import app.orchard.tv.system.OutputDevice
import app.orchard.tv.system.SystemProbe
import app.orchard.tv.ui.components.Pressable
import app.orchard.tv.ui.components.consumeTaps
import app.orchard.tv.ui.components.dismissOnTap
import app.orchard.tv.ui.components.PanelBright
import app.orchard.tv.ui.components.liquidGlass
import app.orchard.tv.ui.home.MenuRow
import app.orchard.tv.ui.home.VpnBadge
import app.orchard.tv.ui.theme.Glyph
import app.orchard.tv.ui.theme.GlyphIcon
import app.orchard.tv.ui.theme.LocalLightPanel
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type
import app.orchard.tv.update.SelfCompile
import app.orchard.tv.update.Updates
import app.orchard.tv.wallpaper.PhoneServer

/**
 * Text on Control Center's white glass. The shared styles carry the dark theme's white labels, so
 * their colors are swapped for the light appearance here; a color already picked by the caller
 * (the ink of a focused control, say) passes through unchanged.
 */
@Composable
private fun CcText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) = BasicText(
    text,
    style = style,
    modifier = modifier,
    maxLines = maxLines,
    overflow = overflow,
)

/**
 * tvOS Control Center: a glass panel that drops in from the top-right corner over a blurred Home.
 * Inputs open as a second page inside the same panel, the way tvOS shows audio routes.
 */
@Composable
fun ControlCenter(
    visible: Boolean,
    page: CcPage,
    vm: HomeViewModel,
    onOpen: (android.content.Intent) -> Unit,
    onClose: () -> Unit,
    /** Draw the closed panel once, nearly transparent, so that its first opening is not its first draw (HomeScreen). */
    warm: Boolean = false,
    /** Home is on its top row: no tiles under the panel, only the wallpaper (see liquidGlass). */
    onShelf: Boolean = false,
) {
    BackHandler(enabled = visible) {
        when (page) {
            CcPage.Main -> onClose()
            CcPage.Inputs -> vm.overlay = Overlay.ControlCenter(CcPage.Main)
            CcPage.InputSettings -> vm.overlay = Overlay.ControlCenter(CcPage.Inputs)
            CcPage.Setup -> vm.overlay = Overlay.ControlCenter(CcPage.Main)
        }
    }

    // Whether Aerial is the default home, read again each time the panel opens. Recreated after a
    // standby kill, Aerial kept the "not the default home screen" it read while the firmware was
    // still waking, and the activity's polling (MainActivity) never ran: Xiaomi MiTV-AZFP0,
    // Android 9, whose log showed onCreate but no onStart for that instance (issue #892).
    LaunchedEffect(visible) { if (visible) vm.refreshPanel() }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(220, easing = Motion.EaseOut)),
        exit = fadeOut(tween(180, easing = Motion.EaseOut)),
        modifier = Modifier.fillMaxSize(),
    ) {
        // A tap outside the panel closes Control Center.
        Box(Modifier.fillMaxSize().dismissOnTap(onClose).background(Color.Black.copy(alpha = 0.28f)))
    }

    Box(Modifier.fillMaxSize().padding(top = 24.dp, end = 32.dp), contentAlignment = Alignment.TopEnd) {
        // The first opening composed the panel and then, frame after frame, gave the GPU its
        // pictures, glyphs and shaders for the first time: 55-95 ms a frame for the whole drop-in,
        // against about 26 ms on the second opening (Xiaomi MiTV-MFTR0, release build). Drawn once
        // at 1% while Home is idle, without focus, the first opening is like the second.
        if (warm && !visible) {
            Column(Modifier.graphicsLayer { alpha = 0.01f }.width(300.dp).liquidGlass(24.dp, milk = 0.30f, tint = PanelBright).padding(16.dp)) {
                MainPage(vm, takeFocus = false, poll = false, onOpen = onOpen)
            }
        }
        AnimatedVisibility(
            visible = visible,
            // Grows out of the corner where the status pill lives; leaves faster than it came.
            enter = fadeIn(tween(200, easing = Motion.EaseOut)) + scaleIn(tween(280, easing = Motion.Drawer), initialScale = 0.9f, transformOrigin = TransformOrigin(1f, 0f)),
            exit = fadeOut(tween(160, easing = Motion.EaseOut)) + scaleOut(tween(160, easing = Motion.EaseOut), targetScale = 0.95f, transformOrigin = TransformOrigin(1f, 0f)),
        ) {
            Column(
                Modifier
                    .width(300.dp)
                    .consumeTaps()
                    .liquidGlass(24.dp, seeThroughFilm = onShelf, milk = 0.30f, tint = PanelBright),
            ) {
                AnimatedContent(
                    targetState = page,
                    transitionSpec = {
                        // Fade only, and snap the panel size. Animating the panel's height re-measures
                        // both pages (and their scrollables) every frame, and sliding the new page on
                        // top of the cross-fade makes its first frame land late; on low-end TV SoCs
                        // that is felt as a stutter when opening and leaving pages.
                        (fadeIn(tween(130, easing = Motion.EaseOut)) togetherWith fadeOut(tween(110, easing = Motion.EaseOut)))
                            .using(SizeTransform { _, _ -> tween(0) })
                    },
                    label = "cc-page",
                ) { p ->
                    // Keep room for focused rows inside the fade layer so their scaled edges are not clipped.
                    Box(Modifier.padding(16.dp)) {
                        // Closing sets the page back to Main while the panel fades out; that page must
                        // not take focus from the tile Home has just put it back on.
                        CompositionLocalProvider(LocalLightPanel provides false) {
                            when (p) {
                                CcPage.Main -> if (Restricted.active) {
                                    KidsPage(vm, visible)
                                } else {
                                    MainPage(vm, takeFocus = visible, poll = visible, onOpen = onOpen)
                                }
                                CcPage.Inputs -> InputsPage(vm, visible, onOpen)
                                CcPage.InputSettings -> InputSettingsPage(vm, visible)
                                CcPage.Setup -> SetupPage(vm, visible, onOpen)                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Control Center in Kids and Elders mode (Restricted): the time, volume and brightness, the day's
 * time left, and leaving the mode with the PIN. No inputs, network, settings or Personalize.
 */
@Composable
private fun KidsPage(vm: HomeViewModel, takeFocus: Boolean) {
    val first = remember { FocusRequester() }
    val context = LocalContext.current
    val laser = remember { HisenseLaserPanel.usable(context) }
    val showsVolume = remember { OutputDevice.showsVolume(context) }
    val showsBrightness = remember { OutputDevice.showsBrightness() }
    PollBrightness(vm, laser, enabled = takeFocus)
    LaunchedEffect(Unit) {
        if (!takeFocus) return@LaunchedEffect
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    Column {
        // Same as the main page: no clock here, Home's bar has one.
        kidsTimeLine()?.let {
            CcText(it, style = Type.Callout.copy(color = Palette.SecondaryLabel))
            Spacer(Modifier.height(6.dp))
        }
        Spacer(Modifier.height(12.dp))
        // A box or stick has neither a panel nor, with its volume fixed, a sound level of its own (#1307).
        if (showsVolume) {
            VolumeRow(vm, first)
            Spacer(Modifier.height(6.dp))
        }
        if (showsBrightness) BrightnessRow(vm, laser)
        Spacer(Modifier.height(6.dp))
        MenuRow(
            Glyph.Shield,
            stringResource(if (Restricted.kind == RestrictedKind.Kids) R.string.cc_kids_leave else R.string.cc_elders_leave),
            focusRequester = first,
            onClick = vm::leaveKidsMode,
        )
    }
}

@Composable
private fun MainPage(
    vm: HomeViewModel,
    takeFocus: Boolean,
    /** The row polls the picture service, so only the copy that is on screen may do it. */
    poll: Boolean,
    onOpen: (android.content.Intent) -> Unit,
) {
    val context = LocalContext.current
    // The laser level belongs to the laser sets only (HisenseLaserPanel); every other TV keeps
    // the brightness row it had.
    val laser = remember { HisenseLaserPanel.usable(context) }
    val showsBrightness = remember { OutputDevice.showsBrightness() }
    PollBrightness(vm, laser, poll)
    // One row of four: Personalize, Settings, Inputs, Network. Personalize takes the focus when the
    // panel opens, as the first button of the row.
    val first = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }
    val inputsFocus = remember { FocusRequester() }
    val networkFocus = remember { FocusRequester() }
    // Firmwares that list their inputs late (see HomeViewModel.refreshSources): look again while
    // the page is open and still empty. One provider or TvInputManager query every 3 s.
    LaunchedEffect(Unit) {
        while (vm.sources.isEmpty()) {
            vm.refreshSources()
            delay(3000)
        }
    }
    LaunchedEffect(Unit) {
        if (!takeFocus) return@LaunchedEffect
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }

    Column {
        // No clock up here: Home's own bar shows the time at the same corner, inches above the
        // panel. Aerial's standing as Home leads the line and the connection closes it.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val (dot, text) = when (vm.homeStatus) {
                HomeStatus.Default -> Palette.Green to stringResource(R.string.cc_home_default)
                HomeStatus.Guarded -> Palette.Green to stringResource(R.string.cc_home_guarded)
                HomeStatus.NotDefault -> Palette.Orange to stringResource(R.string.cc_home_not_default)
            }
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.width(8.dp))
                CcText(
                    text, style = Type.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            val net = vm.network
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GlyphIcon(
                    when (net.kind) { NetKind.Wifi -> Glyph.Wifi; NetKind.Ethernet -> Glyph.Ethernet; NetKind.Offline -> Glyph.Offline },
                    Palette.SecondaryLabel, 14.dp, level = net.level,
                )
                CcText(
                    when (net.kind) {
                        NetKind.Wifi -> net.name ?: stringResource(R.string.net_wifi)
                        NetKind.Ethernet -> stringResource(R.string.net_ethernet)
                        NetKind.Offline -> stringResource(R.string.net_offline)
                    },
                    style = Type.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 110.dp),
                )
                if (net.vpn) VpnBadge(Palette.SecondaryLabel, fontSize = 9.sp)
            }
        }

        Spacer(Modifier.height(12.dp))
        // How Home looks (wallpaper, screensaver, focus style, hidden apps, weather) leads the row:
        // it is the one button here that stays inside Aerial, and the one most often wanted.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            RoundButton(
                Glyph.Photo, stringResource(R.string.cc_personalize),
                focusRequester = first,
            ) {
                vm.overlay = Overlay.Wallpaper
            }
            RoundButton(
                Glyph.Gear, stringResource(R.string.cc_settings),
                focusRequester = settingsFocus,
            ) {
                vm.whenUnlocked {
                    LaunchFallback.arm(SystemProbe.settingsFallbacks(context))
                    onOpen(SystemProbe.settingsIntent(context))
                }
            }
            RoundButton(
                Glyph.Input, stringResource(R.string.cc_inputs),
                focusRequester = inputsFocus,
            ) {
                // TCL lists no inputs to Android; its own input panel is the next best thing.
                vm.overlay = if (vm.sources.isEmpty() && SystemProbe.openInputPicker(context)) Overlay.None
                else Overlay.ControlCenter(CcPage.Inputs)
            }
            RoundButton(
                when (vm.network.kind) { NetKind.Ethernet -> Glyph.Ethernet; NetKind.Offline -> Glyph.Offline; else -> Glyph.Wifi },
                stringResource(R.string.cc_network),
                focusRequester = networkFocus,
            ) {
                vm.whenUnlocked {
                    val pages = SystemProbe.networkSettingsIntents(context, ethernet = vm.network.kind == NetKind.Ethernet)
                    LaunchFallback.arm(pages.drop(1))
                    onOpen(pages.first())
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        // No volume row here: the TV's own remote already has volume keys, and the panel is a place
        // to reach things the remote cannot (brightness, inputs, Personalize).
        if (showsBrightness) BrightnessRow(vm, laser, upTo = first)

        Spacer(Modifier.height(6.dp))
        // A parent left Kids and Elders mode: one press back into it, no PIN.
        if (Restricted.enabled && Restricted.suspended) {
            MenuRow(
                Glyph.Kids,
                stringResource(if (Restricted.kind == RestrictedKind.Kids) R.string.cc_kids_back else R.string.cc_elders_back),
                onClick = { Restricted.start(); vm.overlay = Overlay.None },
            )
        }
        // What the TV opens when switched on: easy to forget, and puzzling when it happens.
        vm.bootTargetLabel()?.let { name ->
            MenuRow(
                Glyph.Power,
                stringResource(R.string.cc_boot),
                onClick = { vm.overlay = Overlay.ControlCenter(CcPage.InputSettings) },
                trailing = "$name ›",
            )
        }
        // Drosophila has no update channel of its own, and it is not tied to Aerial's: the row that
        // offered "Aerial 2.0" here, the page behind it and the nightly version probe that fed
        // them are all gone (the owner, 2026-11-17: 「目前 aerial 有新版本和我们无关联，把他的更新和
        // 相关推送剥离出去」). SelfCompile below stays: it re-installs this same APK so the system
        // compiles it, and never fetches anything.

        // Android 9 to 11, Drosophila running uncompiled: reinstalling itself has the system compile it,
        // which needs the owner's confirmation there (SelfCompile; 12+ does it by itself).
        LaunchedEffect(Unit) { SelfCompile.recheck(context) }
        if (SelfCompile.offered) {
            MenuRow(Glyph.Download, stringResource(R.string.cc_self_compile), onClick = { SelfCompile.run(context, auto = false) })
        }

        // Installed from the APK alone: how to finish, on the TV or with a computer.
        if (vm.homeStatus == HomeStatus.NotDefault) {
            MenuRow(Glyph.Shield, stringResource(R.string.cc_setup_title), onClick = { vm.overlay = Overlay.ControlCenter(CcPage.Setup) }, trailing = "›")
        }

        // The build's own name and number, in the panel's bottom right corner. The update row that
        // used to end this page showed "Aerial x.x" as its trailing text; with no update channel,
        // the version keeps its place as a plain line, out of the way of the buttons
        // (the owner, 2026-11-17: 「把版本号写在控制中心」 then 「Drosophila 1.24 放在右下角」).
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().padding(end = 14.dp), horizontalArrangement = Arrangement.End) {
            CcText("Drosophila ${BuildConfig.VERSION_NAME.substringBefore('-')}", style = Type.Caption)
        }
    }
}

/**
 * For a TV where Aerial was installed from the APK alone (a USB stick, an app store): it opens, but
 * Home, power-on and wake still go to the stock launcher (issue #213). The settings pages that can
 * fix that on the TV itself, where the TV has them, and a QR code to the website's page with this
 * TV's install command, which does all of it.
 */
@Composable
private fun SetupPage(vm: HomeViewModel, takeFocus: Boolean, onOpen: (android.content.Intent) -> Unit) {
    val context = LocalContext.current
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (!takeFocus) return@LaunchedEffect
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    val guard = remember { SystemProbe.guardSettingsIntent(context) }
    val home = remember { SystemProbe.homeSettingsIntent(context) }
    val revival = remember { SystemProbe.revivalSettingsIntent(context) }
    var qr by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(Unit) {
        val ip = PhoneServer.lanAddress(context)
        val url = "https://aerial.qust.me/update/#setup=1&v=${Updates.current}&f=${BuildConfig.FLAVOR}" + (ip?.let { "&tv=$it" } ?: "")
        qr = withContext(Dispatchers.Default) { PhoneServer.qr(url).asImageBitmap() }
    }
    Column {
        MenuRow(Glyph.ChevronLeft, stringResource(R.string.cc_setup_title), focusRequester = first, onClick = { vm.overlay = Overlay.ControlCenter(CcPage.Main) })
        CcText(stringResource(R.string.cc_setup_body), style = Type.Caption, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
        guard?.let { MenuRow(Glyph.Shield, stringResource(R.string.cc_setup_guard), onClick = { vm.whenUnlocked { onOpen(it) } }, trailing = stringResource(R.string.cc_setup_guard_where) + " ›") }
        home?.let { MenuRow(Glyph.Check, stringResource(R.string.cc_setup_home), onClick = { vm.whenUnlocked { onOpen(it) } }, trailing = "›") }
        revival?.let { MenuRow(Glyph.Moon, stringResource(R.string.cc_setup_revival), onClick = { vm.whenUnlocked { onOpen(it) } }, trailing = stringResource(R.string.cc_setup_revival_where) + " ›") }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp)) {
            Box(Modifier.size(104.dp).smoothBackground(Color.White, 12.dp).padding(8.dp)) {
                qr?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, filterQuality = FilterQuality.None, modifier = Modifier.fillMaxSize()) }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                CcText(stringResource(R.string.cc_setup_scan), style = Type.Body)
                Spacer(Modifier.height(6.dp))
                CcText(stringResource(R.string.cc_setup_scan_hint), style = Type.Caption)
            }
        }
    }
}

/** A round Control Center button: frosted at rest, white when focused. */
@Composable
private fun RoundButton(
    glyph: Glyph,
    label: String,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Pressable(onClick = onClick, modifier = modifier, focusRequester = focusRequester, focusedScale = 1.1f) { state ->
        val bg by animateColorAsState(if (state.focused) Palette.Focus else Palette.Fill, tween(140, easing = Motion.EaseOut), label = "round-bg")
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
            Box(Modifier.size(48.dp).clip(CircleShape).drawBehind { drawRect(bg) }, contentAlignment = Alignment.Center) {
                // Focus fills the circle with the near-white focus plate; a white glyph on it
                // disappears (the owner: 「四个圆图标焦点时里面的图案变黑色，否则看不出来」).
                GlyphIcon(glyph, if (state.focused) Palette.OnFocus else Palette.Label, 20.dp)
            }
            Spacer(Modifier.height(6.dp))
            CcText(
                label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = Type.Caption.copy(color = if (state.focused) Palette.Label else Palette.SecondaryLabel, textAlign = TextAlign.Center),
            )
        }
    }
}

/**
 * A Control Center slider: focus it, then Left and Right change the level, OK does [onClick].
 * Shared by volume and brightness so both feel identical. On a touch screen a tap does [onClick]
 * and a sideways drag steps the level, [steps] steps across the width of the row.
 */
@Composable
private fun SliderRow(
    glyph: Glyph,
    level: Float,
    value: String,
    steps: Int,
    onStep: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    // Each step ticks, as focus does; a step past either end bumps instead. Read through state:
    // the drag handler below is made once and outlives this composition's values.
    val atLevel by rememberUpdatedState(level)
    val doStep by rememberUpdatedState(onStep)
    val step = { by: Int ->
        if ((by > 0 && atLevel >= 0.999f) || (by < 0 && atLevel <= 0.001f)) Sounds.limit() else Sounds.step()
        doStep(by)
    }
    val stepCount by rememberUpdatedState(steps.coerceAtLeast(1))
    Pressable(
        onClick = onClick,
        focusedScale = 1.03f,
        modifier = modifier.fillMaxWidth(),
        onKey = { e ->
            val code = e.nativeKeyEvent.keyCode
            if (code != NativeKeyEvent.KEYCODE_DPAD_LEFT && code != NativeKeyEvent.KEYCODE_DPAD_RIGHT) return@Pressable false
            if (e.type == KeyEventType.KeyDown) step(if (code == NativeKeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
            true
        },
    ) { state ->
        val bg by animateColorAsState(if (state.focused) Palette.Focus else Palette.Fill, tween(140, easing = Motion.EaseOut), label = "slider-bg")
        val fg = Palette.Label
        val fraction by animateFloatAsState(level, spring(dampingRatio = 1f, stiffness = 700f), label = "slider-level")
        Row(
            Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    // The level only moves in the steps the system takes, so the drag is counted
                    // out in steps rather than set to an exact point.
                    var travelled = 0f
                    detectHorizontalDragGestures(onDragStart = { travelled = 0f }) { change, dx ->
                        change.consume()
                        travelled += dx
                        val pitch = size.width.toFloat() / stepCount
                        while (travelled >= pitch) { step(1); travelled -= pitch }
                        while (travelled <= -pitch) { step(-1); travelled += pitch }
                    }
                }
                .smoothBackground({ bg }, 14.dp).height(40.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GlyphIcon(glyph, fg, 18.dp, level = level)
            Canvas(Modifier.weight(1f).height(6.dp)) {
                val r = CornerRadius(size.height / 2)
                drawRoundRect(fg.copy(alpha = 0.2f), cornerRadius = r)
                drawRoundRect(fg, size = Size(size.width * fraction, size.height), cornerRadius = r)
            }
            CcText(
                value,
                style = Type.Headline.copy(color = fg, textAlign = TextAlign.End),
                modifier = Modifier.widthIn(min = 28.dp),
            )
        }
    }
}

@Composable
private fun VolumeRow(vm: HomeViewModel, returnTo: FocusRequester) {
    val volume = vm.volume
    SliderRow(
        glyph = if (volume.muted) Glyph.SpeakerMuted else Glyph.Speaker,
        level = if (volume.muted) 0f else volume.fraction,
        value = if (volume.muted) stringResource(R.string.cc_muted) else volume.level.toString(),
        steps = volume.max,
        onStep = vm::adjustVolume,
        modifier = Modifier.focusProperties { up = returnTo },
        onClick = vm::toggleMute,
    )
}

/** Reads the level every 700 ms while the brightness row is on screen. Neither the DLP light
 * engine nor the picture service announces a change, and the TV's own slider can be moved with the
 * panel still up, so nothing else would refresh either.
 *
 * Only the on-screen copy polls: the closed panel draws this page once at 1 %, so that its first
 * opening is not its first draw (ControlCenter's warm path), and a LaunchedEffect runs from there
 * too — that copy would read the picture service for a panel nobody can see.
 */
@Composable
private fun PollBrightness(vm: HomeViewModel, laser: Boolean, enabled: Boolean) {
    val context = LocalContext.current
    val xgimi = remember { SystemProbe.xgimiBrightnessLevel() != null }
    LaunchedEffect(xgimi, laser, enabled) {
        if (!enabled || (!xgimi && !laser)) return@LaunchedEffect
        // XGIMI's Boost is discovered once, before the first poll; it has no saying on a laser set.
        if (xgimi) vm.discoverBrightnessBoost()
        while (isActive) {
            delay(700)
            vm.refreshBrightness()
        }
    }
}

/**
 * Brightness where Aerial may change it; otherwise a row that opens the TV's picture settings, and
 * says so: a bare "Brightness" with no slider read as broken (Vidda 85V1F-S, FFalcon, issue #130).
 *
 * [upTo] is where Up goes from this row. The row is as wide as the panel, so the default focus
 * search found whichever of the four buttons above happened to sit nearest its middle — 设置 — while
 * the eye reads the row above as one line starting at 个性化 (the owner: 「焦点从亮度移到上面的时候，
 * 应该直接到个性化，而不是设置」).
 */
@Composable
private fun BrightnessRow(vm: HomeViewModel, laser: Boolean, upTo: FocusRequester? = null) {
    val context = LocalContext.current
    // XGIMI (GMUI): the real brightness is the DLP light-engine level. It reads through the HAL
    // class (SystemProbe.xgimiBrightnessLevel) and, on this firmware, writes through it too, so the
    // row is a slider of the light engine's own levels (1..10) rather than a percentage of the
    // system setting that moves nothing. [laser] comes from the page: it is the light source's own
    // 0..10 level, which the system setting does not move either.
    val xgimi = remember { SystemProbe.xgimiBrightnessLevel() != null }
    val level = vm.brightness
    // Up goes to where the page starts, whichever shape the row has.
    val up = if (upTo == null) Modifier else Modifier.focusProperties { up = upTo }
    if (level == null) {
        MenuRow(Glyph.Sun, stringResource(R.string.cc_brightness), onClick = {
            vm.overlay = Overlay.None
            SystemProbe.openPictureSettings(context)
        }, modifier = up, trailing = stringResource(R.string.cc_brightness_in_settings) + " ›")
    } else {
        SliderRow(
            glyph = Glyph.Sun,
            level = level,
            // A laser set's level is 11 notches of its own, not a percentage of anything: show the
            // notch, the number the TV's own slider shows (4, not 40).
            value = when {
                vm.brightnessBoost == true -> "10+"
                laser -> Math.round(level * HisenseLaserPanel.LEVEL_MAX).toString()
                else -> Math.round(level * 100).toString()
            },
            // Brightness.adjust moves 5% a step, and stops at 1%; XGIMI steps its own levels and a
            // Hisense laser set its own 11 notches. Only the drag pitch follows this; a key is one
            // step either way.
            steps = when {
                laser -> HisenseLaserPanel.LEVEL_MAX
                xgimi -> SystemProbe.xgimiBrightnessMax
                else -> 20
            },
            onStep = vm::adjustBrightness,
            modifier = up,
            onClick = {},
        )
    }
}

@Composable
private fun InputsPage(vm: HomeViewModel, takeFocus: Boolean, onOpen: (android.content.Intent) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (!takeFocus) return@LaunchedEffect
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    Column(Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState())) {
        MenuRow(Glyph.ChevronLeft, stringResource(R.string.cc_inputs), onClick = { vm.overlay = Overlay.ControlCenter(CcPage.Main) })
        Spacer(Modifier.height(8.dp))
        val shown = vm.shownSources
        if (shown.isEmpty()) {
            CcText(stringResource(R.string.cc_no_inputs), style = Type.Callout, modifier = Modifier.padding(14.dp))
        }
        val connected = stringResource(R.string.input_connected)
        shown.forEachIndexed { i, source ->
            MenuRow(
                glyph = when (source.kind) {
                    SourceKind.Hdmi, SourceKind.DisplayPort -> Glyph.Hdmi
                    SourceKind.Tuner -> Glyph.Antenna
                    SourceKind.Av -> Glyph.Av
                    SourceKind.Usb -> Glyph.Usb
                    SourceKind.Other -> Glyph.Input
                },
                text = source.label,
                // What the owner named the input on its tile, else what HDMI-CEC reports.
                trailing = SourceLooks.of(source.id)?.let { it.name ?: it.device?.let { d -> stringResource(d.label) } }
                    ?: source.deviceName ?: if (source.connected) connected else null,
                focusRequester = if (i == 0) first else null,
                onClick = { onOpen(source.intent) },
            )
        }
        // Switching is this page's one job; what Aerial does with the inputs is a page further in.
        if (vm.sources.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            MenuRow(
                Glyph.Gear, stringResource(R.string.cc_inputs_settings),
                onClick = { vm.overlay = Overlay.ControlCenter(CcPage.InputSettings) },
                // With every input taken out of the list, the way back in is the first row.
                focusRequester = if (shown.isEmpty()) first else null,
            )
        }
    }
}

/**
 * What Aerial does with the inputs: which ones are tiles on Home (any number) and which one the
 * TV starts on (one, or none). Kept apart from the list that switches inputs.
 */
@Composable
private fun InputSettingsPage(vm: HomeViewModel, takeFocus: Boolean) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (!takeFocus) return@LaunchedEffect
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    fun name(source: app.orchard.tv.system.TvSource) = source.deviceName?.let { "${source.label} · $it" } ?: source.label
    Column(Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState())) {
        MenuRow(Glyph.ChevronLeft, stringResource(R.string.cc_inputs_settings), onClick = { vm.overlay = Overlay.ControlCenter(CcPage.Inputs) })

        val shown = vm.shownSources
        // Which inputs the list has: firmwares list ports the TV does not have (issue #279).
        Spacer(Modifier.height(10.dp))
        CcText(stringResource(R.string.cc_inputs_list), style = Type.Callout, modifier = Modifier.padding(start = 14.dp, bottom = 6.dp))
        vm.sources.forEachIndexed { i, source ->
            val listed = source.id !in AerialSettings.hiddenSources
            ChoiceRow(
                text = name(source),
                selected = listed,
                focusRequester = if (i == 0) first else null,
                onClick = { vm.setSourceHidden(source, listed) },
            )
        }
        CcText(
            stringResource(R.string.cc_inputs_list_hint),
            style = Type.Caption,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp),
        )

        Spacer(Modifier.height(18.dp))
        CcText(stringResource(R.string.cc_inputs_pin), style = Type.Callout, modifier = Modifier.padding(start = 14.dp, bottom = 6.dp))
        shown.forEach { source ->
            val pinned = source.id in AerialSettings.pinnedSources
            ChoiceRow(
                text = name(source),
                selected = pinned,
                focusRequester = null,
                onClick = { vm.setSourcePinned(source, !pinned) },
            )
        }
        CcText(
            stringResource(R.string.cc_inputs_pin_hint),
            style = Type.Caption,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp),
        )

        Spacer(Modifier.height(18.dp))
        CcText(stringResource(R.string.cc_inputs_boot), style = Type.Callout, modifier = Modifier.padding(start = 14.dp, bottom = 6.dp))
        ChoiceRow(
            text = stringResource(R.string.cc_inputs_boot_none),
            selected = !AerialSettings.hasBootTarget,
            focusRequester = null,
            onClick = { vm.chooseBootTarget() },
        )
        // An app is chosen from its tile's menu; here it only shows, and "nothing" clears it.
        AerialSettings.bootApp?.let { pkg ->
            ChoiceRow(
                text = vm.entry(pkg)?.label ?: pkg,
                selected = true,
                focusRequester = null,
                onClick = {},
            )
        }
        shown.forEach { source ->
            ChoiceRow(
                text = name(source),
                selected = source.id == AerialSettings.bootSource,
                focusRequester = null,
                onClick = { vm.chooseBootTarget(sourceId = source.id) },
            )
        }
        CcText(
            stringResource(R.string.cc_inputs_boot_hint),
            style = Type.Caption,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp),
        )

        // A device switching itself on over HDMI-CEC takes the TV (issue #1020); off unless chosen.
        Spacer(Modifier.height(18.dp))
        CcText(stringResource(R.string.cc_inputs_cec), style = Type.Callout, modifier = Modifier.padding(start = 14.dp, bottom = 6.dp))
        ChoiceRow(stringResource(R.string.st_on), AerialSettings.cecSwitch, null) { AerialSettings.chooseCecSwitch(true) }
        ChoiceRow(stringResource(R.string.st_off), !AerialSettings.cecSwitch, null) { AerialSettings.chooseCecSwitch(false) }
        CcText(
            stringResource(R.string.cc_inputs_cec_hint),
            style = Type.Caption,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp),
        )
    }
}

/** A row in a list of choices: the chosen one carries a check, as in tvOS settings. */
@Composable
private fun ChoiceRow(text: String, selected: Boolean, focusRequester: FocusRequester?, onClick: () -> Unit) {
    Pressable(onClick = onClick, focusRequester = focusRequester, focusedScale = 1.03f, modifier = Modifier.fillMaxWidth()) { state ->
        // Alpha, not color: Transparent -> Focus walks the ARGB channels and flashes dark gray
        // halfway (same bug as the status pill).
        val bgAlpha by animateFloatAsState(if (state.focused) 1f else 0f, tween(140, easing = Motion.EaseOut), label = "choice-bg")
        val fg = Palette.Label
        Row(
            Modifier.fillMaxWidth().smoothBackground({ Palette.Focus.copy(alpha = bgAlpha) }, 12.dp).height(44.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CcText(text, style = Type.Body.copy(color = fg), modifier = Modifier.weight(1f))
            if (selected) GlyphIcon(Glyph.Check, fg, 16.dp)
        }
    }
}
