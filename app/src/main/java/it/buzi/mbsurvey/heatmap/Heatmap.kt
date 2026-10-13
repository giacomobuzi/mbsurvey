package it.buzi.mbsurvey.heatmap

import android.graphics.Bitmap
import android.graphics.Color
import it.buzi.mbsurvey.R
import it.buzi.mbsurvey.model.Band
import it.buzi.mbsurvey.model.Floor
import it.buzi.mbsurvey.model.Project
import it.buzi.mbsurvey.model.Scan
import it.buzi.mbsurvey.survey.SurveyEngine
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class Metric(val labelRes: Int, val unit: String, val lowerIsBetter: Boolean = false) {
    PRIMARY_RSSI(R.string.m_primary, "dBm"),
    SECONDARY_RSSI(R.string.m_secondary, "dBm"),
    SNR(R.string.m_snr, "dB"),
    AP_COUNT(R.string.m_apcount, "AP"),
    INTERFERENCE(R.string.m_interference, "BSSID", true),
    LATENCY(R.string.m_latency, "ms", true),
    PACKET_LOSS(R.string.m_loss, "%", true),
    PER_AP(R.string.m_perap, "dBm");

    fun label(ctx: android.content.Context): String = ctx.getString(labelRes)
}

/** Which scans feed the heatmap. */
class HeatmapFilter(
    val metric: Metric = Metric.PRIMARY_RSSI,
    val ssid: String? = null,
    val band: Band? = null,
    val bssid: String? = null,
    /** When set, only scans of these surveys are used. */
    val surveyIds: Set<String>? = null,
    /** Comparison mode: values are (surveyB - surveyA); legend is diverging. */
    val compareA: String? = null,
    val compareB: String? = null
) {
    val isCompare: Boolean get() = compareA != null && compareB != null
}

/** A rendered heatmap: a low-resolution grid scaled over the floor plan. */
class HeatmapResult(
    val bitmap: Bitmap,
    val cellPx: Double,
    val values: FloatArray,
    val cols: Int,
    val rows: Int,
    val coveredCells: Int,
    val min: Float,
    val max: Float,
    val avg: Float,
    val legend: List<Pair<Float, Int>>
)

object Heatmap {

    /** Value of the metric for one scan, or null when the scan has no data for the filter. */
    fun value(scan: Scan, f: HeatmapFilter, p: Project): Float? {
        val s = p.settings
        val v: Int? = when (f.metric) {
            Metric.PRIMARY_RSSI -> SurveyEngine.primaryRssi(scan, f.ssid, f.band)
            Metric.SECONDARY_RSSI -> SurveyEngine.secondaryRssi(scan, f.ssid, f.band)
            Metric.SNR -> SurveyEngine.snr(scan, f.ssid, f.band, s.noiseFloorDbm)
            Metric.AP_COUNT -> SurveyEngine.apCount(scan, f.ssid, f.band, s.apCountThreshold)
            Metric.INTERFERENCE -> SurveyEngine.channelInterference(scan, f.ssid, f.band, s.rssiMin)
            Metric.PER_AP -> f.bssid?.let { b -> SurveyEngine.bssidRssi(scan, b) ?: -100 }
            Metric.LATENCY -> return scan.rttMs?.toFloat() ?: (if (scan.lossPct == 100) 2000f else null)
            Metric.PACKET_LOSS -> scan.lossPct
        }
        return v?.toFloat()
    }

    /** Colour stops for a metric (value -> ARGB). Green = good, red = bad, as in Ekahau. */
    fun legend(metric: Metric, p: Project): List<Pair<Float, Int>> {
        val s = p.settings
        return when (metric) {
            Metric.PRIMARY_RSSI, Metric.SECONDARY_RSSI, Metric.PER_AP -> listOf(
                -90f to Color.rgb(120, 0, 0),
                s.rssiMin.toFloat() to Color.rgb(230, 40, 40),
                s.rssiOk.toFloat() to Color.rgb(255, 170, 0),
                s.rssiGood.toFloat() to Color.rgb(255, 240, 0),
                (s.rssiGood + 10).toFloat() to Color.rgb(90, 220, 60),
                -40f to Color.rgb(0, 140, 60)
            )
            Metric.SNR -> listOf(
                0f to Color.rgb(120, 0, 0), 10f to Color.rgb(230, 40, 40), 20f to Color.rgb(255, 170, 0),
                25f to Color.rgb(255, 240, 0), 35f to Color.rgb(90, 220, 60), 50f to Color.rgb(0, 140, 60)
            )
            Metric.AP_COUNT -> listOf(
                0f to Color.rgb(230, 40, 40), 1f to Color.rgb(255, 170, 0), 2f to Color.rgb(255, 240, 0),
                3f to Color.rgb(90, 220, 60), 5f to Color.rgb(0, 140, 60)
            )
            Metric.INTERFERENCE -> listOf(
                0f to Color.rgb(0, 140, 60), 1f to Color.rgb(90, 220, 60), 2f to Color.rgb(255, 240, 0),
                3f to Color.rgb(255, 170, 0), 5f to Color.rgb(230, 40, 40)
            )
            Metric.LATENCY -> {
                val g = s.latencyGoodMs.toFloat()
                listOf(0f to Color.rgb(0, 140, 60), g to Color.rgb(90, 220, 60), g * 2 to Color.rgb(255, 240, 0), g * 4 to Color.rgb(255, 170, 0), g * 10 to Color.rgb(230, 40, 40))
            }
            Metric.PACKET_LOSS -> listOf(
                0f to Color.rgb(0, 140, 60), 1f to Color.rgb(90, 220, 60), 5f to Color.rgb(255, 240, 0), 20f to Color.rgb(255, 170, 0), 50f to Color.rgb(230, 40, 40)
            )
        }
    }

    fun colorFor(v: Float, legend: List<Pair<Float, Int>>): Int {
        if (v <= legend.first().first) return legend.first().second
        if (v >= legend.last().first) return legend.last().second
        for (i in 1 until legend.size) {
            val (v0, c0) = legend[i - 1]
            val (v1, c1) = legend[i]
            if (v <= v1) {
                val t = if (v1 == v0) 0f else (v - v0) / (v1 - v0)
                return lerp(c0, c1, t)
            }
        }
        return legend.last().second
    }

    private fun lerp(c0: Int, c1: Int, t: Float): Int {
        val r = (Color.red(c0) + (Color.red(c1) - Color.red(c0)) * t).toInt()
        val g = (Color.green(c0) + (Color.green(c1) - Color.green(c0)) * t).toInt()
        val b = (Color.blue(c0) + (Color.blue(c1) - Color.blue(c0)) * t).toInt()
        return Color.rgb(r, g, b)
    }

    /**
     * Inverse-distance-weighted interpolation on a grid. Cells farther than radius from every sample
     * stay transparent. Target grid resolution ~ 260 cells on the long side.
     */
    fun render(p: Project, floor: Floor, filter: HeatmapFilter, alpha: Int = 150, radiusMetersOverride: Double? = null): HeatmapResult? {
        if (filter.isCompare) return renderCompare(p, floor, filter, alpha, radiusMetersOverride)
        val scans = SurveyEngine.locatedScans(p, floor.id).let { all ->
            val ids = filter.surveyIds
            if (ids == null) all else p.surveysOf(floor.id).filter { it.id in ids }.flatMap { it.locatedScans }
        }
        val pts = ArrayList<FloatArray>(scans.size)
        for (s in scans) {
            val v = value(s, filter, p) ?: continue
            pts.add(floatArrayOf(s.x!!.toFloat(), s.y!!.toFloat(), v))
        }
        if (pts.isEmpty()) return null

        val longSide = max(floor.widthPx, floor.heightPx).toDouble()
        val cellPx = max(1.0, longSide / 260.0)
        val cols = ceil(floor.widthPx / cellPx).toInt()
        val rows = ceil(floor.heightPx / cellPx).toInt()
        val mpp = if (floor.metersPerPx > 0) floor.metersPerPx else 0.05
        val radiusPx = ((radiusMetersOverride ?: p.settings.heatmapRadiusM) / mpp).toFloat()
        val r2 = radiusPx * radiusPx

        // Spatial hash of samples into buckets of radius size for speed.
        val bucket = max(radiusPx, 1f)
        val bcols = ceil(floor.widthPx / bucket).toInt() + 1
        val brows = ceil(floor.heightPx / bucket).toInt() + 1
        val buckets = HashMap<Int, MutableList<FloatArray>>()
        for (pt in pts) {
            val bx = (pt[0] / bucket).toInt().coerceIn(0, bcols - 1)
            val by = (pt[1] / bucket).toInt().coerceIn(0, brows - 1)
            buckets.getOrPut(by * bcols + bx) { mutableListOf() }.add(pt)
        }

        val values = FloatArray(cols * rows) { Float.NaN }
        var covered = 0
        var vmin = Float.MAX_VALUE; var vmax = -Float.MAX_VALUE; var vsum = 0.0
        for (row in 0 until rows) {
            val cy = (row + 0.5f) * cellPx.toFloat()
            for (col in 0 until cols) {
                val cx = (col + 0.5f) * cellPx.toFloat()
                var wsum = 0.0; var vw = 0.0; var hit = false
                val bx = (cx / bucket).toInt(); val by = (cy / bucket).toInt()
                for (dy in -1..1) for (dx in -1..1) {
                    val list = buckets[(by + dy) * bcols + (bx + dx)] ?: continue
                    for (pt in list) {
                        val ddx = pt[0] - cx; val ddy = pt[1] - cy
                        val d2 = ddx * ddx + ddy * ddy
                        if (d2 > r2) continue
                        hit = true
                        val w = 1.0 / (d2.toDouble() + 1.0)
                        wsum += w; vw += w * pt[2]
                    }
                }
                if (hit && wsum > 0) {
                    val v = (vw / wsum).toFloat()
                    values[row * cols + col] = v
                    covered++
                    vmin = min(vmin, v); vmax = max(vmax, v); vsum += v
                }
            }
        }
        if (covered == 0) return null
        val legend = legend(filter.metric, p)
        val bmp = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888)
        val px = IntArray(cols * rows)
        for (i in values.indices) {
            val v = values[i]
            px[i] = if (v.isNaN()) Color.TRANSPARENT else {
                val c = colorFor(v, legend)
                Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c))
            }
        }
        bmp.setPixels(px, 0, cols, 0, 0, cols, rows)
        return HeatmapResult(bmp, cellPx, values, cols, rows, covered, vmin, vmax, (vsum / covered).toFloat(), legend)
    }

    /** Difference heatmap B - A for the filter's metric. Cells covered by only one survey stay transparent. */
    private fun renderCompare(p: Project, floor: Floor, filter: HeatmapFilter, alpha: Int, radiusM: Double?): HeatmapResult? {
        val base = HeatmapFilter(filter.metric, filter.ssid, filter.band, filter.bssid)
        val a = render(p, floor, HeatmapFilter(base.metric, base.ssid, base.band, base.bssid, setOf(filter.compareA!!)), alpha, radiusM) ?: return null
        val b = render(p, floor, HeatmapFilter(base.metric, base.ssid, base.band, base.bssid, setOf(filter.compareB!!)), alpha, radiusM) ?: return null
        if (a.cols != b.cols || a.rows != b.rows) return null
        val values = FloatArray(a.values.size) { Float.NaN }
        var covered = 0; var vmin = Float.MAX_VALUE; var vmax = -Float.MAX_VALUE; var sum = 0.0
        for (i in values.indices) {
            val va = a.values[i]; val vb = b.values[i]
            if (va.isNaN() || vb.isNaN()) continue
            val d = vb - va
            values[i] = d; covered++; vmin = min(vmin, d); vmax = max(vmax, d); sum += d
        }
        if (covered == 0) return null
        val span = when (filter.metric) { Metric.AP_COUNT, Metric.INTERFERENCE -> 3f; Metric.PACKET_LOSS -> 20f; Metric.LATENCY -> 50f; else -> 10f }
        val better = if (filter.metric.lowerIsBetter) Color.rgb(230, 40, 40) else Color.rgb(0, 140, 60)
        val worse = if (filter.metric.lowerIsBetter) Color.rgb(0, 140, 60) else Color.rgb(230, 40, 40)
        val legend = listOf(-span to worse, -span / 3 to Color.rgb(255, 200, 120), 0f to Color.rgb(245, 245, 245), span / 3 to Color.rgb(170, 230, 170), span to better)
        val bmp = Bitmap.createBitmap(a.cols, a.rows, Bitmap.Config.ARGB_8888)
        val px = IntArray(values.size)
        for (i in values.indices) {
            val v = values[i]
            px[i] = if (v.isNaN()) Color.TRANSPARENT else { val c = colorFor(v, legend); Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c)) }
        }
        bmp.setPixels(px, 0, a.cols, 0, 0, a.cols, a.rows)
        return HeatmapResult(bmp, a.cellPx, values, a.cols, a.rows, covered, vmin, vmax, (sum / covered).toFloat(), legend)
    }

    /** Share of covered cells whose value satisfies the requirement (>= for RSSI/SNR/AP count, <= for interference). */
    fun coverageShare(res: HeatmapResult, metric: Metric, threshold: Float): Float {
        var ok = 0; var n = 0
        for (v in res.values) {
            if (v.isNaN()) continue
            n++
            val pass = if (metric.lowerIsBetter) v <= threshold else v >= threshold
            if (pass) ok++
        }
        return if (n == 0) 0f else ok.toFloat() / n
    }

    /** Covered area in square metres. */
    fun coveredAreaM2(res: HeatmapResult, floor: Floor): Double {
        val mpp = if (floor.metersPerPx > 0) floor.metersPerPx else return 0.0
        val cellM = res.cellPx * mpp
        return res.coveredCells * cellM * cellM
    }

    fun distancePx(x1: Double, y1: Double, x2: Double, y2: Double): Double = sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1))
}
