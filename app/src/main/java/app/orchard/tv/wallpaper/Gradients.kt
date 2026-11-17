package app.orchard.tv.wallpaper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.core.graphics.createBitmap
import kotlin.math.sin

/**
 * The one program-drawn wallpaper that stays: the dark night picture TV inputs sit on (and that
 * stands in for a video's frame until one can be had). The Mac-style set it belonged to is gone
 * from Personalize — nobody chose it.
 *
 * Every shape is laid out on fractions of the picture, so a thumbnail and the full picture match.
 */
class GradientPreset(
    val key: String,
    val nameZh: String,
    val nameEn: String,
    internal val draw: Canvas.(w: Float, h: Float) -> Unit,
)

object Gradients {
    val presets = listOf(
        GradientPreset("hills_night", "山丘 · 夜", "Hills, Night") { w, h ->
            hills(w, h, sky(0xFF04081C, 0xFF14285A, 0xFF2F4D86), 0.62f, 0.58f, 0xFF78AAFF, 0.35f, listOf(
                0xFF1F3566 to 0xFF253C70, 0xFF162851 to 0xFF182B55, 0xFF0D1B3A to 0xFF0B1733, 0xFF050C1F to 0xFF040917,
            ))
        },
    )

    fun byKey(key: String) = presets.firstOrNull { it.key == key }

    /** Renders a preset at [width]×[height], dithered so the long gradients do not band. */
    fun render(preset: GradientPreset, width: Int, height: Int): Bitmap {
        val out = createBitmap(width, height)
        Canvas(out).apply { preset.draw(this, width.toFloat(), height.toFloat()) }
        Images.dither(out)
        return out
    }

    // ---- the kinds still drawn ----

    private fun sky(vararg c: Long) = c.map { it.toInt() }.toIntArray()

    private fun paint(shader: Shader) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }

    private fun vertical(y0: Float, y1: Float, colors: IntArray) =
        LinearGradient(0f, y0, 0f, y1, colors, null, Shader.TileMode.CLAMP)

    private fun pair(p: Pair<Long, Long>) = intArrayOf(p.first.toInt(), p.second.toInt())

    private fun Canvas.glowAt(w: Float, h: Float, x: Float, y: Float, r: Float, color: Long, alpha: Float) {
        val c = color.toInt()
        val a = (alpha * 255).toInt() shl 24
        drawRect(0f, 0f, w, h, paint(RadialGradient(x, y, r, (c and 0xFFFFFF) or a, c and 0xFFFFFF, Shader.TileMode.CLAMP)))
    }

    /**
     * A ridge: three sines, seeded so each layer has its own, the same at every size. Closed down
     * to the bottom of the picture, or with [crestOnly] just the line along the top.
     */
    private fun ridge(w: Float, h: Float, base: Float, amp: Float, seed: Int, crestOnly: Boolean = false): Path {
        var s = seed.toLong()
        fun r(): Float { s = (s * 16807) % 2147483647; return s / 2147483647f }
        val ph = floatArrayOf(r() * 6, r() * 6, r() * 6)
        val f = floatArrayOf(1.3f + r(), 3 + r() * 2, 7 + r() * 3)
        return Path().apply {
            val steps = 240
            for (i in 0..steps) {
                val t = i / steps.toFloat()
                val y = base + amp * (sin(t * f[0] + ph[0]) * 0.6f + sin(t * f[1] + ph[1]) * 0.28f + sin(t * f[2] + ph[2]) * 0.12f)
                if (i == 0) {
                    if (crestOnly) moveTo(0f, y) else { moveTo(0f, h); lineTo(0f, y) }
                } else {
                    lineTo(t * w, y)
                }
            }
            if (!crestOnly) {
                lineTo(w, h)
                close()
            }
        }
    }

    private fun Canvas.hills(w: Float, h: Float, sky: IntArray, sunX: Float, sunY: Float, glow: Long, glowA: Float, layers: List<Pair<Long, Long>>) {
        drawRect(0f, 0f, w, h, paint(vertical(0f, h * 0.75f, sky)))
        glowAt(w, h, w * sunX, h * sunY, w * 0.45f, glow, glowA)
        layers.forEachIndexed { i, l ->
            val base = h * (0.45f + i * 0.11f)
            drawPath(ridge(w, h, base, h * (0.09f - i * 0.006f), 7 + i * 13), paint(vertical(base - h * 0.1f, h, pair(l))))
        }
    }

}
