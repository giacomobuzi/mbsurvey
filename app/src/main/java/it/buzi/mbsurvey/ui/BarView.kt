package it.buzi.mbsurvey.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/** Rounded gradient bar, 0..1, with a colour that fades to a lighter tint on the left. */
class BarView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var fraction = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var color = Color.rgb(11, 110, 79)
        set(v) { field = v; invalidate() }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(232, 236, 234) }
    private val fg = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val r = height / 2f
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), r, r, bg)
        val w = width * fraction
        if (w <= 0f) return
        val light = Color.argb(255, (Color.red(color) + 255) / 2, (Color.green(color) + 255) / 2, (Color.blue(color) + 255) / 2)
        fg.shader = LinearGradient(0f, 0f, w, 0f, light, color, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(RectF(0f, 0f, maxOf(w, height.toFloat()), height.toFloat()), r, r, fg)
    }

    companion object {
        /** Green → yellow → red by RSSI. */
        fun rssiColor(rssi: Int): Int = when {
            rssi >= -60 -> Color.rgb(11, 140, 79)
            rssi >= -67 -> Color.rgb(90, 180, 60)
            rssi >= -75 -> Color.rgb(244, 190, 0)
            rssi >= -85 -> Color.rgb(240, 120, 20)
            else -> Color.rgb(210, 40, 40)
        }
        fun rssiFraction(rssi: Int): Float = ((rssi + 100) / 70f).coerceIn(0.03f, 1f)
        /** Good (low) → bad (high) for latency-like values. */
        fun latencyColor(ms: Double): Int = when {
            ms < 20 -> Color.rgb(11, 140, 79); ms < 50 -> Color.rgb(90, 180, 60); ms < 100 -> Color.rgb(244, 190, 0); ms < 250 -> Color.rgb(240, 120, 20); else -> Color.rgb(210, 40, 40)
        }
    }
}
