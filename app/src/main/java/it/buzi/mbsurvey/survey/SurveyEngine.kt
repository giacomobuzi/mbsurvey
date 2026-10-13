package it.buzi.mbsurvey.survey

import it.buzi.mbsurvey.model.AccessPoint
import it.buzi.mbsurvey.model.ApSource
import it.buzi.mbsurvey.model.Band
import it.buzi.mbsurvey.model.Channels
import it.buzi.mbsurvey.model.Project
import it.buzi.mbsurvey.model.Scan
import it.buzi.mbsurvey.model.ScanEntry
import it.buzi.mbsurvey.model.Survey
import it.buzi.mbsurvey.model.SurveyMode
import it.buzi.mbsurvey.model.Waypoint

/** A BSSID aggregated over all located scans of a floor (or of the whole project). */
class RadioSummary(val bssid: String) {
    var ssid: String = ""
    var freqMhz: Int = 0
    var widthMhz: Int = 20
    var capabilities: String = ""
    var maxRssi: Int = -127
    var samples: Int = 0
    var rssiSum: Long = 0
    var estX: Double? = null
    var estY: Double? = null
    var estFloorId: String? = null
    val channel: Int get() = Channels.channelOf(freqMhz)
    val band: Band get() = Channels.bandOf(freqMhz)
    val avgRssi: Double get() = if (samples == 0) 0.0 else rssiSum.toDouble() / samples
    val security: String get() = Channels.security(capabilities)
}

/** A single located measurement, flattened for heatmaps and exports. */
class Sample(val x: Double, val y: Double, val t: Long, val entry: ScanEntry, val surveyId: String)

object SurveyEngine {

    /**
     * Continuous mode: assigns positions to the scans between the previous waypoint and the new one,
     * interpolated linearly on time. Call after appending the new waypoint.
     */
    fun resolveLastSegment(s: Survey) {
        if (s.waypoints.size < 2) return
        val a = s.waypoints[s.waypoints.size - 2]
        val b = s.waypoints[s.waypoints.size - 1]
        locate(s, a, b)
    }

    /** Re-resolves all positions of a continuous survey from its waypoints. */
    fun resolveAll(s: Survey) {
        if (s.mode != SurveyMode.CONTINUOUS) return
        s.scans.forEach { it.x = null; it.y = null }
        for (i in 1 until s.waypoints.size) locate(s, s.waypoints[i - 1], s.waypoints[i])
    }

    private fun locate(s: Survey, a: Waypoint, b: Waypoint) {
        val span = (b.t - a.t).toDouble()
        for (scan in s.scans) {
            if (scan.t < a.t || scan.t > b.t) continue
            if (scan.isLocated && scan.t != b.t) continue
            val f = if (span <= 0) 1.0 else (scan.t - a.t) / span
            scan.x = a.x + (b.x - a.x) * f
            scan.y = a.y + (b.y - a.y) * f
        }
    }

    /** All located samples for a floor, optionally filtered by SSID / band. */
    fun samples(p: Project, floorId: String, ssidFilter: String? = null, band: Band? = null): List<Sample> {
        val out = ArrayList<Sample>()
        for (s in p.surveysOf(floorId)) {
            for (scan in s.scans) {
                val x = scan.x ?: continue
                val y = scan.y ?: continue
                for (e in scan.entries) {
                    if (ssidFilter != null && e.ssid != ssidFilter) continue
                    if (band != null && e.band != band) continue
                    out.add(Sample(x, y, scan.t, e, s.id))
                }
            }
        }
        return out
    }

    /** Located scans of a floor. */
    fun locatedScans(p: Project, floorId: String): List<Scan> =
        p.surveysOf(floorId).flatMap { it.locatedScans }

    /** All distinct SSIDs measured in the project, most frequent first. */
    fun ssids(p: Project): List<String> {
        val counts = HashMap<String, Int>()
        for (s in p.surveys) for (scan in s.scans) for (e in scan.entries) {
            if (e.ssid.isNotBlank()) counts[e.ssid] = (counts[e.ssid] ?: 0) + 1
        }
        return counts.entries.sortedByDescending { it.value }.map { it.key }
    }

    /** Aggregates every BSSID seen in located scans; estimates a position from the strongest samples. */
    fun radios(p: Project, floorId: String? = null): List<RadioSummary> {
        val map = LinkedHashMap<String, RadioSummary>()
        val strongest = HashMap<String, MutableList<Triple<Double, Double, Int>>>()
        val floorOf = HashMap<String, String>()
        for (s in p.surveys) {
            if (floorId != null && s.floorId != floorId) continue
            for (scan in s.scans) {
                for (e in scan.entries) {
                    val r = map.getOrPut(e.bssid) { RadioSummary(e.bssid) }
                    if (e.ssid.isNotBlank()) r.ssid = e.ssid
                    r.freqMhz = e.freqMhz
                    r.widthMhz = e.widthMhz
                    r.capabilities = e.capabilities
                    r.samples++
                    r.rssiSum += e.rssi
                    if (e.rssi > r.maxRssi) {
                        r.maxRssi = e.rssi
                        if (scan.isLocated) floorOf[e.bssid] = s.floorId
                    }
                    if (scan.isLocated) {
                        strongest.getOrPut(e.bssid) { mutableListOf() }.add(Triple(scan.x!!, scan.y!!, e.rssi))
                    }
                }
            }
        }
        for ((bssid, list) in strongest) {
            val r = map[bssid] ?: continue
            // Weighted centroid of the 8 strongest samples (weights grow steeply with RSSI).
            val top = list.sortedByDescending { it.third }.take(8)
            var wx = 0.0; var wy = 0.0; var w = 0.0
            for ((x, y, rssi) in top) {
                val weight = Math.pow(10.0, (rssi + 100) / 10.0)
                wx += x * weight; wy += y * weight; w += weight
            }
            if (w > 0) { r.estX = wx / w; r.estY = wy / w; r.estFloorId = floorOf[bssid] }
        }
        return map.values.sortedByDescending { it.maxRssi }
    }

    /** Groups BSSIDs that most likely belong to the same physical AP (same OUI + base MAC, different last nibble). */
    fun groupKey(bssid: String): String {
        val clean = bssid.lowercase().replace(":", "")
        if (clean.length != 12) return bssid
        return clean.substring(0, 11)
    }

    /** Creates/updates MEASURED access points in the project from the radios seen so far. */
    fun syncMeasuredAps(p: Project) {
        val radios = radios(p)
        val byGroup = radios.groupBy { groupKey(it.bssid) }
        for ((_, list) in byGroup) {
            val existing = p.accessPoints.firstOrNull { ap -> ap.bssids.any { b -> list.any { it.bssid == b } } }
            val best = list.maxByOrNull { it.maxRssi } ?: continue
            if (existing != null) {
                list.forEach { if (it.bssid !in existing.bssids) existing.bssids.add(it.bssid) }
                if (existing.source == ApSource.MEASURED && existing.x == null && best.estX != null) {
                    existing.x = best.estX; existing.y = best.estY; existing.floorId = best.estFloorId
                }
            } else {
                val ssid = list.map { it.ssid }.firstOrNull { it.isNotBlank() } ?: ""
                val ap = AccessPoint(
                    name = if (ssid.isNotBlank()) "$ssid-${best.bssid.takeLast(5)}" else "AP-${best.bssid.takeLast(5)}",
                    floorId = best.estFloorId,
                    x = best.estX,
                    y = best.estY,
                    mine = false,
                    source = ApSource.MEASURED
                )
                list.forEach { ap.bssids.add(it.bssid) }
                p.accessPoints.add(ap)
            }
        }
    }

    /** Per-scan metric helpers used by heatmaps and the report. */
    fun primaryRssi(scan: Scan, ssid: String?, band: Band?): Int? =
        scan.entries.filter { (ssid == null || it.ssid == ssid) && (band == null || it.band == band) }
            .maxOfOrNull { it.rssi }

    fun secondaryRssi(scan: Scan, ssid: String?, band: Band?): Int? {
        val sorted = scan.entries.filter { (ssid == null || it.ssid == ssid) && (band == null || it.band == band) }
            .map { it.rssi }.sortedDescending()
        return sorted.getOrNull(1)
    }

    fun apCount(scan: Scan, ssid: String?, band: Band?, threshold: Int): Int =
        scan.entries.count { (ssid == null || it.ssid == ssid) && (band == null || it.band == band) && it.rssi >= threshold }

    /** Number of other BSSIDs overlapping the primary BSSID's channel with usable power. */
    fun channelInterference(scan: Scan, ssid: String?, band: Band?, minRssi: Int): Int? {
        val primary = scan.entries.filter { (ssid == null || it.ssid == ssid) && (band == null || it.band == band) }
            .maxByOrNull { it.rssi } ?: return null
        val pc = if (primary.centerFreq0 > 0 && primary.widthMhz > 20) primary.centerFreq0 else primary.freqMhz
        return scan.entries.count { e ->
            e.bssid != primary.bssid && e.rssi >= minRssi && e.band == primary.band &&
                Channels.overlaps(pc, primary.widthMhz, if (e.centerFreq0 > 0 && e.widthMhz > 20) e.centerFreq0 else e.freqMhz, e.widthMhz)
        }
    }

    fun snr(scan: Scan, ssid: String?, band: Band?, noiseFloor: Int): Int? =
        primaryRssi(scan, ssid, band)?.let { it - noiseFloor }

    fun bssidRssi(scan: Scan, bssid: String): Int? = scan.entries.firstOrNull { it.bssid == bssid }?.rssi
}
