package it.buzi.wifisurvey.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import it.buzi.wifisurvey.model.Door
import it.buzi.wifisurvey.model.PlanDrawing
import it.buzi.wifisurvey.model.Room
import it.buzi.wifisurvey.model.Wall
import it.buzi.wifisurvey.plan.PlanRenderer
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Touch editor for a PlanDrawing: draw rooms and walls by dragging, place doors by tapping on a wall,
 * select/move/resize with one finger, pan/zoom with two fingers (or one finger in select mode on empty space).
 */
class PlanEditorView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    enum class Tool { SELECT, ROOM, WALL, DOOR }

    var plan: PlanDrawing = PlanDrawing()
        set(value) { field = value; fitToScreen(); invalidate() }
    var tool: Tool = Tool.SELECT
        set(value) { field = value; selectedId = null; invalidate() }
    var selectedId: String? = null
        set(value) { field = value; onSelectionChanged?.invoke(value); invalidate() }
    var snapM = 0.5

    var onRoomCreated: ((Room) -> Unit)? = null
    var onRoomTapped: ((Room) -> Unit)? = null
    var onSelectionChanged: ((String?) -> Unit)? = null
    var onChanged: (() -> Unit)? = null
    /** Called when a gesture that may modify the plan starts; onChanged or onChangeCancelled follows. */
    var onBeforeChange: (() -> Unit)? = null
    var onChangeCancelled: (() -> Unit)? = null
    private var pendingChange = false

    private fun beginChange() { if (!pendingChange) { pendingChange = true; onBeforeChange?.invoke() } }
    private fun commitChange() { pendingChange = false; onChanged?.invoke() }
    private fun cancelChange() { if (pendingChange) { pendingChange = false; onChangeCancelled?.invoke() } }

    private var ppm = 10f      // px per metre on screen
    private var ox = 0f
    private var oy = 0f
    private var fitted = false

    // drag state
    private var dragging = false
    private var dragStartMx = 0.0
    private var dragStartMy = 0.0
    private var lastSx = 0f
    private var lastSy = 0f
    private var draft: Any? = null                 // Room or Wall being drawn
    private var moveTarget: Any? = null
    private var resizeCorner = -1                  // 0..3 for room corners
    private var origRoom: DoubleArray? = null
    private var multiTouch = false

    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(244, 163, 0) }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f }
    private val draftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 11, 110, 79); style = Paint.Style.STROKE; strokeWidth = 4f }
    private val draftFill = Paint().apply { color = Color.argb(60, 11, 110, 79) }
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(11, 110, 79); textSize = 34f; isFakeBoldText = true }
    private val dimBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 255, 255, 255) }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val f = d.scaleFactor
            val newPpm = (ppm * f).coerceIn(2f, 400f)
            val real = newPpm / ppm
            ox = d.focusX - (d.focusX - ox) * real
            oy = d.focusY - (d.focusY - oy) * real
            ppm = newPpm
            invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onTap(toMx(e.x), toMy(e.y))
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val r = roomAt(toMx(e.x), toMy(e.y))
            if (r != null) onRoomTapped?.invoke(r)
            return true
        }
    })

    // ---------------- coordinates ----------------

    private fun toMx(sx: Float): Double = ((sx - ox) / ppm).toDouble()
    private fun toMy(sy: Float): Double = ((sy - oy) / ppm).toDouble()
    private fun toSx(mx: Double): Float = ox + (mx * ppm).toFloat()
    private fun toSy(my: Double): Float = oy + (my * ppm).toFloat()
    private fun snap(v: Double): Double = round(v / snapM) * snapM

    fun fitToScreen() {
        if (width == 0 || height == 0) return
        val pad = 40f
        ppm = min((width - 2 * pad) / plan.widthM, (height - 2 * pad) / plan.heightM).toFloat()
        ox = ((width - plan.widthM * ppm) / 2).toFloat()
        oy = ((height - plan.heightM * ppm) / 2).toFloat()
        fitted = true
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitToScreen()
    }

    // ---------------- hit testing ----------------

    private fun roomAt(mx: Double, my: Double): Room? =
        plan.rooms.lastOrNull { mx >= it.x && mx <= it.x + it.w && my >= it.y && my <= it.y + it.h }

    private fun wallAt(mx: Double, my: Double, tolM: Double): Wall? =
        plan.walls.lastOrNull { distToSegment(mx, my, it.x1, it.y1, it.x2, it.y2) <= tolM }

    private fun doorAt(mx: Double, my: Double, tolM: Double): Door? =
        plan.doors.lastOrNull { hypot(it.x - mx, it.y - my) <= max(tolM, it.width / 2) }

    private fun distToSegment(px: Double, py: Double, x1: Double, y1: Double, x2: Double, y2: Double): Double {
        val dx = x2 - x1; val dy = y2 - y1
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((px - x1) * dx + (py - y1) * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(px - (x1 + t * dx), py - (y1 + t * dy))
    }

    /** Nearest room edge or wall to (mx,my) for door placement: returns (x, y, horizontal) or null. */
    private fun nearestEdge(mx: Double, my: Double, tolM: Double): Triple<Double, Double, Boolean>? {
        var best: Triple<Double, Double, Boolean>? = null
        var bestD = tolM
        fun consider(x1: Double, y1: Double, x2: Double, y2: Double) {
            val d = distToSegment(mx, my, x1, y1, x2, y2)
            if (d < bestD) {
                bestD = d
                val horizontal = abs(y2 - y1) < abs(x2 - x1)
                val dx = x2 - x1; val dy = y2 - y1
                val len2 = dx * dx + dy * dy
                val t = if (len2 == 0.0) 0.0 else (((mx - x1) * dx + (my - y1) * dy) / len2).coerceIn(0.0, 1.0)
                best = Triple(x1 + t * dx, y1 + t * dy, horizontal)
            }
        }
        for (r in plan.rooms) {
            consider(r.x, r.y, r.x + r.w, r.y); consider(r.x, r.y + r.h, r.x + r.w, r.y + r.h)
            consider(r.x, r.y, r.x, r.y + r.h); consider(r.x + r.w, r.y, r.x + r.w, r.y + r.h)
        }
        for (w in plan.walls) consider(w.x1, w.y1, w.x2, w.y2)
        return best
    }

    private fun cornerAt(r: Room, sx: Float, sy: Float): Int {
        val corners = listOf(Pair(r.x, r.y), Pair(r.x + r.w, r.y), Pair(r.x + r.w, r.y + r.h), Pair(r.x, r.y + r.h))
        for ((i, c) in corners.withIndex()) {
            if (hypot((toSx(c.first) - sx).toDouble(), (toSy(c.second) - sy).toDouble()) < 48) return i
        }
        return -1
    }

    // ---------------- interaction ----------------

    private fun onTap(mx: Double, my: Double) {
        when (tool) {
            Tool.DOOR -> {
                val tol = 30.0 / ppm
                val edge = nearestEdge(mx, my, tol) ?: return
                beginChange()
                val d = Door(x = snap(edge.first), y = snap(edge.second), horizontal = edge.third)
                // keep the snapped point on the edge line
                if (edge.third) d.y = edge.second else d.x = edge.first
                plan.doors.add(d)
                selectedId = d.id
                commitChange()
            }
            Tool.SELECT -> {
                val tol = 24.0 / ppm
                val id = doorAt(mx, my, tol)?.id ?: wallAt(mx, my, tol)?.id ?: roomAt(mx, my)?.id
                selectedId = id
            }
            else -> {}
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        gestureDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                multiTouch = false
                lastSx = e.x; lastSy = e.y
                dragStartMx = toMx(e.x); dragStartMy = toMy(e.y)
                dragging = false
                draft = null; moveTarget = null; resizeCorner = -1
                if (tool == Tool.SELECT) {
                    val selRoom = plan.rooms.firstOrNull { it.id == selectedId }
                    if (selRoom != null) {
                        resizeCorner = cornerAt(selRoom, e.x, e.y)
                        if (resizeCorner >= 0) { moveTarget = selRoom; origRoom = doubleArrayOf(selRoom.x, selRoom.y, selRoom.w, selRoom.h) }
                    }
                    if (moveTarget == null) {
                        val tol = 24.0 / ppm
                        moveTarget = doorAt(dragStartMx, dragStartMy, tol) ?: wallAt(dragStartMx, dragStartMy, tol) ?: roomAt(dragStartMx, dragStartMy)
                        (moveTarget as? Room)?.let { origRoom = doubleArrayOf(it.x, it.y, it.w, it.h) }
                        (moveTarget as? Wall)?.let { origRoom = doubleArrayOf(it.x1, it.y1, it.x2, it.y2) }
                        (moveTarget as? Door)?.let { origRoom = doubleArrayOf(it.x, it.y, 0.0, 0.0) }
                    }
                    if (moveTarget != null) beginChange()
                } else if (tool == Tool.ROOM || tool == Tool.WALL) beginChange()
            }
            MotionEvent.ACTION_POINTER_DOWN -> { multiTouch = true; draft = null; moveTarget = null; cancelChange() }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount > 1 || multiTouch) {
                    if (!scaleDetector.isInProgress && e.pointerCount == 1) { ox += e.x - lastSx; oy += e.y - lastSy }
                    lastSx = e.x; lastSy = e.y
                    invalidate(); return true
                }
                val mx = toMx(e.x); val my = toMy(e.y)
                if (!dragging && hypot((e.x - lastSx).toDouble(), (e.y - lastSy).toDouble()) < 12) return true
                dragging = true
                when (tool) {
                    Tool.ROOM -> {
                        val x0 = snap(dragStartMx); val y0 = snap(dragStartMy); val x1 = snap(mx); val y1 = snap(my)
                        val r = (draft as? Room) ?: Room(name = "", x = x0, y = y0, w = 0.0, h = 0.0).also { draft = it }
                        r.x = min(x0, x1).coerceIn(0.0, plan.widthM); r.y = min(y0, y1).coerceIn(0.0, plan.heightM)
                        r.w = (abs(x1 - x0)).coerceAtMost(plan.widthM - r.x); r.h = (abs(y1 - y0)).coerceAtMost(plan.heightM - r.y)
                    }
                    Tool.WALL -> {
                        val x0 = snap(dragStartMx); val y0 = snap(dragStartMy)
                        var x1 = snap(mx); var y1 = snap(my)
                        // snap to axis when nearly horizontal/vertical
                        if (abs(x1 - x0) > 4 * abs(y1 - y0)) y1 = y0 else if (abs(y1 - y0) > 4 * abs(x1 - x0)) x1 = x0
                        val w = (draft as? Wall) ?: Wall(x1 = x0, y1 = y0, x2 = x1, y2 = y1).also { draft = it }
                        w.x2 = x1.coerceIn(0.0, plan.widthM); w.y2 = y1.coerceIn(0.0, plan.heightM)
                    }
                    Tool.SELECT -> {
                        val t = moveTarget
                        val o = origRoom
                        if (t == null || o == null) {
                            ox += e.x - lastSx; oy += e.y - lastSy
                        } else {
                            val dx = snap(mx - dragStartMx); val dy = snap(my - dragStartMy)
                            when (t) {
                                is Room -> if (resizeCorner >= 0) resize(t, o, resizeCorner, dx, dy) else {
                                    t.x = (o[0] + dx).coerceIn(0.0, plan.widthM - t.w); t.y = (o[1] + dy).coerceIn(0.0, plan.heightM - t.h)
                                }
                                is Wall -> { t.x1 = o[0] + dx; t.y1 = o[1] + dy; t.x2 = o[2] + dx; t.y2 = o[3] + dy }
                                is Door -> { t.x = o[0] + dx; t.y = o[1] + dy }
                            }
                        }
                    }
                    Tool.DOOR -> { ox += e.x - lastSx; oy += e.y - lastSy }
                }
                lastSx = e.x; lastSy = e.y
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging && !multiTouch) {
                    when (val d = draft) {
                        is Room -> if (d.w >= snapM && d.h >= snapM) { plan.rooms.add(d); selectedId = d.id; commitChange(); onRoomCreated?.invoke(d) } else cancelChange()
                        is Wall -> if (hypot(d.x2 - d.x1, d.y2 - d.y1) >= snapM) { plan.walls.add(d); selectedId = d.id; commitChange() } else cancelChange()
                        else -> if (moveTarget != null) commitChange() else cancelChange()
                    }
                } else cancelChange()
                draft = null; moveTarget = null; resizeCorner = -1; dragging = false
                invalidate()
            }
        }
        return true
    }

    private fun resize(r: Room, o: DoubleArray, corner: Int, dx: Double, dy: Double) {
        var x0 = o[0]; var y0 = o[1]; var x1 = o[0] + o[2]; var y1 = o[1] + o[3]
        when (corner) {
            0 -> { x0 += dx; y0 += dy }
            1 -> { x1 += dx; y0 += dy }
            2 -> { x1 += dx; y1 += dy }
            3 -> { x0 += dx; y1 += dy }
        }
        r.x = min(x0, x1).coerceAtLeast(0.0); r.y = min(y0, y1).coerceAtLeast(0.0)
        r.w = max(snapM, abs(x1 - x0)).coerceAtMost(plan.widthM - r.x); r.h = max(snapM, abs(y1 - y0)).coerceAtMost(plan.heightM - r.y)
    }

    fun deleteSelected(): Boolean {
        val id = selectedId ?: return false
        beginChange()
        val removed = plan.rooms.removeAll { it.id == id } || plan.walls.removeAll { it.id == id } || plan.doors.removeAll { it.id == id }
        if (removed) { selectedId = null; commitChange() } else cancelChange()
        return removed
    }

    fun selectedRoom(): Room? = plan.rooms.firstOrNull { it.id == selectedId }

    // ---------------- drawing ----------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!fitted) fitToScreen()
        canvas.drawColor(Color.rgb(232, 236, 234))
        PlanRenderer.draw(canvas, plan, ppm, ox, oy, grid = true, selectedId = selectedId, labels = true)

        when (val d = draft) {
            is Room -> {
                val rect = RectF(toSx(d.x), toSy(d.y), toSx(d.x + d.w), toSy(d.y + d.h))
                canvas.drawRect(rect, draftFill); canvas.drawRect(rect, draftPaint)
                dimension(canvas, "%.1f × %.1f m".format(d.w, d.h), rect.centerX(), rect.top - 16f)
            }
            is Wall -> {
                canvas.drawLine(toSx(d.x1), toSy(d.y1), toSx(d.x2), toSy(d.y2), draftPaint)
                dimension(canvas, "%.1f m".format(hypot(d.x2 - d.x1, d.y2 - d.y1)), (toSx(d.x1) + toSx(d.x2)) / 2, (toSy(d.y1) + toSy(d.y2)) / 2 - 20f)
            }
        }

        selectedRoom()?.let { r ->
            for (c in listOf(Pair(r.x, r.y), Pair(r.x + r.w, r.y), Pair(r.x + r.w, r.y + r.h), Pair(r.x, r.y + r.h))) {
                canvas.drawCircle(toSx(c.first), toSy(c.second), 16f, handlePaint)
                canvas.drawCircle(toSx(c.first), toSy(c.second), 16f, handleStroke)
            }
        }
    }

    private fun dimension(c: Canvas, text: String, cx: Float, cy: Float) {
        val w = dimPaint.measureText(text)
        c.drawRoundRect(cx - w / 2 - 12f, cy - 34f, cx + w / 2 + 12f, cy + 10f, 10f, 10f, dimBg)
        c.drawText(text, cx - w / 2, cy, dimPaint)
    }
}
