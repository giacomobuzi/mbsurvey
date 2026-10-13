package it.buzi.mbsurvey.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import it.buzi.mbsurvey.R
import it.buzi.mbsurvey.data.ProjectStore
import it.buzi.mbsurvey.model.AccessPoint
import it.buzi.mbsurvey.model.ApSource
import it.buzi.mbsurvey.model.Project
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Imports floor plans (bitmap images), their scale and the planned/placed access points from an
 * Ekahau .esx project, so a validation survey can be run on the design.
 * SVG floor plans are only imported when the file also carries a bitmap rendition (bitmapImageId).
 */
object EsxImporter {

    fun import(ctx: Context, store: ProjectStore, uri: Uri): Project {
        val tmp = File(ctx.cacheDir, "esx-import").apply { deleteRecursively(); mkdirs() }
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    val name = File(e.name).name
                    if (!e.isDirectory && name.isNotBlank()) {
                        File(tmp, name).outputStream().use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                    e = zip.nextEntry
                }
            }
        } ?: throw IllegalArgumentException("File non leggibile")

        val projectJson = File(tmp, "project.json").takeIf { it.exists() }?.let { JSONObject(it.readText()).optJSONObject("project") }
        val name = projectJson?.optString("name")?.ifBlank { null } ?: ctx.getString(R.string.default_esx_project)
        val project = Project(
            name = name,
            customer = projectJson?.optString("customer") ?: "",
            location = projectJson?.optString("location") ?: "",
            surveyor = projectJson?.optString("responsiblePerson") ?: ""
        )

        val floorIdMap = HashMap<String, String>()
        val floorPlans = File(tmp, "floorPlans.json").takeIf { it.exists() }?.let { JSONObject(it.readText()).optJSONArray("floorPlans") }
        if (floorPlans != null) {
            for (i in 0 until floorPlans.length()) {
                val fp = floorPlans.getJSONObject(i)
                val esxId = fp.optString("id")
                val w = fp.optDouble("width", 0.0)
                val h = fp.optDouble("height", 0.0)
                val mpu = fp.optDouble("metersPerUnit", 0.0)
                val candidates = listOf(fp.optString("bitmapImageId"), fp.optString("imageId")).filter { it.isNotBlank() }
                var bmp: Bitmap? = null
                for (imgId in candidates) {
                    val f = File(tmp, "image-$imgId")
                    if (!f.exists()) continue
                    bmp = decode(f) ?: continue
                    break
                }
                if (bmp == null) continue // SVG only: cannot render without a bitmap rendition
                val floor = store.addFloor(project, fp.optString("name", ctx.getString(R.string.default_floor, i + 1)), bmp)
                // Ekahau coordinates are in floor-plan units (its declared width/height). Our pixels may
                // differ (bitmap rendition / downscale), so keep a scale factor and convert the AP positions.
                val unitToPx = if (w > 0) floor.widthPx / w else 1.0
                floor.metersPerPx = if (mpu > 0) mpu / unitToPx else 0.0
                floorIdMap[esxId] = floor.id
                scaleByFloor[floor.id] = unitToPx
            }
        }

        val aps = File(tmp, "accessPoints.json").takeIf { it.exists() }?.let { JSONObject(it.readText()).optJSONArray("accessPoints") }
        val measurements = HashMap<String, String>() // measurementId -> mac
        File(tmp, "accessPointMeasurements.json").takeIf { it.exists() }?.let { f ->
            val arr = JSONObject(f.readText()).optJSONArray("accessPointMeasurements") ?: return@let
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                measurements[m.optString("id")] = m.optString("mac").lowercase()
            }
        }
        val radiosByAp = HashMap<String, MutableList<String>>() // apId -> macs
        File(tmp, "measuredRadios.json").takeIf { it.exists() }?.let { f ->
            val arr = JSONObject(f.readText()).optJSONArray("measuredRadios") ?: return@let
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                val apId = r.optString("accessPointId")
                val ids = r.optJSONArray("accessPointMeasurementIds") ?: continue
                for (j in 0 until ids.length()) {
                    measurements[ids.getString(j)]?.let { mac -> if (mac.isNotBlank()) radiosByAp.getOrPut(apId) { mutableListOf() }.add(mac) }
                }
            }
        }
        if (aps != null) {
            for (i in 0 until aps.length()) {
                val a = aps.getJSONObject(i)
                val loc = a.optJSONObject("location")
                val esxFloor = loc?.optString("floorPlanId")
                val floorId = floorIdMap[esxFloor]
                val coord = loc?.optJSONObject("coord")
                val scale = scaleByFloor[floorId] ?: 1.0
                val ap = AccessPoint(
                    name = a.optString("name", "AP"),
                    vendor = a.optString("vendor", ""),
                    model = a.optString("model", ""),
                    floorId = floorId,
                    x = if (floorId != null && coord != null) coord.optDouble("x") * scale else null,
                    y = if (floorId != null && coord != null) coord.optDouble("y") * scale else null,
                    mine = a.optBoolean("mine", true),
                    source = if (radiosByAp.containsKey(a.optString("id"))) ApSource.MEASURED else ApSource.PLANNED
                )
                radiosByAp[a.optString("id")]?.forEach { if (it !in ap.bssids) ap.bssids.add(it) }
                project.accessPoints.add(ap)
            }
        }
        scaleByFloor.clear()
        tmp.deleteRecursively()
        if (project.floors.isEmpty()) throw IllegalArgumentException(ctx.getString(R.string.esx_no_bitmap))
        store.save(project)
        return project
    }

    private val scaleByFloor = HashMap<String, Double>()

    private fun decode(f: File): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, opts)
        if (opts.outWidth <= 0) return null
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / sample > 4096) sample *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(f.absolutePath, o2)
    }
}
