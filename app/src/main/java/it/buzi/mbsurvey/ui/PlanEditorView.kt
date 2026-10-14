package it.buzi.mbsurvey.ui

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
import it.buzi.mbsurvey.model.Door
import it.buzi.mbsurvey.model.PlanDrawing
import it.buzi.mbsurvey.model.Room
import it.buzi.mbsurvey.model.Wall
import it.buzi.mbsurvey.plan.PlanRenderer
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
    var onDoorTapped: ((Door) -> Unit)? = null
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
            val mx = toMx(e.x); val my = toMy(e.y)
            val d = doorAt(mx, my, 24.0 / ppm)
            if (d != null) { onDoorTapped?.invoke(d); return true }
            val r = roomAt(mx, my)
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

    private var wallEnd = -1 // 0/1 when dragging a wall endpoint

    private fun wallEndAt(w: Wall, sx: Float, sy: Float): Int {
        if (hypot((toSx(w.x1) - sx).toDouble(), (toSy(w.y1) - sy).toDouble()) < 48) return 0
        if (hypot((toSx(w.x2) - sx).toDouble(), (toSy(w.y2) - sy).toDouble()) < 48) return 1
        return -1
    }

    /** Visible centre of the plan in metres (clamped inside the plan). */
    private fun viewCentreM(): Pair<Double, Double> {
        val cx = toMx(width / 2f).coerceIn(0.0, plan.widthM)
        val cy = toMy(height / 2f).coerceIn(0.0, plan.heightM)
        return Pair(cx, cy)
    }

    /** Creates a default-sized room near the centre of the view, offset so repeated taps don't overlap exactly. */
    fun addRoom(name: String): Room {
        beginChange()
        val w = (plan.widthM / 4).coerceIn(2.0, 12.0).let { snap(it) }
        val h = (plan.heightM / 4).coerceIn(2.0, 10.0).let { snap(it) }
        val (cx, cy) = viewCentreM()
        val k = plan.rooms.size % 4
        val r = Room(name = name, x = snap(cx - w / 2 + k), y = snap(cy - h / 2 + k), w = w, h = h)
        r.x = r.x.coerceIn(0.0, plan.widthM - w); r.y = r.y.coerceIn(0.0, plan.heightM - h)
        plan.rooms.add(r)
        // find a free spot: scan the grid for the first position without overlap
        if (overlapsOthers(r)) {
            var placed = false
            var yy = 0.0
            while (yy + r.h <= plan.heightM + 1e-6 && !placed) {
                var xx = 0.0
                while (xx + r.w <= plan.widthM + 1e-6) {
                    r.x = xx; r.y = yy
                    if (!overlapsOthers(r)) { placed = true; break }
                    xx += snapM
                }
                yy += snapM
            }
            if (!placed) { r.w = snap(min(r.w, 3.0)); r.h = snap(min(r.h, 3.0)); r.x = 0.0; r.y = 0.0; fitRoom(r, resizing = true) }
        }
        selectedId = r.id
        commitChange()
        return r
    }

    /** Creates a horizontal wall near the centre of the view. */
    fun addWall(): Wall {
        beginChange()
        val len = (plan.widthM / 3).coerceIn(2.0, 15.0).let { snap(it) }
        val (cx, cy) = viewCentreM()
        val k = (plan.walls.size % 4) * snapM
        val w = Wall(x1 = snap(cx - len / 2), y1 = snap(cy + k), x2 = snap(cx + len / 2), y2 = snap(cy + k))
        clampWall(w)
        plan.walls.add(w)
        selectedId = w.id
        commitChange()
        return w
    }

    private fun clampWall(w: Wall) {
        w.x1 = w.x1.coerceIn(0.0, plan.widthM); w.x2 = w.x2.coerceIn(0.0, plan.widthM)
        w.y1 = w.y1.coerceIn(0.0, plan.heightM); w.y2 = w.y2.coerceIn(0.0, plan.heightM)
    }

    private fun clampDoor(d: Door) {
        val half = d.width / 2
        if (d.horizontal) { d.x = d.x.coerceIn(half, plan.widthM - half); d.y = d.y.coerceIn(0.0, plan.heightM) }
        else { d.y = d.y.coerceIn(half, plan.heightM - half); d.x = d.x.coerceIn(0.0, plan.widthM) }
    }

    fun selectedDoor(): Door? = plan.doors.firstOrNull { it.id == selectedId }
    fun selectedWall(): Wall? = plan.walls.firstOrNull { it.id == selectedId }

    fun setDoorWidth(d: Door, width: Double) {
        beginChange(); d.width = width.coerceIn(0.5, 10.0); clampDoor(d); commitChange()
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
                clampDoor(d)
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
                draft = null; moveTarget = null; resizeCorner = -1; wallEnd = -1
                if (tool == Tool.SELECT) {
                    val selRoom = plan.rooms.firstOrNull { it.id == selectedId }
                    if (selRoom != null) {
                        resizeCorner = cornerAt(selRoom, e.x, e.y)
                        if (resizeCorner >= 0) { moveTarget = selRoom; origRoom = doubleArrayOf(selRoom.x, selRoom.y, selRoom.w, selRoom.h) }
                    }
                    val selWall = selectedWall()
                    if (moveTarget == null && selWall != null) {
                        wallEnd = wallEndAt(selWall, e.x, e.y)
                        if (wallEnd >= 0) { moveTarget = selWall; origRoom = doubleArrayOf(selWall.x1, selWall.y1, selWall.x2, selWall.y2) }
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
                                is Room -> {
                                    if (resizeCorner >= 0) resize(t, o, resizeCorner, dx, dy) else {
                                        t.x = (o[0] + dx).coerceIn(0.0, max(0.0, plan.widthM - t.w)); t.y = (o[1] + dy).coerceIn(0.0, max(0.0, plan.heightM - t.h))
                                    }
                                    fitRoom(t, resizing = resizeCorner >= 0)
                                }
                                is Wall -> {
                                    when (wallEnd) {
                                        0 -> { t.x1 = o[0] + dx; t.y1 = o[1] + dy }
                                        1 -> { t.x2 = o[2] + dx; t.y2 = o[3] + dy }
                                        else -> { t.x1 = o[0] + dx; t.y1 = o[1] + dy; t.x2 = o[2] + dx; t.y2 = o[3] + dy }
                                    }
                                    clampWall(t)
                                }
                                is Door -> { t.x = o[0] + dx; t.y = o[1] + dy; clampDoor(t) }
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
                        is Room -> if (d.w >= snapM && d.h >= snapM) { plan.rooms.add(d); fitRoom(d, resizing = true); selectedId = d.id; commitChange(); onRoomCreated?.invoke(d) } else cancelChange()
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

    private val snapTolM = 0.3

    /** Keeps r inside the plan and free of overlaps with other rooms: edges snap to neighbours, overlaps are pushed out
     *  (when moving) or trimmed (when resizing). */
    private fun fitRoom(r: Room, resizing: Boolean) {
        val others = plan.rooms.filter { it !== r }
        // magnetic snapping to neighbour edges and plan borders
        for (o in others) {
            if (abs(r.x - (o.x + o.w)) < snapTolM) r.x = o.x + o.w
            if (abs((r.x + r.w) - o.x) < snapTolM) { if (resizing) r.w = o.x - r.x else r.x = o.x - r.w }
            if (abs(r.y - (o.y + o.h)) < snapTolM) r.y = o.y + o.h
            if (abs((r.y + r.h) - o.y) < snapTolM) { if (resizing) r.h = o.y - r.y else r.y = o.y - r.h }
            if (abs(r.x - o.x) < snapTolM) r.x = o.x
            if (abs(r.y - o.y) < snapTolM) r.y = o.y
        }
        if (abs(r.x + r.w - plan.widthM) < snapTolM) { if (resizing) r.w = plan.widthM - r.x else r.x = plan.widthM - r.w }
        if (abs(r.y + r.h - plan.heightM) < snapTolM) { if (resizing) r.h = plan.heightM - r.y else r.y = plan.heightM - r.h }
        if (abs(r.x) < snapTolM) r.x = 0.0
        if (abs(r.y) < snapTolM) r.y = 0.0
        // resolve remaining overlaps (a few passes)
        repeat(4) {
            var moved = false
            for (o in others) {
                val ox = min(r.x + r.w, o.x + o.w) - max(r.x, o.x)
                val oy = min(r.y + r.h, o.y + o.h) - max(r.y, o.y)
                if (ox <= 1e-6 || oy <= 1e-6) continue
                moved = true
                val rcx = r.x + r.w / 2; val ocx = o.x + o.w / 2
                val rcy = r.y + r.h / 2; val ocy = o.y + o.h / 2
                if (ox < oy) {
                    if (resizing) { if (rcx < ocx) r.w -= ox else { r.x += ox; r.w -= ox } }
                    else { if (rcx < ocx) r.x -= ox else r.x += ox }
                } else {
                    if (resizing) { if (rcy < ocy) r.h -= oy else { r.y += oy; r.h -= oy } }
                    else { if (rcy < ocy) r.y -= oy else r.y += oy }
                }
            }
            if (!moved) return@repeat
        }
        r.w = max(snapM, r.w); r.h = max(snapM, r.h)
        r.x = r.x.coerceIn(0.0, max(0.0, plan.widthM - r.w)); r.y = r.y.coerceIn(0.0, max(0.0, plan.heightM - r.h))
        r.w = min(r.w, plan.widthM - r.x); r.h = min(r.h, plan.heightM - r.y)
        // the border clamp may have pushed it back onto a neighbour: shrink until free
        for (o in others) {
            val ox = min(r.x + r.w, o.x + o.w) - max(r.x, o.x)
            val oy = min(r.y + r.h, o.y + o.h) - max(r.y, o.y)
            if (ox <= 1e-6 || oy <= 1e-6) continue
            if (ox < oy) { if (r.x + r.w / 2 < o.x + o.w / 2) r.w -= ox else { r.x += ox; r.w -= ox } }
            else { if (r.y + r.h / 2 < o.y + o.h / 2) r.h -= oy else { r.y += oy; r.h -= oy } }
            r.w = max(snapM, r.w); r.h = max(snapM, r.h)
        }
    }

    /** Fits every room inside the plan and removes overlaps (used after a plan resize and before saving). */
    fun validateAll(): Int {
        var fixed = 0
        for (r in plan.rooms) {
            val before = doubleArrayOf(r.x, r.y, r.w, r.h)
            if (r.w > plan.widthM) r.w = plan.widthM
            if (r.h > plan.heightM) r.h = plan.heightM
            fitRoom(r, resizing = true)
            if (before[0] != r.x || before[1] != r.y || before[2] != r.w || before[3] != r.h) fixed++
        }
        for (w in plan.walls) clampWall(w)
        for (d in plan.doors) clampDoor(d)
        invalidate()
        return fixed
    }

    /** True when r overlaps any other room (used to reject impossible numeric input). */
    fun overlapsOthers(r: Room): Boolean = plan.rooms.any { o -> o !== r && min(r.x + r.w, o.x + o.w) - max(r.x, o.x) > 1e-6 && min(r.y + r.h, o.y + o.h) - max(r.y, o.y) > 1e-6 }

    /** Applies numeric geometry to a room, fitting it inside the plan and away from other rooms. */
    fun setRoomGeometry(r: Room, x: Double, y: Double, w: Double, h: Double) {
        beginChange()
        r.x = x; r.y = y; r.w = max(snapM, w); r.h = max(snapM, h)
        fitRoom(r, resizing = true)
        commitChange()
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

        selectedWall()?.let { w ->
            for (c in listOf(Pair(w.x1, w.y1), Pair(w.x2, w.y2))) {
                canvas.drawCircle(toSx(c.first), toSy(c.second), 16f, handlePaint)
                canvas.drawCircle(toSx(c.first), toSy(c.second), 16f, handleStroke)
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
