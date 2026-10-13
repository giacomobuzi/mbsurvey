package it.buzi.mbsurvey.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import it.buzi.mbsurvey.model.AccessPoint
import it.buzi.mbsurvey.model.Note
import it.buzi.mbsurvey.model.Survey
import kotlin.math.max
import kotlin.math.min

/**
 * Shows a floor plan with pinch-zoom and pan, and overlays heatmap, survey paths, access points and
 * calibration markers. Taps are reported in floor-plan pixel coordinates.
 */
class FloorPlanView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var floorBitmap: Bitmap? = null
        set(value) { field = value; fitToScreen(); invalidate() }
    var heatmap: Bitmap? = null
        set(value) { field = value; invalidate() }
    var heatmapCellPx: Double = 1.0
    var floorWidth: Int = 1
    var floorHeight: Int = 1

    var surveys: List<Survey> = emptyList()
        set(value) { field = value; invalidate() }
    var liveWaypoints: List<PointF> = emptyList()
        set(value) { field = value; invalidate() }
    var accessPoints: List<AccessPoint> = emptyList()
        set(value) { field = value; invalidate() }
    var calibrationPoints: List<PointF> = emptyList()
        set(value) { field = value; invalidate() }
    var notes: List<Note> = emptyList()
        set(value) { field = value; invalidate() }
    var showNotes = true
        set(value) { field = value; invalidate() }
    var showPath = true
        set(value) { field = value; invalidate() }
    var showAps = true
        set(value) { field = value; invalidate() }
    var pendingTap: PointF? = null
        set(value) { field = value; invalidate() }
    /** Live position (camera survey): floor px + heading in map degrees (0 = up). */
    var livePosition: PointF? = null
        set(value) { field = value; invalidate() }
    var liveHeadingDeg: Float = 0f
        set(value) { field = value; invalidate() }
    var compact = false

    var onTap: ((x: Double, y: Double) -> Unit)? = null

    private val matrix = Matrix()
    private val inverse = Matrix()
    private var minScale = 0.1f
    private var fitted = false

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val heatPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 20, 90, 200); strokeWidth = 4f; style = Paint.Style.STROKE
    }
    private val livePathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 220, 40, 40); strokeWidth = 5f; style = Paint.Style.STROKE
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 20, 90, 200) }
    private val livePointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(240, 220, 40, 40) }
    private val apPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 11, 110, 79) }
    private val apOtherPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 120, 120, 120) }
    private val apStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 3f; style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 28f; isFakeBoldText = true }
    private val textBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(180, 0, 0, 0) }
    private val calibPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(240, 244, 163, 0); strokeWidth = 4f; style = Paint.Style.STROKE }
    private val calibFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(240, 244, 163, 0) }
    private val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(244, 163, 0) }
    private val noteText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 26f; isFakeBoldText = true; textAlign = Paint.Align.CENTER }

    /** Screen distance in px from a floor point, used for hit-testing notes. */
    fun screenDistance(fx: Double, fy: Double, sx: Float, sy: Float): Float {
        val p = toScreen(fx.toFloat(), fy.toFloat())
        return kotlin.math.hypot(p.x - sx, p.y - sy)
    }

    /** Note whose pin is closest to the floor point (within 40 screen px), if any. */
    fun noteAt(fx: Double, fy: Double): Note? {
        val p = toScreen(fx.toFloat(), fy.toFloat())
        return notes.minByOrNull { screenDistance(it.x, it.y, p.x, p.y) }?.takeIf { screenDistance(it.x, it.y, p.x, p.y) < 40f }
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val s = d.scaleFactor
            val cur = currentScale()
            val target = (cur * s).coerceIn(minScale, 12f)
            val f = target / cur
            matrix.postScale(f, f, d.focusX, d.focusY)
            invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            matrix.postTranslate(-dx, -dy)
            invalidate()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val p = toFloor(e.x, e.y) ?: return true
            if (p.x < 0 || p.y < 0 || p.x > floorWidth || p.y > floorHeight) return true
            onTap?.invoke(p.x.toDouble(), p.y.toDouble())
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val cur = currentScale()
            val target = if (cur < minScale * 2.5f) cur * 2.5f else minScale
            val f = target / cur
            matrix.postScale(f, f, e.x, e.y)
            if (target == minScale) fitToScreen()
            invalidate()
            return true
        }
    })

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitToScreen()
    }

    fun fitToScreen() {
        val bmp = floorBitmap ?: return
        if (width == 0 || height == 0) return
        val sx = width.toFloat() / bmp.width
        val sy = height.toFloat() / bmp.height
        val s = min(sx, sy) * 0.98f
        minScale = s * 0.5f
        matrix.reset()
        matrix.postScale(s, s)
        matrix.postTranslate((width - bmp.width * s) / 2f, (height - bmp.height * s) / 2f)
        fitted = true
        invalidate()
    }

    private fun currentScale(): Float {
        val v = FloatArray(9); matrix.getValues(v); return v[Matrix.MSCALE_X]
    }

    /** Scale from floor-plan pixels to the displayed bitmap (bitmap may be downsampled). */
    private fun bitmapScale(): Float {
        val bmp = floorBitmap ?: return 1f
        return bmp.width.toFloat() / max(1, floorWidth)
    }

    private fun toFloor(sx: Float, sy: Float): PointF? {
        if (!matrix.invert(inverse)) return null
        val pts = floatArrayOf(sx, sy)
        inverse.mapPoints(pts)
        val bs = bitmapScale()
        return PointF(pts[0] / bs, pts[1] / bs)
    }

    private fun toScreen(fx: Float, fy: Float): PointF {
        val bs = bitmapScale()
        val pts = floatArrayOf(fx * bs, fy * bs)
        matrix.mapPoints(pts)
        return PointF(pts[0], pts[1])
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = floorBitmap ?: return
        if (!fitted) fitToScreen()
        canvas.save()
        canvas.concat(matrix)
        canvas.drawBitmap(bmp, 0f, 0f, bmpPaint)
        val bs = bitmapScale()
        heatmap?.let { h ->
            val dst = RectF(0f, 0f, (h.width * heatmapCellPx * bs).toFloat(), (h.height * heatmapCellPx * bs).toFloat())
            canvas.drawBitmap(h, null, dst, heatPaint)
        }
        canvas.restore()

        val scale = currentScale()
        val r = 8f + 2f * min(scale / minScale, 3f)

        if (showPath) {
            for (s in surveys) {
                var prev: PointF? = null
                for (wp in s.waypoints) {
                    val p = toScreen(wp.x.toFloat(), wp.y.toFloat())
                    if (prev != null) canvas.drawLine(prev.x, prev.y, p.x, p.y, pathPaint)
                    canvas.drawCircle(p.x, p.y, r * 0.6f, pointPaint)
                    prev = p
                }
            }
        }
        var prev: PointF? = null
        for (wp in liveWaypoints) {
            val p = toScreen(wp.x, wp.y)
            if (prev != null) canvas.drawLine(prev.x, prev.y, p.x, p.y, livePathPaint)
            canvas.drawCircle(p.x, p.y, r * 0.8f, livePointPaint)
            prev = p
        }
        pendingTap?.let { t ->
            val p = toScreen(t.x, t.y)
            canvas.drawCircle(p.x, p.y, r * 1.3f, calibPaint)
        }

        if (showAps) {
            for (ap in accessPoints) {
                val x = ap.x ?: continue
                val y = ap.y ?: continue
                val p = toScreen(x.toFloat(), y.toFloat())
                canvas.drawCircle(p.x, p.y, r * 1.4f, if (ap.mine) apPaint else apOtherPaint)
                canvas.drawCircle(p.x, p.y, r * 1.4f, apStroke)
                val label = ap.name
                val tw = textPaint.measureText(label)
                canvas.drawRoundRect(p.x + r * 1.6f, p.y - 20f, p.x + r * 1.6f + tw + 12f, p.y + 14f, 6f, 6f, textBg)
                canvas.drawText(label, p.x + r * 1.6f + 6f, p.y + 6f, textPaint)
            }
        }

        if (showNotes) {
            notes.forEachIndexed { i, n ->
                val p = toScreen(n.x.toFloat(), n.y.toFloat())
                // pin: circle + small tail
                canvas.drawCircle(p.x, p.y - 22f, 20f, notePaint)
                val tail = android.graphics.Path().apply { moveTo(p.x - 10f, p.y - 10f); lineTo(p.x + 10f, p.y - 10f); lineTo(p.x, p.y + 4f); close() }
                canvas.drawPath(tail, notePaint)
                canvas.drawText((i + 1).toString(), p.x, p.y - 13f, noteText)
                if (n.photoFile != null) canvas.drawCircle(p.x + 16f, p.y - 36f, 6f, apStroke)
            }
        }

        livePosition?.let { lp ->
            val p = toScreen(lp.x, lp.y)
            val rr = if (compact) 10f else 18f
            val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 30, 100, 200) }
            canvas.drawCircle(p.x, p.y, rr * 2.2f, glow)
            val cone = android.graphics.Path()
            val h = Math.toRadians(liveHeadingDeg.toDouble())
            val len = rr * 3.2f
            val a1 = h - Math.toRadians(25.0); val a2 = h + Math.toRadians(25.0)
            cone.moveTo(p.x, p.y)
            cone.lineTo(p.x + (Math.sin(a1) * len).toFloat(), p.y - (Math.cos(a1) * len).toFloat())
            cone.lineTo(p.x + (Math.sin(h) * len * 1.15).toFloat(), p.y - (Math.cos(h) * len * 1.15).toFloat())
            cone.lineTo(p.x + (Math.sin(a2) * len).toFloat(), p.y - (Math.cos(a2) * len).toFloat())
            cone.close()
            canvas.drawPath(cone, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(120, 30, 100, 200) })
            canvas.drawCircle(p.x, p.y, rr, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 100, 200) })
            canvas.drawCircle(p.x, p.y, rr, apStroke)
        }

        if (calibrationPoints.isNotEmpty()) {
            var cp: PointF? = null
            for (c in calibrationPoints) {
                val p = toScreen(c.x, c.y)
                canvas.drawCircle(p.x, p.y, r * 1.2f, calibFill)
                if (cp != null) canvas.drawLine(cp.x, cp.y, p.x, p.y, calibPaint)
                cp = p
            }
        }
    }
}
