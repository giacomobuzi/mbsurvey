package it.buzi.mbsurvey.ar

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Position source for the camera survey: step detector + rotation vector (compass) dead reckoning,
 * plus a stillness detector on the accelerometer.
 *
 * Map coordinates are floor-plan pixels (y down). headingOffsetDeg converts the compass azimuth to a
 * map bearing (0 = up on the map, clockwise). It is set by the one-tap calibration in the UI.
 */
class DeadReckoning(ctx: Context, private val metersPerPx: Double) : SensorEventListener {

    interface Listener {
        fun onPositionChanged(x: Double, y: Double, headingMapDeg: Float)
        fun onStillnessChanged(still: Boolean)
        fun onStep(count: Int)
    }

    var listener: Listener? = null
    var stepLengthM = 0.70
    var headingOffsetDeg = 0f
    var x = 0.0; private set
    var y = 0.0; private set
    var azimuthDeg = 0f; private set
    val headingMapDeg: Float get() = norm(azimuthDeg + headingOffsetDeg)
    var steps = 0; private set
    var anchored = false; private set
    var minX = 0.0; var minY = 0.0; var maxX = Double.MAX_VALUE; var maxY = Double.MAX_VALUE

    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rot = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val stepDetector = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private val accel = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val rotMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    // stillness: rolling energy of linear acceleration
    private val energy = FloatArray(40)
    private var ei = 0
    private var still = false
    private var stillSince = 0L
    var stillDelayMs = 2000L
    val isStill: Boolean get() = still
    val hasStepDetector: Boolean get() = stepDetector != null

    fun start() {
        rot?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        stepDetector?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
        accel?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() { sm.unregisterListener(this) }

    /** Sets the current position (initial anchor or correction). */
    fun anchor(px: Double, py: Double) {
        x = px; y = py; anchored = true
        listener?.onPositionChanged(x, y, headingMapDeg)
    }

    /** Calibrates the heading: the user is looking towards map point (tx, ty) from the current position. */
    fun calibrateHeadingTowards(tx: Double, ty: Double) {
        val bearing = Math.toDegrees(Math.atan2(tx - x, -(ty - y))).toFloat() // 0 = up, clockwise
        headingOffsetDeg = norm(bearing - azimuthDeg)
        listener?.onPositionChanged(x, y, headingMapDeg)
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotMatrix, e.values)
                // phone held upright in portrait, camera facing forward: remap so azimuth = camera direction
                val remapped = FloatArray(9)
                SensorManager.remapCoordinateSystem(rotMatrix, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
                SensorManager.getOrientation(remapped, orientation)
                val az = norm(Math.toDegrees(orientation[0].toDouble()).toFloat())
                // light smoothing on the circle
                val d = norm(az - azimuthDeg + 180f) - 180f
                azimuthDeg = norm(azimuthDeg + d * 0.25f)
                if (anchored) listener?.onPositionChanged(x, y, headingMapDeg)
            }
            Sensor.TYPE_STEP_DETECTOR -> {
                steps++
                listener?.onStep(steps)
                if (anchored) {
                    val h = Math.toRadians(headingMapDeg.toDouble())
                    val dpx = stepLengthM / metersPerPx
                    x = (x + sin(h) * dpx).coerceIn(minX, maxX)
                    y = (y - cos(h) * dpx).coerceIn(minY, maxY)
                    listener?.onPositionChanged(x, y, headingMapDeg)
                }
            }
            Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_ACCELEROMETER -> {
                var m = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
                if (e.sensor.type == Sensor.TYPE_ACCELEROMETER) m = kotlin.math.abs(m - SensorManager.GRAVITY_EARTH)
                energy[ei] = m; ei = (ei + 1) % energy.size
                val avg = energy.average()
                val now = System.currentTimeMillis()
                val quiet = avg < 0.35
                if (quiet) {
                    if (stillSince == 0L) stillSince = now
                    if (!still && now - stillSince >= stillDelayMs) { still = true; listener?.onStillnessChanged(true) }
                } else {
                    stillSince = 0L
                    if (still) { still = false; listener?.onStillnessChanged(false) }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun norm(d: Float): Float { var v = d % 360f; if (v < 0) v += 360f; return v }
}
