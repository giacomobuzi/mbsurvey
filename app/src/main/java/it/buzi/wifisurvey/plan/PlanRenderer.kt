package it.buzi.wifisurvey.plan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import it.buzi.wifisurvey.model.PlanDrawing
import kotlin.math.ceil

/** Draws a PlanDrawing on a canvas (editor preview) or into a bitmap (the floor image used by surveys). */
object PlanRenderer {

    private val roomFills = intArrayOf(
        Color.rgb(226, 240, 234), Color.rgb(232, 236, 246), Color.rgb(250, 240, 224),
        Color.rgb(240, 232, 244), Color.rgb(228, 242, 246), Color.rgb(246, 232, 232)
    )

    /** Rasterises the plan at the plan's fixed px/m. White background, ready for surveys. */
    fun toBitmap(plan: PlanDrawing): Bitmap {
        val ppm = PlanDrawing.pxPerMeter(plan.widthM, plan.heightM)
        val w = maxOf(64, ceil(plan.widthM * ppm).toInt())
        val h = maxOf(64, ceil(plan.heightM * ppm).toInt())
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        draw(c, plan, ppm.toFloat(), 0f, 0f, grid = true, selectedId = null, labels = true)
        return bmp
    }

    /**
     * Draws the plan with the given scale (px per metre) and offset. Used by the editor with the live
     * zoom and by toBitmap. selectedId highlights one element.
     */
    fun draw(c: Canvas, plan: PlanDrawing, ppm: Float, ox: Float, oy: Float, grid: Boolean, selectedId: String?, labels: Boolean) {
        val W = (plan.widthM * ppm).toFloat(); val H = (plan.heightM * ppm).toFloat()
        val bg = Paint().apply { color = Color.WHITE }
        c.drawRect(ox, oy, ox + W, oy + H, bg)

        if (grid) {
            val gp = Paint().apply { color = Color.rgb(236, 239, 238); strokeWidth = 1f }
            val gp5 = Paint().apply { color = Color.rgb(218, 224, 221); strokeWidth = 1f }
            var m = 0.0
            while (m <= plan.widthM + 1e-6) {
                val x = ox + (m * ppm).toFloat()
                c.drawLine(x, oy, x, oy + H, if (m % 5.0 == 0.0) gp5 else gp)
                m += 1.0
            }
            m = 0.0
            while (m <= plan.heightM + 1e-6) {
                val y = oy + (m * ppm).toFloat()
                c.drawLine(ox, y, ox + W, y, if (m % 5.0 == 0.0) gp5 else gp)
                m += 1.0
            }
        }

        val wallW = maxOf(2f, ppm * 0.25f)
        val roomStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(52, 64, 58); style = Paint.Style.STROKE; strokeWidth = wallW; strokeJoin = Paint.Join.MITER }
        val roomFill = Paint(Paint.ANTI_ALIAS_FLAG)
        val sel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(244, 163, 0); style = Paint.Style.STROKE; strokeWidth = wallW + 4f }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(30, 60, 48); textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val labelSmall = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(95, 107, 102); textAlign = Paint.Align.CENTER }

        plan.rooms.forEachIndexed { i, r ->
            val rect = RectF(ox + (r.x * ppm).toFloat(), oy + (r.y * ppm).toFloat(), ox + ((r.x + r.w) * ppm).toFloat(), oy + ((r.y + r.h) * ppm).toFloat())
            roomFill.color = roomFills[i % roomFills.size]
            c.drawRect(rect, roomFill)
            c.drawRect(rect, if (r.id == selectedId) sel else roomStroke)
            if (labels && (r.name.isNotBlank() || plan.showDims)) {
                val size = (ppm * 0.9f).coerceIn(10f, 60f).coerceAtMost(rect.width() / maxOf(4, r.name.length) * 1.6f)
                label.textSize = size
                c.drawText(r.name, rect.centerX(), rect.centerY() + size / 3f, label)
                if (plan.showDims) {
                    labelSmall.textSize = size * 0.6f
                    c.drawText("%.1f × %.1f m · %.0f m²".format(r.w, r.h, r.w * r.h), rect.centerX(), rect.centerY() + size * 1.1f, labelSmall)
                }
            }
        }

        val wallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(52, 64, 58); strokeWidth = wallW; strokeCap = Paint.Cap.SQUARE }
        for (w in plan.walls) {
            c.drawLine(ox + (w.x1 * ppm).toFloat(), oy + (w.y1 * ppm).toFloat(), ox + (w.x2 * ppm).toFloat(), oy + (w.y2 * ppm).toFloat(), if (w.id == selectedId) sel else wallPaint)
        }

        val gap = Paint().apply { color = Color.WHITE; strokeWidth = wallW + 2f; strokeCap = Paint.Cap.BUTT }
        val leaf = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(11, 110, 79); style = Paint.Style.STROKE; strokeWidth = maxOf(1.5f, wallW * 0.35f) }
        for (d in plan.doors) {
            val cx = ox + (d.x * ppm).toFloat(); val cy = oy + (d.y * ppm).toFloat()
            val half = (d.width * ppm / 2).toFloat()
            if (d.horizontal) {
                c.drawLine(cx - half, cy, cx + half, cy, gap)
                val path = Path(); path.moveTo(cx - half, cy); path.lineTo(cx - half, cy - 2 * half)
                path.addArc(RectF(cx - half - 2 * half, cy - 2 * half, cx - half + 2 * half, cy + 2 * half), 270f, 90f)
                c.drawPath(path, if (d.id == selectedId) sel else leaf)
            } else {
                c.drawLine(cx, cy - half, cx, cy + half, gap)
                val path = Path(); path.moveTo(cx, cy - half); path.lineTo(cx + 2 * half, cy - half)
                path.addArc(RectF(cx - 2 * half, cy - half - 2 * half, cx + 2 * half, cy - half + 2 * half), 0f, 90f)
                c.drawPath(path, if (d.id == selectedId) sel else leaf)
            }
        }

        val border = Paint().apply { color = Color.rgb(160, 170, 166); style = Paint.Style.STROKE; strokeWidth = 2f }
        c.drawRect(ox, oy, ox + W, oy + H, border)
    }
}
