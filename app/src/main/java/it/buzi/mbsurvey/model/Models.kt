package it.buzi.mbsurvey.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

/** Project settings persisted with the project. */
class ProjectSettings(
    var scanIntervalMs: Long = 2500,
    var stopAndGoScans: Int = 3,
    var heatmapRadiusM: Double = 4.0,
    var rssiGood: Int = -65,
    var rssiOk: Int = -72,
    var rssiMin: Int = -85,
    var apCountThreshold: Int = -70,
    var noiseFloorDbm: Int = -92,
    var activeSurvey: Boolean = false,
    var pingTarget: String = "",
    var pingProbes: Int = 3,
    var latencyGoodMs: Int = 50
) {
    fun toJson(): JSONObject = JSONObject()
        .put("scanIntervalMs", scanIntervalMs)
        .put("stopAndGoScans", stopAndGoScans)
        .put("heatmapRadiusM", heatmapRadiusM)
        .put("rssiGood", rssiGood)
        .put("rssiOk", rssiOk)
        .put("rssiMin", rssiMin)
        .put("apCountThreshold", apCountThreshold)
        .put("noiseFloorDbm", noiseFloorDbm)
        .put("activeSurvey", activeSurvey)
        .put("pingTarget", pingTarget)
        .put("pingProbes", pingProbes)
        .put("latencyGoodMs", latencyGoodMs)

    companion object {
        fun fromJson(o: JSONObject?): ProjectSettings {
            val s = ProjectSettings()
            if (o == null) return s
            s.scanIntervalMs = o.optLong("scanIntervalMs", s.scanIntervalMs)
            s.stopAndGoScans = o.optInt("stopAndGoScans", s.stopAndGoScans)
            s.heatmapRadiusM = o.optDouble("heatmapRadiusM", s.heatmapRadiusM)
            s.rssiGood = o.optInt("rssiGood", s.rssiGood)
            s.rssiOk = o.optInt("rssiOk", s.rssiOk)
            s.rssiMin = o.optInt("rssiMin", s.rssiMin)
            s.apCountThreshold = o.optInt("apCountThreshold", s.apCountThreshold)
            s.noiseFloorDbm = o.optInt("noiseFloorDbm", s.noiseFloorDbm)
            s.activeSurvey = o.optBoolean("activeSurvey", false)
            s.pingTarget = o.optString("pingTarget", "")
            s.pingProbes = o.optInt("pingProbes", 3)
            s.latencyGoodMs = o.optInt("latencyGoodMs", 50)
            return s
        }
    }
}

/** Two reference points on the floor plan (pixels) and the real distance between them. */
class Calibration(var x1: Double, var y1: Double, var x2: Double, var y2: Double, var meters: Double) {
    fun metersPerPx(): Double {
        val d = Math.hypot(x2 - x1, y2 - y1)
        return if (d > 0) meters / d else 0.0
    }

    fun toJson(): JSONObject = JSONObject()
        .put("x1", x1).put("y1", y1).put("x2", x2).put("y2", y2).put("meters", meters)

    companion object {
        fun fromJson(o: JSONObject?): Calibration? {
            if (o == null) return null
            return Calibration(
                o.optDouble("x1"), o.optDouble("y1"), o.optDouble("x2"), o.optDouble("y2"), o.optDouble("meters")
            )
        }
    }
}

class Floor(
    val id: String = newId(),
    var name: String,
    var imageFile: String,
    var widthPx: Int,
    var heightPx: Int,
    var metersPerPx: Double = 0.0,
    var calibration: Calibration? = null,
    var drawing: PlanDrawing? = null
) {
    val isCalibrated: Boolean get() = metersPerPx > 0
    val isDrawn: Boolean get() = drawing != null

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("imageFile", imageFile)
        .put("widthPx", widthPx)
        .put("heightPx", heightPx)
        .put("metersPerPx", metersPerPx)
        .put("calibration", calibration?.toJson() ?: JSONObject.NULL)
        .put("drawing", drawing?.toJson() ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject): Floor = Floor(
            id = o.getString("id"),
            name = o.optString("name", "Piano"),
            imageFile = o.getString("imageFile"),
            widthPx = o.optInt("widthPx"),
            heightPx = o.optInt("heightPx"),
            metersPerPx = o.optDouble("metersPerPx", 0.0),
            calibration = Calibration.fromJson(o.optJSONObject("calibration")),
            drawing = o.optJSONObject("drawing")?.let { PlanDrawing.fromJson(it) }
        )
    }
}

enum class ApSource { PLANNED, MEASURED, MANUAL }

/** An access point: either planned (from an imported design), measured (from scans) or placed by hand. */
class AccessPoint(
    val id: String = newId(),
    var name: String,
    var vendor: String = "",
    var model: String = "",
    var floorId: String? = null,
    var x: Double? = null,
    var y: Double? = null,
    val bssids: MutableList<String> = mutableListOf(),
    var mine: Boolean = true,
    var source: ApSource = ApSource.MANUAL,
    var notes: String = ""
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("vendor", vendor)
        .put("model", model)
        .put("floorId", floorId ?: JSONObject.NULL)
        .put("x", x ?: JSONObject.NULL)
        .put("y", y ?: JSONObject.NULL)
        .put("bssids", JSONArray(bssids))
        .put("mine", mine)
        .put("source", source.name)
        .put("notes", notes)

    companion object {
        fun fromJson(o: JSONObject): AccessPoint {
            val ap = AccessPoint(
                id = o.getString("id"),
                name = o.optString("name", "AP"),
                vendor = o.optString("vendor", ""),
                model = o.optString("model", ""),
                floorId = if (o.isNull("floorId")) null else o.optString("floorId"),
                x = if (o.isNull("x")) null else o.optDouble("x"),
                y = if (o.isNull("y")) null else o.optDouble("y"),
                mine = o.optBoolean("mine", true),
                source = runCatching { ApSource.valueOf(o.optString("source", "MANUAL")) }.getOrDefault(ApSource.MANUAL),
                notes = o.optString("notes", "")
            )
            val arr = o.optJSONArray("bssids")
            if (arr != null) for (i in 0 until arr.length()) ap.bssids.add(arr.getString(i))
            return ap
        }
    }
}

enum class SurveyMode { CONTINUOUS, STOP_AND_GO }

/** A point tapped by the user on the floor plan during a survey. */
class Waypoint(val t: Long, val x: Double, val y: Double) {
    fun toJson(): JSONObject = JSONObject().put("t", t).put("x", x).put("y", y)

    companion object {
        fun fromJson(o: JSONObject) = Waypoint(o.getLong("t"), o.getDouble("x"), o.getDouble("y"))
    }
}

/** One BSSID seen in one scan. */
class ScanEntry(
    val bssid: String,
    val ssid: String,
    val rssi: Int,
    val freqMhz: Int,
    val widthMhz: Int,
    val centerFreq0: Int,
    val capabilities: String
) {
    val channel: Int get() = Channels.channelOf(freqMhz)
    val band: Band get() = Channels.bandOf(freqMhz)
    val security: String get() = Channels.security(capabilities)

    fun toJson(): JSONObject = JSONObject()
        .put("b", bssid).put("s", ssid).put("r", rssi).put("f", freqMhz)
        .put("w", widthMhz).put("c0", centerFreq0).put("cap", capabilities)

    companion object {
        fun fromJson(o: JSONObject) = ScanEntry(
            o.getString("b"), o.optString("s", ""), o.getInt("r"), o.getInt("f"),
            o.optInt("w", 20), o.optInt("c0", 0), o.optString("cap", "")
        )
    }
}

/** One scan: all BSSIDs visible at time t. Position is resolved from the waypoints. */
class Scan(val t: Long, var x: Double? = null, var y: Double? = null, val entries: List<ScanEntry>) {
    val isLocated: Boolean get() = x != null && y != null

    /** Active survey: round-trip time (ms) and packet loss (%) measured right after this scan. */
    var rttMs: Double? = null
    var lossPct: Int? = null

    fun toJson(): JSONObject {
        val arr = JSONArray()
        entries.forEach { arr.put(it.toJson()) }
        return JSONObject().put("t", t).put("x", x ?: JSONObject.NULL).put("y", y ?: JSONObject.NULL).put("e", arr)
            .put("rtt", rttMs ?: JSONObject.NULL).put("loss", lossPct ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject): Scan {
            val arr = o.optJSONArray("e") ?: JSONArray()
            val list = ArrayList<ScanEntry>(arr.length())
            for (i in 0 until arr.length()) list.add(ScanEntry.fromJson(arr.getJSONObject(i)))
            val sc = Scan(
                o.getLong("t"),
                if (o.isNull("x")) null else o.optDouble("x"),
                if (o.isNull("y")) null else o.optDouble("y"),
                list
            )
            if (!o.isNull("rtt")) sc.rttMs = o.optDouble("rtt")
            if (!o.isNull("loss")) sc.lossPct = o.optInt("loss")
            return sc
        }
    }
}

class Survey(
    val id: String = newId(),
    val floorId: String,
    var name: String,
    var mode: SurveyMode,
    val startedAt: Long,
    var endedAt: Long? = null,
    val waypoints: MutableList<Waypoint> = mutableListOf(),
    val scans: MutableList<Scan> = mutableListOf()
) {
    val locatedScans: List<Scan> get() = scans.filter { it.isLocated }

    fun toJson(): JSONObject {
        val wp = JSONArray(); waypoints.forEach { wp.put(it.toJson()) }
        val sc = JSONArray(); scans.forEach { sc.put(it.toJson()) }
        return JSONObject()
            .put("id", id)
            .put("floorId", floorId)
            .put("name", name)
            .put("mode", mode.name)
            .put("startedAt", startedAt)
            .put("endedAt", endedAt ?: JSONObject.NULL)
            .put("waypoints", wp)
            .put("scans", sc)
    }

    companion object {
        fun fromJson(o: JSONObject): Survey {
            val s = Survey(
                id = o.getString("id"),
                floorId = o.getString("floorId"),
                name = o.optString("name", "Survey"),
                mode = runCatching { SurveyMode.valueOf(o.optString("mode", "CONTINUOUS")) }.getOrDefault(SurveyMode.CONTINUOUS),
                startedAt = o.optLong("startedAt"),
                endedAt = if (o.isNull("endedAt")) null else o.optLong("endedAt")
            )
            val wp = o.optJSONArray("waypoints") ?: JSONArray()
            for (i in 0 until wp.length()) s.waypoints.add(Waypoint.fromJson(wp.getJSONObject(i)))
            val sc = o.optJSONArray("scans") ?: JSONArray()
            for (i in 0 until sc.length()) s.scans.add(Scan.fromJson(sc.getJSONObject(i)))
            return s
        }
    }
}

/** A note pinned on a floor plan, optionally with a photo (file name under notes/). */
class Note(
    val id: String = newId(),
    val floorId: String,
    var x: Double,
    var y: Double,
    var text: String = "",
    var photoFile: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("floorId", floorId).put("x", x).put("y", y)
        .put("text", text).put("photoFile", photoFile ?: JSONObject.NULL).put("createdAt", createdAt)

    companion object {
        fun fromJson(o: JSONObject) = Note(
            o.getString("id"), o.getString("floorId"), o.getDouble("x"), o.getDouble("y"), o.optString("text", ""),
            if (o.isNull("photoFile")) null else o.optString("photoFile"), o.optLong("createdAt")
        )
    }
}

class Project(
    val id: String = newId(),
    var name: String,
    var customer: String = "",
    var location: String = "",
    var surveyor: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    var modifiedAt: Long = System.currentTimeMillis(),
    val floors: MutableList<Floor> = mutableListOf(),
    val accessPoints: MutableList<AccessPoint> = mutableListOf(),
    val surveys: MutableList<Survey> = mutableListOf(),
    var settings: ProjectSettings = ProjectSettings(),
    val notes: MutableList<Note> = mutableListOf()
) {
    fun floor(id: String?): Floor? = floors.firstOrNull { it.id == id }
    fun notesOf(floorId: String): List<Note> = notes.filter { it.floorId == floorId }
    fun surveysOf(floorId: String): List<Survey> = surveys.filter { it.floorId == floorId }

    /** Serialises everything except the survey data (which lives in separate files). */
    fun toJson(): JSONObject {
        val fl = JSONArray(); floors.forEach { fl.put(it.toJson()) }
        val aps = JSONArray(); accessPoints.forEach { aps.put(it.toJson()) }
        val sv = JSONArray(); surveys.forEach { sv.put(it.id) }
        val nt = JSONArray(); notes.forEach { nt.put(it.toJson()) }
        return JSONObject()
            .put("format", "wifisurvey-project")
            .put("formatVersion", 1)
            .put("id", id)
            .put("name", name)
            .put("customer", customer)
            .put("location", location)
            .put("surveyor", surveyor)
            .put("createdAt", createdAt)
            .put("modifiedAt", modifiedAt)
            .put("floors", fl)
            .put("accessPoints", aps)
            .put("surveyIds", sv)
            .put("settings", settings.toJson())
            .put("notes", nt)
    }

    companion object {
        fun fromJson(o: JSONObject): Project {
            val p = Project(
                id = o.getString("id"),
                name = o.optString("name", "Progetto"),
                customer = o.optString("customer", ""),
                location = o.optString("location", ""),
                surveyor = o.optString("surveyor", ""),
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                modifiedAt = o.optLong("modifiedAt", System.currentTimeMillis()),
                settings = ProjectSettings.fromJson(o.optJSONObject("settings"))
            )
            val fl = o.optJSONArray("floors") ?: JSONArray()
            for (i in 0 until fl.length()) p.floors.add(Floor.fromJson(fl.getJSONObject(i)))
            val aps = o.optJSONArray("accessPoints") ?: JSONArray()
            for (i in 0 until aps.length()) p.accessPoints.add(AccessPoint.fromJson(aps.getJSONObject(i)))
            val nt = o.optJSONArray("notes") ?: JSONArray()
            for (i in 0 until nt.length()) runCatching { Note.fromJson(nt.getJSONObject(i)) }.getOrNull()?.let { p.notes.add(it) }
            return p
        }
    }
}
