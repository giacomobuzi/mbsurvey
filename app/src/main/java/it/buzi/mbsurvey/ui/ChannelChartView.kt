package it.buzi.mbsurvey.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import it.buzi.mbsurvey.model.Band
import it.buzi.mbsurvey.model.ScanEntry

/**
 * Channel occupancy chart like inSSIDer / Ekahau "Channels": one curve per BSSID, width = channel width,
 * height = RSSI. Three stacked panels: 2.4, 5 and 6 GHz.
 */
class ChannelChartView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var entries: List<ScanEntry> = emptyList()
        set(value) { field = value; invalidate() }
    var highlightBssid: String? = null
        set(value) { field = value; invalidate() }

    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(160, 170, 166); strokeWidth = 2f }
    private val grid = Paint().apply { color = Color.rgb(236, 239, 238); strokeWidth = 1f }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(95, 107, 102); textSize = 24f }
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(11, 110, 79); textSize = 28f; isFakeBoldText = true }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
    private val ssidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; isFakeBoldText = true; textAlign = Paint.Align.CENTER }

    private val palette = intArrayOf(
        Color.rgb(11, 110, 79), Color.rgb(30, 100, 200), Color.rgb(230, 100, 20), Color.rgb(150, 40, 160),
        Color.rgb(200, 30, 60), Color.rgb(0, 150, 160), Color.rgb(120, 120, 20), Color.rgb(90, 60, 30)
    )

    private class Panel(val band: Band, val minMhz: Int, val maxMhz: Int, val ticks: List<Int>)

    private val panels = listOf(
        Panel(Band.B24, 2400, 2495, listOf(1, 3, 5, 7, 9, 11, 13)),
        Panel(Band.B5, 5150, 5875, listOf(36, 44, 52, 60, 100, 108, 116, 124, 132, 140, 149, 157, 165)),
        Panel(Band.B6, 5925, 7125, listOf(1, 25, 49, 73, 97, 121, 145, 169, 193, 217))
    )

    private fun chanFreq(band: Band, ch: Int): Int = when (band) {
        Band.B24 -> if (ch == 14) 2484 else 2407 + 5 * ch
        Band.B5 -> 5000 + 5 * ch
        Band.B6 -> 5950 + 5 * ch
        else -> 0
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val visible = panels.filter { p -> p.band != Band.B6 || entries.any { it.band == Band.B6 } }
        val padL = 70f; val padR = 20f; val padT = 44f; val padB = 44f
        val ph = height / visible.size.toFloat()
        val colorOf = HashMap<String, Int>()
        var ci = 0
        for ((pi, p) in visible.withIndex()) {
            val top = pi * ph
            val plotL = padL; val plotR = width - padR; val plotT = top + padT; val plotB = top + ph - padB
            canvas.drawText(p.band.label, 16f, top + 32f, title)
            // y grid: -30 .. -100 dBm
            for (dbm in listOf(-30, -50, -70, -90)) {
                val y = yOf(dbm.toFloat(), plotT, plotB)
                canvas.drawLine(plotL, y, plotR, y, grid)
                canvas.drawText("$dbm", 10f, y + 8f, label)
            }
            canvas.drawLine(plotL, plotB, plotR, plotB, axis)
            canvas.drawLine(plotL, plotT, plotL, plotB, axis)
            for (ch in p.ticks) {
                val x = xOf(chanFreq(p.band, ch).toFloat(), p, plotL, plotR)
                canvas.drawLine(x, plotB, x, plotB + 6f, axis)
                label.textAlign = Paint.Align.CENTER
                canvas.drawText(ch.toString(), x, plotB + 30f, label)
                label.textAlign = Paint.Align.LEFT
            }
            val list = entries.filter { it.band == p.band }.sortedBy { it.rssi }
            for (e in list) {
                val color = colorOf.getOrPut(e.bssid) { palette[(ci++) % palette.size] }
                val centre = if (e.centerFreq0 > 0 && e.widthMhz > 20) e.centerFreq0 else e.freqMhz
                val half = e.widthMhz / 2f
                val x0 = xOf(centre - half, p, plotL, plotR); val x1 = xOf(centre + half, p, plotL, plotR)
                val yTop = yOf(e.rssi.toFloat(), plotT, plotB)
                val hl = highlightBssid == null || highlightBssid == e.bssid
                val path = Path().apply {
                    moveTo(x0, plotB)
                    val k = (x1 - x0) * 0.12f
                    cubicTo(x0 + k, yTop, x0 + k, yTop, x0 + (x1 - x0) / 2, yTop)
                    cubicTo(x1 - k, yTop, x1 - k, yTop, x1, plotB)
                    close()
                }
                fill.color = Color.argb(if (hl) 70 else 20, Color.red(color), Color.green(color), Color.blue(color))
                stroke.color = Color.argb(if (hl) 230 else 70, Color.red(color), Color.green(color), Color.blue(color))
                canvas.drawPath(path, fill)
                canvas.drawPath(path, stroke)
                if (hl && e.rssi > -85) {
                    ssidPaint.color = stroke.color
                    canvas.drawText(e.ssid.ifBlank { e.bssid.takeLast(5) }.take(18), (x0 + x1) / 2, yTop - 6f, ssidPaint)
                }
            }
        }
    }

    private fun xOf(mhz: Float, p: Panel, l: Float, r: Float): Float = l + (r - l) * (mhz - p.minMhz) / (p.maxMhz - p.minMhz)
    private fun yOf(dbm: Float, t: Float, b: Float): Float = b - (b - t) * ((dbm.coerceIn(-100f, -30f) + 100f) / 70f)

    fun legendRect(): RectF = RectF(0f, 0f, width.toFloat(), height.toFloat())
}
