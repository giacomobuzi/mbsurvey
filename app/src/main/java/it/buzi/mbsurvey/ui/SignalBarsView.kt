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
import it.buzi.mbsurvey.model.ScanEntry

/** Translucent panel with the strongest networks as gradient bars (camera survey overlay). */
class SignalBarsView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var entries: List<ScanEntry> = emptyList()
        set(value) { field = value.sortedByDescending { it.rssi }.take(5); invalidate() }
    var title: String = ""
        set(value) { field = value; invalidate() }

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 20, 28, 25) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 26f; isFakeBoldText = true }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 255, 255, 255); textSize = 22f }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 255, 255, 255) }

    override fun onDraw(c: Canvas) {
        c.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 24f, 24f, bg)
        var y = 34f
        c.drawText(title.ifBlank { "Wi-Fi" }, 16f, y, text)
        y += 14f
        val rowH = (height - y - 10f) / 5f
        for (e in entries) {
            val name = e.ssid.ifBlank { e.bssid.takeLast(8) }.take(16)
            c.drawText(name, 16f, y + 22f, small)
            val v = "${e.rssi}"
            c.drawText(v, width - 16f - small.measureText(v), y + 22f, small)
            val top = y + 28f; val bottom = top + 10f
            c.drawRoundRect(RectF(16f, top, width - 16f, bottom), 5f, 5f, track)
            val w = (width - 32f) * BarView.rssiFraction(e.rssi)
            val col = BarView.rssiColor(e.rssi)
            bar.shader = LinearGradient(16f, 0f, 16f + w, 0f, Color.argb(160, Color.red(col), Color.green(col), Color.blue(col)), col, Shader.TileMode.CLAMP)
            c.drawRoundRect(RectF(16f, top, 16f + w, bottom), 5f, 5f, bar)
            y += rowH
        }
    }
}
