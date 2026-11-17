package app.orchard.tv.system

import android.content.Context
import android.provider.Settings

/**
 * Puts back a screen timeout saved by an older Aerial, which could put the TV to sleep from
 * Personalize's Back option. That option is gone (the owner: 「个性化菜单里的返回键，删除后面的休眠功能」);
 * what is left is this restore at the next wake, so an upgrade never leaves the owner's timeout
 * shortened. Safe to call at any time, and more than once.
 */
object Sleep {
    private const val SAVED = "screen_off_timeout_before_sleep"

    private fun prefs(context: Context) =
        context.getSharedPreferences("sleep", Context.MODE_PRIVATE)

    fun restore(context: Context) {
        val p = prefs(context)
        val saved = p.getLong(SAVED, -1L)
        if (saved <= 0L) return
        p.edit().remove(SAVED).apply()
        runCatching { Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, saved.toInt()) }
        DiagLog.i("sleep: screen timeout back to $saved ms")
    }
}
