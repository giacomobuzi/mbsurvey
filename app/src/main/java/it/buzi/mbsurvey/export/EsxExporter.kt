package it.buzi.mbsurvey.export

import android.content.Context
import it.buzi.mbsurvey.data.ProjectStore
import it.buzi.mbsurvey.model.Band
import it.buzi.mbsurvey.model.Project
import it.buzi.mbsurvey.model.SurveyMode
import it.buzi.mbsurvey.model.newId
import it.buzi.mbsurvey.survey.SurveyEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes an Ekahau .esx (ZIP of JSON files, schema 1.8.0).
 *
 * The project/floor/image/accessPoints parts follow the structure of a real Ekahau AI Pro 11.5 file.
 * measuredRadios / accessPointMeasurements follow the field names used by public ESX tooling.
 * surveys.json (survey path + per-point readings) is a best-effort reconstruction of Ekahau's layout
 * and is marked as such; a vendor-neutral copy of every reading is also stored in
 * wifisurvey-measurements.json inside the archive so nothing is lost.
 */
class EsxExporter(private val ctx: Context, private val store: ProjectStore) {

    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private val configurationId = "71b21be8-eaac-45a0-99e9-0c22d88872ef"

    private fun created(o: JSONObject): JSONObject = o.put("status", "CREATED")

    fun write(p: Project, file: File) {
        val projectId = newId()
        ZipOutputStream(FileOutputStream(file).buffered()).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
            put("version", "2.0")

            // ---- project ----
            val project = JSONObject()
                .put("schemaVersion", "1.8.0")
                .put("name", p.name).put("title", p.name)
                .put("customer", p.customer).put("location", p.location).put("responsiblePerson", p.surveyor)
                .put("noteIds", JSONArray()).put("projectAncestors", JSONArray())
                .put("history", JSONObject()
                    .put("modifiedAt", iso.format(Date(p.modifiedAt))).put("modifiedBy", p.surveyor.ifBlank { "MB Survey" })
                    .put("createdAt", iso.format(Date(p.createdAt))).put("createdBy", p.surveyor.ifBlank { "MB Survey" }))
                .put("projectConfigurationId", configurationId)
                .put("tags", JSONArray()).put("id", projectId)
            put("project.json", JSONObject().put("project", created(project)).toString())

            // ---- reference files from the Ekahau template ----
            for (name in listOf("projectConfiguration", "floorTypes", "usageProfiles", "deviceProfiles", "applicationProfiles",
                "requirements", "networkCapacitySettings", "attenuationAreaTypes", "wallTypes")) {
                put("$name.json", ctx.assets.open("esx/$name.json").bufferedReader().use { it.readText() })
            }

            // ---- floor plans + images ----
            val floorPlans = JSONArray(); val images = JSONArray()
            for (f in p.floors) {
                val imageId = newId()
                floorPlans.put(created(JSONObject()
                    .put("name", f.name)
                    .put("width", f.widthPx.toDouble()).put("height", f.heightPx.toDouble())
                    .put("metersPerUnit", if (f.metersPerPx > 0) f.metersPerPx else 0.05)
                    .put("imageId", imageId).put("bitmapImageId", imageId)
                    .put("gpsReferencePoints", JSONArray())
                    .put("floorPlanType", "FSPL")
                    .put("cropMinX", 0.0).put("cropMinY", 0.0)
                    .put("cropMaxX", f.widthPx.toDouble()).put("cropMaxY", f.heightPx.toDouble())
                    .put("rotateUpDirection", "UP")
                    .put("tags", JSONArray()).put("id", f.id)))
                images.put(created(JSONObject()
                    .put("imageFormat", "JPEG")
                    .put("resolutionWidth", f.widthPx.toDouble()).put("resolutionHeight", f.heightPx.toDouble())
                    .put("id", imageId)))
                val img = store.floorImageFile(p.id, f)
                if (img.exists()) {
                    zip.putNextEntry(ZipEntry("image-$imageId"))
                    img.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            put("floorPlans.json", JSONObject().put("floorPlans", floorPlans).toString())
            put("images.json", JSONObject().put("images", images).toString())

            // ---- access points, measured radios, measurements ----
            val radios = SurveyEngine.radios(p)
            val aps = JSONArray(); val measuredRadios = JSONArray(); val measurements = JSONArray()
            val radioIdByBssid = HashMap<String, String>()
            // Make sure every measured BSSID belongs to an AP (unassigned ones become MEASURED APs).
            val apList = p.accessPoints.map { it }.toMutableList()
            val assigned = apList.flatMap { it.bssids }.toSet()
            val orphan = radios.filter { it.bssid !in assigned }.groupBy { SurveyEngine.groupKey(it.bssid) }
            for ((_, list) in orphan) {
                val best = list.maxByOrNull { it.maxRssi } ?: continue
                val ap = it.buzi.mbsurvey.model.AccessPoint(
                    name = (best.ssid.ifBlank { "AP" }) + "-" + best.bssid.takeLast(5),
                    floorId = best.estFloorId, x = best.estX, y = best.estY, mine = false,
                    source = it.buzi.mbsurvey.model.ApSource.MEASURED
                )
                list.forEach { ap.bssids.add(it.bssid) }
                apList.add(ap)
            }
            for (ap in apList) {
                val o = JSONObject()
                if (ap.floorId != null && ap.x != null && ap.y != null && p.floor(ap.floorId) != null) {
                    o.put("location", JSONObject().put("floorPlanId", ap.floorId)
                        .put("coord", JSONObject().put("x", ap.x!!).put("y", ap.y!!)))
                }
                o.put("name", ap.name).put("mine", ap.mine).put("hidden", false)
                    .put("userDefinedPosition", ap.source != it.buzi.mbsurvey.model.ApSource.MEASURED)
                    .put("noteIds", JSONArray()).put("vendor", ap.vendor).put("model", ap.model)
                    .put("tags", JSONArray()).put("id", ap.id)
                aps.put(created(o))
                for (bssid in ap.bssids) {
                    val r = radios.firstOrNull { it.bssid == bssid } ?: continue
                    val measurementId = newId()
                    val radioId = newId()
                    radioIdByBssid[bssid] = radioId
                    val centre = if (r.widthMhz > 20) r.freqMhz else r.freqMhz
                    measurements.put(created(JSONObject()
                        .put("mac", bssid).put("ssid", r.ssid)
                        .put("technologies", JSONArray().put(technology(r.band)))
                        .put("channelByCenterFrequencyDefinedNarrowChannels", JSONArray().put(centre))
                        .put("channelWidth", r.widthMhz)
                        .put("security", r.security)
                        .put("id", measurementId)))
                    measuredRadios.put(created(JSONObject()
                        .put("accessPointId", ap.id)
                        .put("accessPointMeasurementIds", JSONArray().put(measurementId))
                        .put("frequencyBand", bandKey(r.band))
                        .put("technologies", JSONArray().put(technology(r.band)))
                        .put("id", radioId)))
                }
            }
            put("accessPoints.json", JSONObject().put("accessPoints", aps).toString())
            put("measuredRadios.json", JSONObject().put("measuredRadios", measuredRadios).toString())
            put("accessPointMeasurements.json", JSONObject().put("accessPointMeasurements", measurements).toString())

            // ---- surveys (best-effort Ekahau layout) ----
            val surveys = JSONArray()
            for (s in p.surveys) {
                if (p.floor(s.floorId) == null) continue
                val points = JSONArray()
                for (scan in s.scans) {
                    val x = scan.x ?: continue
                    val y = scan.y ?: continue
                    val readings = JSONArray()
                    for (e in scan.entries) {
                        readings.put(JSONObject()
                            .put("measuredRadioId", radioIdByBssid[e.bssid] ?: JSONObject.NULL)
                            .put("mac", e.bssid)
                            .put("signalStrength", e.rssi)
                            .put("frequency", e.freqMhz))
                    }
                    points.put(created(JSONObject()
                        .put("coord", JSONObject().put("x", x).put("y", y))
                        .put("time", iso.format(Date(scan.t)))
                        .put("measurements", readings)
                        .put("id", newId())))
                }
                val waypoints = JSONArray()
                s.waypoints.forEach { waypoints.put(JSONObject().put("coord", JSONObject().put("x", it.x).put("y", it.y)).put("time", iso.format(Date(it.t)))) }
                surveys.put(created(JSONObject()
                    .put("name", s.name)
                    .put("floorPlanId", s.floorId)
                    .put("surveyType", "PASSIVE")
                    .put("surveyMode", if (s.mode == SurveyMode.CONTINUOUS) "CONTINUOUS" else "STOP_AND_GO")
                    .put("adapter", "Android Wi-Fi")
                    .put("startTime", iso.format(Date(s.startedAt)))
                    .put("endTime", iso.format(Date(s.endedAt ?: s.startedAt)))
                    .put("surveyPoints", points)
                    .put("surveyPath", waypoints)
                    .put("id", s.id)))
            }
            put("surveys.json", JSONObject().put("surveys", surveys).toString())

            put("projectHistorys.json", JSONObject().put("projectHistorys", JSONArray().put(created(JSONObject()
                .put("parentIds", JSONArray())
                .put("timestamp", iso.format(Date()))
                .put("projectId", projectId)
                .put("projectName", p.name)
                .put("productName", "MB Survey Android")
                .put("productVersion", "1.0")
                .put("schemaVersion", "1.8.0")
                .put("operation", "LOCAL_SAVE")
                .put("platform", "Android")
                .put("id", newId())))).toString())

            // ---- vendor-neutral full copy ----
            put("wifisurvey-measurements.json", store.exportJson(p).toString())
        }
    }

    private fun technology(b: Band): String = when (b) { Band.B24 -> "N"; Band.B5 -> "AC"; Band.B6 -> "AX"; else -> "N" }
    private fun bandKey(b: Band): String = when (b) { Band.B24 -> "TWO"; Band.B5 -> "FIVE"; Band.B6 -> "SIX"; else -> "TWO" }
}
