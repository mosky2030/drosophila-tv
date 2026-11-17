package app.orchard.tv.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import android.os.SystemClock
import android.provider.Settings
import app.orchard.tv.BuildConfig
import app.orchard.tv.apps.IconLibrary
import app.orchard.tv.guard.GuardSetup
import app.orchard.tv.system.DeviceClass
import app.orchard.tv.wallpaper.AerialQuality
import app.orchard.tv.wallpaper.ScreensaverMode
import app.orchard.tv.wallpaper.Wallpapers
import app.orchard.tv.weather.Weather
import java.security.MessageDigest

/**
 * Where interface sounds come from. The TV's own are silent wherever its key sound setting is off,
 * which many TVs ship with (Sony, issue #160; #169), so that is not the default there.
 */
enum class UiSounds(val key: String) {
    Tv("tv"), Aerial("aerial"), Off("off");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key }
    }
}

/**
 * How much of the interface's light and motion to draw. Full is the glass panels, a focused tile's
 * sheen, parallax and shadow, and the panel's highest refresh rate. Lite draws the panels in plain
 * dark glass (no offscreen layer per frame), a focused tile without sheen, parallax or shadow, and
 * stays at 60 Hz: for TVs whose GPU cannot keep up (issues #232, #354). Auto is Lite on a low-end
 * TV (DeviceClass) and Full elsewhere.
 */
enum class Effects(val key: String) {
    Auto("auto"), Full("full"), Lite("lite");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Auto
    }
}

/**
 * 24- or 12-hour time. System follows the TV's own setting, and 24 hours where the TV has none: an
 * unset setting means the locale's habit, and zh_CN's is 12-hour, which TV clocks here never show
 * (TCL 65S515D, issue #15).
 */
enum class ClockHours(val key: String) {
    System("system"), H24("24"), H12("12");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: System
    }
}

/**
 * How large Home's clock is: the corner-line small one, the lock-screen one above the top row,
 * that one a size up, or the time alone at nearly the screen's width, over the icons.
 */
enum class ClockSize { Small, Large, XL, Full;

    companion object {
        fun of(key: String?): ClockSize? = when (key) {
            "small" -> Small; "large" -> Large; "xl" -> XL; "full" -> Full
            else -> null
        }
    }
}

/**
 * An extra, opt-in look for Home's app tiles, from the TV puzzle prototype: Original leaves every
 * one of Aerial's own Home styles exactly as they are; Translucent dims the unfocused tiles into
 * the wallpaper and keeps the focused one fully opaque, the way glass icons read on Apple TV.
 *
 * [Dock] is the one that changes Home's shape rather than its glass: every app on one long bar of
 * glass at the bottom, square icons, the focused one grown with its neighbours stepping down in
 * size, and the folders kept together at the bar's right end. Rooms inside that bar are taken from
 * the screen: one size fits all, so the more apps, the smaller the icons.
 *
 * [Wheel] is a short bar of the same square icons, a few at a time, the focused one held in the
 * middle of the screen while the row slides through it: Left and Right turn the wheel rather than
 * walk the focus along, and the icons further from the middle fade back as they go.
 */
enum class IconStyle(val key: String) {
    Original("original"), Translucent("translucent"), Dock("dock"), Wheel("wheel"), Hidden("hidden");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Original
    }
}

/**
 * Where Home's clock sits: the two top corners, or the middle of the top edge. The full-screen
 * clock (§满屏) ignores it — it is centred by design, over the icons.
 */
enum class ClockPlace(val key: String) {
    Left("left"), Center("center"), Right("right");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Center
    }
}

/**
 * How every app's icon is drawn, one look for the whole of Home (the owner: 「现在各种APP图标
 * 颜色乱七八糟…统一图标风格」).
 *
 * [Original] keeps the icon as the app drew it: its own plate carried across the tile and its own
 * mark, in its own colors. [Dopamine] gives every app a bright, saturated plate of its own (the
 * same one every time, from the package name) and keeps the mark as it is. A frosted-glass look
 * was tried between these and taken out again (the owner: 「磨砂玻璃效果还是不行，先删除吧，多巴胺
 * 保留」); a choice saved as it falls back to [Original].
 */
enum class IconLook(val key: String) {
    Original("original"), Dopamine("dopamine");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Original
    }
}

/**
 * What Back does on Home's top row (the owner: 「个性化菜单里的返回键，删除后面的休眠功能」).
 * Sleep was an option and was taken out; a saved "sleep" value falls back to [Screensaver].
 */
enum class BackAction(val key: String) {
    /** Starts the screensaver, as on tvOS. */
    Screensaver("saver"),

    /** Nothing, for homes where Back gets pressed by accident (issue #94). */
    Off("off");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Screensaver
    }
}

/** Aerial's own preferences. Compose state, written on the main thread only. */
object AerialSettings {    private const val PREFS = "settings"
    private var started = false
    private lateinit var app: Context

    /** Opt-in tile look; Original is the default, so an existing Home never changes on upgrade. */
    var iconStyle by mutableStateOf(IconStyle.Original); private set

    /** How every app's icon is drawn (see [IconLook]); the app's own icon unless changed. */
    var iconLook by mutableStateOf(IconLook.Original); private set


    /**
     * TV inputs the owner took out of the inputs list, by id: ports the firmware lists but the TV
     * does not have (TCL 75V2 lists four HDMI for its two, issue #279), or never uses (#744).
     */
    var hiddenSources by mutableStateOf<Set<String>>(emptySet()); private set

    /**
     * Switch to an input when the device on it switches itself on over HDMI-CEC (CecSwitch, issue
     * #1020). Off unless chosen: a console waking should not take the TV from everyone.
     */
    var cecSwitch by mutableStateOf(false); private set

    /** TV inputs pinned to Home as tiles, by TvInputInfo id. */
    var pinnedSources by mutableStateOf<Set<String>>(emptySet()); private set

    /**
     * The input to open when the TV is switched on (a set-top box, an Apple TV), by TvSource id;
     * null for none. Home still comes back to Aerial. Issue #25.
     */
    var bootSource by mutableStateOf<String?>(null); private set

    /**
     * An app to open when the TV is switched on instead of an input, by package name: a live TV
     * app that plays the last channel. Only one of this and [bootSource] is set.
     */
    var bootApp by mutableStateOf<String?>(null); private set

    /**
     * Only the top row on Home, low on the screen, so the wallpaper (a family photo) shows above it,
     * as tvOS does under a full-screen top shelf. The rest of the grid is one press down.
     * No longer a setting: Aerial always lays Home out this way.
     */
    val oneRowHome = true

    /**
     * Apps a row on Home: six, as tvOS 26 lays them out. No longer a setting, so a Home keeps the
     * same rhythm whatever was chosen before.
     */
    private const val DEFAULT_HOME_COLUMNS = 6

    /** The number in effect; [Restricted.COLUMNS] in Kids and Elders mode, for bigger tiles from the sofa. */
    val homeColumns: Int get() = if (Restricted.active) Restricted.COLUMNS else DEFAULT_HOME_COLUMNS

    /**
     * Home shows only its first this many tiles, for trying a nearly empty Home on a TV that has
     * many apps (ConfigReceiver preview_apps). Kept in memory only: nothing is hidden or moved,
     * and the next start, or 0, shows them all again.
     */
    var previewApps by mutableIntStateOf(0)

    /** In one-row mode, the top of the second row peeking under the first. Always on now. */
    val peekNextRow = true

    /** Interface sounds (system/Sounds), as tvOS plays them on every press. */
    var uiSounds by mutableStateOf(UiSounds.Tv); private set

    const val DAY_MINUTES = 1440
    /** The choices for [photoMinutes]: a day, an hour, 15, 5 and 1 minutes. */
    val PHOTO_MINUTES = listOf(DAY_MINUTES, 60, 15, 5, 1)

    /**
     * Home's apps stay where they are: holding OK or Menu on one opens no menu, so nothing gets
     * moved, hidden, put in a folder or uninstalled by a stray press (issues #717 #1013 #1106).
     * Unlocking asks for the parental PIN when one is set.
     */
    var homeLocked by mutableStateOf(false); private set

    /** The Liquid Glass plate under the top row. Always on now: glass under the apps. */
    val homePlate = true

    /** Where the clock, with the date and weather, sits: either top corner or the top middle. */
    var clockPlace by mutableStateOf(ClockPlace.Center); private set

    /** The date and weather over a big clock above the top row, instead of one small line in the corner. */
    var largeClock by mutableStateOf(false); private set

    /** The shelf clock a size up ("超大"): the same clock at a bigger print, where the room allows it. */
    var clockXL by mutableStateOf(false); private set

    /** The time alone, at nearly the screen's width ("满屏"), clear of the icons above them. */
    var clockFull by mutableStateOf(false); private set

    /** The size the booleans make: the small corner line, the shelf clock, that one a size up, or the full one. */
    val clockSize: ClockSize
        get() = when {
            !largeClock -> ClockSize.Small
            clockFull -> ClockSize.Full
            clockXL -> ClockSize.XL
            else -> ClockSize.Large
        }

    /**
     * The Chinese calendar's date after the date on the clock ("十月初七"). Off unless asked for:
     * a TV in a household that does not read it should not carry it.
     */
    var clockLunar by mutableStateOf(false); private set

    /** The solar term the day falls in, after the date ("立冬"), where it is set to be shown. */
    var clockTerm by mutableStateOf(false); private set

    /**
     * How often "My Photos, in turn" moves to the next photo, in minutes; a day (1440) unless the
     * owner chose shorter, for a home that wants Home to be a slideshow (issue #1314).
     */
    var photoMinutes by mutableIntStateOf(DAY_MINUTES); private set

    /** 24- or 12-hour time, or as the TV is set (issues #1324 #1044). */
    var clockHours by mutableStateOf(ClockHours.System); private set

    /**
     * The moving wallpaper (aerial films, the owner's own videos) plays on the top row whatever the
     * keys do, under a little shade, rather than only once Home has sat still (issues #360, #456).
     * The grid below and panels still stop it: they cover it.
     */
    var wallpaperAlwaysMoves by mutableStateOf(false); private set

    /** The weather beside the clock; off for those who want the time alone (issue #472). */
    var showWeather by mutableStateOf(true); private set

    /**
     * The time over Aerial's aerial and photo screensavers (issue #240: TCL's firmware puts its own
     * clock over the system screensaver). The clock screensaver is the clock and keeps it.
     */
    var screensaverClock by mutableStateOf(true); private set

    /**
     * The screensaver is the puzzle: Home's picture as a jigsaw, shuffled, played with the remote
     * (ui/home/Screensaver.kt). A switch of its own rather than one of the kinds, so it takes the
     * screensaver over whatever kind was chosen, and whatever the wallpaper: Bing's picture of the
     * day, one of the owner's own photos, or the bundled puzzle set. Off puts the chosen kind back.
     */
    var puzzleSaver by mutableStateOf(false); private set

    /** Every app's name under its tile, not only the focused one's (issue #28), the top row's on the glass plate too. */
    private var chosenAllNames by mutableStateOf(false)

    /** As chosen; always every name in Elders mode (Restricted). */
    val showAllNames: Boolean get() = chosenAllNames || (Restricted.active && Restricted.kind == RestrictedKind.Elders)

    /** What Back on Home's top row does (see [BackAction]); the screensaver unless changed (issue #94). */
    var backAction by mutableStateOf(BackAction.Screensaver); private set

    /**
     * Parental PIN (issue #10): asked before TV settings open and before hidden apps come back.
     * Kept as a SHA-256 hash. One right entry holds for a while, until Home is pressed, Aerial
     * leaves the screen or the screensaver starts.
     */
    var pinSet by mutableStateOf(false); private set

    /**
     * The PIN is four presses of the arrow keys rather than four digits: quicker on a remote with
     * no number keys, and a child watching sees nothing on screen to copy (issue #368).
     */
    var pinArrows by mutableStateOf(false); private set
    private var pinHash: String? = null
    private var unlockedUntil = 0L
    private const val UNLOCK_MS = 10 * 60 * 1000L

    /**
     * Seconds of no input on Home before Aerial's screensaver starts (issue #66). The firmware's own
     * screensaver still caps it: Aerial starts just ahead of that one, see MainActivity.
     * [NEVER] starts none, and switches off the system's while that is Aerial's (issue #333).
     * Seconds rather than minutes: 30 s and 1 min are the shortest the sheet offers (the owner).
     */
    var screensaverSeconds by mutableIntStateOf(DEFAULT_SCREENSAVER_SECONDS); private set

    const val NEVER = 0

    /** 30 s, 1, 2, 5 and 10 min, then never. 20 min was dropped (the owner). */
    val screensaverChoices = listOf(30, 60, 120, 300, 600, NEVER)

    /** Which of Apple's files the aerial screensaver plays; Auto is the best this TV decodes (issues #198, #217). */
    var aerialQuality by mutableStateOf(AerialQuality.Auto); private set

    var effects by mutableStateOf(Effects.Auto); private set

    /** This TV counts as low-end for [Effects.Auto]; see DeviceClass. */
    var lowEnd = false; private set

    /** Draw the lighter interface (see [Effects]). */
    val lite: Boolean get() = effects == Effects.Lite || (effects == Effects.Auto && lowEnd)
    private const val DEFAULT_SCREENSAVER_SECONDS = 300

    fun init(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        iconStyle = IconStyle.of(prefs.getString("icon_style", null))
        iconLook = IconLook.of(prefs.getString("icon_look", null))
        pinnedSources = prefs.getStringSet("pinned_sources", null)?.toSet() ?: emptySet()
        hiddenSources = prefs.getStringSet("hidden_sources", null)?.toSet() ?: emptySet()
        cecSwitch = prefs.getBoolean("cec_switch", false)
        bootSource = prefs.getString("boot_source", null)
        bootApp = prefs.getString("boot_app", null)
        pinHash = prefs.getString("pin_hash", null)
        pinSet = pinHash != null
        pinArrows = prefs.getBoolean("pin_arrows", false)
        // The layout is fixed now (one row, the second peeking, six across, on glass): the keys
        // written by older versions are left behind rather than read.
        // Before this the clock was either top left or top right; a Home that had chosen the
        // right keeps it, every other one moves to the middle (the owner: 默认居中).
        clockPlace = ClockPlace.of(
            prefs.getString("clock_place", null)
                ?: if (prefs.getBoolean("clock_right", false)) ClockPlace.Right.key else null,
        )
        largeClock = prefs.getBoolean("large_clock", false)
        clockXL = prefs.getBoolean("clock_xl", false)
        clockFull = prefs.getBoolean("clock_full", false)
        clockLunar = prefs.getBoolean("clock_lunar", false)
        clockTerm = prefs.getBoolean("clock_term", false)
        clockHours = ClockHours.of(prefs.getString("clock_hours", null))
        homeLocked = prefs.getBoolean("home_locked", false)
        photoMinutes = prefs.getInt("photo_minutes", DAY_MINUTES).takeIf { it in PHOTO_MINUTES } ?: DAY_MINUTES
        chosenAllNames = prefs.getBoolean("show_all_names", false)
        wallpaperAlwaysMoves = prefs.getBoolean("wallpaper_always_moves", false)
        showWeather = prefs.getBoolean("show_weather", true)
        screensaverClock = prefs.getBoolean("screensaver_clock", true)
        puzzleSaver = prefs.getBoolean("puzzle_saver", false)
        // The puzzle was one of the screensaver kinds until it became a switch of its own. A Home
        // that had it chosen keeps the puzzle, now as the switch, and its kind goes back to
        // following the wallpaper — so turning the switch off gives the normal screensaver.
        // Here rather than in Wallpapers.init, which runs before this and has no app to write with.
        val wallpaperPrefs = app.getSharedPreferences(Wallpapers.PREFS, Context.MODE_PRIVATE)
        if (wallpaperPrefs.getString("screensaver", null) == ScreensaverMode.Puzzle.key) {
            puzzleSaver = true
            prefs.edit { putBoolean("puzzle_saver", true) }
            wallpaperPrefs.edit { putString("screensaver", Wallpapers.FOLLOW) }
        }
        // Before 1.28 this was on or off; on meant whichever sounds Aerial played.
        uiSounds = UiSounds.of(prefs.getString("ui_sounds_kind", null)) ?: when {
            !prefs.getBoolean("ui_sounds", true) -> UiSounds.Off
            Settings.System.getInt(app.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, 1) == 1 -> UiSounds.Tv
            else -> UiSounds.Aerial
        }
        // A switch before this one: on meant the screensaver, off that Back did nothing.
        backAction = BackAction.of(
            prefs.getString("back_action", null)
                ?: if (prefs.getBoolean("back_starts_screensaver", true)) BackAction.Screensaver.key else BackAction.Off.key,
        )
        // Seconds since 1.100; the key before it held minutes.
        screensaverSeconds = prefs.getInt(
            "screensaver_seconds",
            prefs.getInt("screensaver_minutes", DEFAULT_SCREENSAVER_SECONDS / 60) * 60,
        )
        aerialQuality = AerialQuality.of(prefs.getString("aerial_quality", null))
        effects = Effects.of(prefs.getString("effects", null))
        lowEnd = DeviceClass.lowEnd(app)
    }

    fun chooseBootSource(id: String?) {
        bootSource = id
        bootApp = null
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("boot_source", id).remove("boot_app") }
    }

    fun chooseBootApp(pkg: String?) {
        bootApp = pkg
        bootSource = null
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("boot_app", pkg).remove("boot_source") }
    }

    /** Something opens when the TV is switched on. */
    val hasBootTarget: Boolean get() = bootSource != null || bootApp != null

    val locked: Boolean get() = pinSet && SystemClock.elapsedRealtime() > unlockedUntil

    fun unlock(pin: String): Boolean {
        if (hash(pin) != pinHash) return false
        unlockedUntil = SystemClock.elapsedRealtime() + UNLOCK_MS
        return true
    }

    fun relock() { unlockedUntil = 0L }

    /** Whether [pin] is the PIN, without unlocking anything: for leaving Kids mode and its time limits. */
    fun pinMatches(pin: String): Boolean = pinHash != null && hash(pin) == pinHash

    /** Sets the PIN, digits or [arrows] (U, D, L, R), or turns it off with null. */
    fun choosePin(pin: String?, arrows: Boolean = false) {
        pinHash = pin?.let(::hash)
        pinSet = pinHash != null
        pinArrows = pin != null && arrows
        // Locked from the start: a parent who sets the PIN and hands the remote over expects it
        // to hold at once, not after the next Home press (issue #718).
        unlockedUntil = 0L
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("pin_hash", pinHash); putBoolean("pin_arrows", pinArrows) }
    }

    private fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256").digest("aerial:$pin".toByteArray()).joinToString("") { "%02x".format(it) }

    fun chooseUiSounds(kind: UiSounds) {
        uiSounds = kind
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("ui_sounds_kind", kind.key) }
    }

    fun chooseClockPlace(place: ClockPlace) {
        clockPlace = place
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("clock_place", place.key) }
    }

    fun chooseLargeClock(on: Boolean) {
        largeClock = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("large_clock", on) }
    }

    /**
     * The clock's size, as one choice over the two booleans it is kept as ([largeClock] keeps its
     * old key, and [clockXL] rides on it), so the phone page's plain on/off still works.
     */
    fun chooseClockSize(size: ClockSize) {
        val large = size != ClockSize.Small
        val xl = size == ClockSize.XL
        val full = size == ClockSize.Full
        largeClock = large
        clockXL = xl
        clockFull = full
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean("large_clock", large)
            putBoolean("clock_xl", xl)
            putBoolean("clock_full", full)
        }
    }

    fun chooseClockLunar(on: Boolean) {
        clockLunar = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("clock_lunar", on) }
    }

    fun chooseClockTerm(on: Boolean) {
        clockTerm = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("clock_term", on) }
    }

    fun choosePhotoMinutes(minutes: Int) {
        if (minutes !in PHOTO_MINUTES) return
        photoMinutes = minutes
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putInt("photo_minutes", minutes) }
    }

    fun chooseHomeLocked(on: Boolean) {
        homeLocked = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("home_locked", on) }
    }

    fun chooseClockHours(hours: ClockHours) {
        clockHours = hours
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("clock_hours", hours.key) }
    }

    fun chooseShowAllNames(on: Boolean) {
        chosenAllNames = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("show_all_names", on) }
    }

    fun chooseWallpaperAlwaysMoves(on: Boolean) {
        wallpaperAlwaysMoves = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("wallpaper_always_moves", on) }
    }

    fun chooseShowWeather(on: Boolean) {
        showWeather = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("show_weather", on) }
        // On: fetch at once if the reading has gone stale — waiting for the next return to Home
        // left the clock without weather after the toggle said it was on (the owner).
        if (on) Weather.refreshIfStale()
    }

    fun chooseScreensaverClock(on: Boolean) {
        screensaverClock = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("screensaver_clock", on) }
    }

    fun choosePuzzleSaver(on: Boolean) {
        puzzleSaver = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("puzzle_saver", on) }
    }

    fun chooseBackAction(action: BackAction) {
        backAction = action
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("back_action", action.key) }
    }

    fun chooseAerialQuality(quality: AerialQuality) {
        aerialQuality = quality
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("aerial_quality", quality.key) }
    }

    fun chooseScreensaverSeconds(seconds: Int) {
        screensaverSeconds = seconds
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putInt("screensaver_seconds", seconds) }
        GuardSetup.setSystemScreensaverOff(app, seconds == NEVER)
    }

    fun chooseCecSwitch(on: Boolean) {
        cecSwitch = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("cec_switch", on) }
    }

    fun setSourceHidden(inputId: String, hidden: Boolean) {
        hiddenSources = if (hidden) hiddenSources + inputId else hiddenSources - inputId
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putStringSet("hidden_sources", hiddenSources) }
    }

    fun setPinned(inputId: String, pinned: Boolean) {
        pinnedSources = if (pinned) pinnedSources + inputId else pinnedSources - inputId
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putStringSet("pinned_sources", pinnedSources) }
    }


    fun chooseEffects(choice: Effects) {
        effects = choice
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("effects", choice.key) }
    }

    fun chooseIconStyle(style: IconStyle) {
        iconStyle = style
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("icon_style", style.key) }
    }

    fun chooseIconLook(look: IconLook) {
        iconLook = look
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("icon_look", look.key) }
    }

}
