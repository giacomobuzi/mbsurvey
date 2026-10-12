package it.buzi.wifisurvey.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.annotation.StringRes
import it.buzi.wifisurvey.R
import it.buzi.wifisurvey.data.ProjectStore
import it.buzi.wifisurvey.heatmap.Heatmap
import it.buzi.wifisurvey.heatmap.HeatmapFilter
import it.buzi.wifisurvey.heatmap.HeatmapResult
import it.buzi.wifisurvey.heatmap.Metric
import it.buzi.wifisurvey.model.Floor
import it.buzi.wifisurvey.model.Project
import it.buzi.wifisurvey.survey.SurveyEngine
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class ExportKind(@StringRes val labelRes: Int, val mime: String) {
    CSV(R.string.export_csv, "text/csv"),
    JSON(R.string.export_json, "application/zip"),
    PNG(R.string.export_png, "application/zip"),
    PDF(R.string.export_pdf, "application/pdf"),
    ESX(R.string.export_esx, "application/octet-stream")
}

class Exporter(private val ctx: Context, private val store: ProjectStore) {

    private val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ITALY).format(Date())

    fun export(p: Project, kind: ExportKind): File {
        val base = ShareUtil.safeName(p.name) + "-" + stamp
        val dir = ShareUtil.exportDir(ctx)
        return when (kind) {
            ExportKind.CSV -> File(dir, "$base.csv").also { writeCsv(p, it) }
            ExportKind.JSON -> File(dir, "$base.wifisurvey.zip").also { writeBackupZip(p, it) }
            ExportKind.PNG -> File(dir, "$base-heatmaps.zip").also { writePngZip(p, it) }
            ExportKind.PDF -> File(dir, "$base-report.pdf").also { PdfReport(ctx, store).write(p, it) }
            ExportKind.ESX -> File(dir, "$base.esx").also { EsxExporter(ctx, store).write(p, it) }
        }
    }

    // ---------------- CSV ----------------

    private fun writeCsv(p: Project, file: File) {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
        file.bufferedWriter().use { w ->
            w.write("timestamp;survey;floor;x_px;y_px;x_m;y_m;bssid;ssid;rssi_dbm;freq_mhz;channel;width_mhz;band;security;ap_name\n")
            for (s in p.surveys) {
                val floor = p.floor(s.floorId) ?: continue
                val mpp = floor.metersPerPx
                for (scan in s.scans) {
                    val x = scan.x ?: continue
                    val y = scan.y ?: continue
                    val ts = iso.format(Date(scan.t))
                    for (e in scan.entries) {
                        val ap = p.accessPoints.firstOrNull { it.bssids.contains(e.bssid) }?.name ?: ""
                        w.write(listOf(
                            ts, q(s.name), q(floor.name),
                            "%.1f".format(Locale.US, x), "%.1f".format(Locale.US, y),
                            if (mpp > 0) "%.2f".format(Locale.US, x * mpp) else "", if (mpp > 0) "%.2f".format(Locale.US, y * mpp) else "",
                            e.bssid, q(e.ssid), e.rssi.toString(), e.freqMhz.toString(), e.channel.toString(), e.widthMhz.toString(),
                            e.band.label, e.security, q(ap)
                        ).joinToString(";"))
                        w.write("\n")
                    }
                }
            }
        }
    }

    private fun q(s: String): String = if (s.contains(';') || s.contains('"') || s.contains('\n')) "\"" + s.replace("\"", "\"\"") + "\"" else s

    // ---------------- backup ----------------

    /** project.json + surveys/*.json + floors/*.jpg, restorable with JsonImporter. */
    private fun writeBackupZip(p: Project, file: File) {
        ZipOutputStream(FileOutputStream(file).buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("project.json"))
            zip.write(p.toJson().toString(2).toByteArray())
            zip.closeEntry()
            for (s in p.surveys) {
                zip.putNextEntry(ZipEntry("surveys/${s.id}.json"))
                zip.write(s.toJson().toString().toByteArray())
                zip.closeEntry()
            }
            for (f in p.floors) {
                val img = store.floorImageFile(p.id, f)
                if (!img.exists()) continue
                zip.putNextEntry(ZipEntry("floors/${f.imageFile}"))
                img.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    // ---------------- PNG ----------------

    private fun writePngZip(p: Project, file: File) {
        ZipOutputStream(FileOutputStream(file).buffered()).use { zip ->
            for (floor in p.floors) {
                if (p.surveysOf(floor.id).isEmpty()) continue
                for (metric in listOf(Metric.PRIMARY_RSSI, Metric.SECONDARY_RSSI, Metric.SNR, Metric.AP_COUNT, Metric.INTERFERENCE)) {
                    val bmp = composite(p, floor, HeatmapFilter(metric)) ?: continue
                    zip.putNextEntry(ZipEntry(ShareUtil.safeName(floor.name) + "-" + metric.name.lowercase() + ".png"))
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, zip)
                    zip.closeEntry()
                    bmp.recycle()
                }
            }
        }
    }

    /** Floor plan + heatmap + APs + legend rendered to one bitmap (max 2000 px). */
    fun composite(p: Project, floor: Floor, filter: HeatmapFilter, maxSide: Int = 2000): Bitmap? {
        val res = Heatmap.render(p, floor, filter, alpha = 160) ?: return null
        return compositeWith(p, floor, res, filter, maxSide)
    }

    fun compositeWith(p: Project, floor: Floor, res: HeatmapResult, filter: HeatmapFilter, maxSide: Int = 2000): Bitmap? {
        val base = store.loadFloorBitmap(p, floor, maxSide) ?: return null
        val out = Bitmap.createBitmap(base.width, base.height + 70, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        c.drawBitmap(base, 0f, 0f, null)
        val sx = base.width.toFloat() / floor.widthPx
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        c.drawBitmap(res.bitmap, null, RectF(0f, 0f, (res.cols * res.cellPx * sx).toFloat(), (res.rows * res.cellPx * sx).toFloat()), paint)
        drawPathsAndAps(c, p, floor, sx, base.width)
        drawLegend(c, res, filter, base.width, base.height.toFloat())
        base.recycle()
        return out
    }

    private fun drawPathsAndAps(c: Canvas, p: Project, floor: Floor, sx: Float, w: Int) {
        val r = maxOf(4f, w / 180f)
        val path = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(160, 20, 90, 200); strokeWidth = r / 2; style = Paint.Style.STROKE }
        for (s in p.surveysOf(floor.id)) {
            var px = 0f; var py = 0f; var first = true
            for (wp in s.waypoints) {
                val x = (wp.x * sx).toFloat(); val y = (wp.y * sx).toFloat()
                if (!first) c.drawLine(px, py, x, y, path)
                px = x; py = y; first = false
            }
        }
        val ap = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(11, 110, 79) }
        val apOther = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(110, 110, 110) }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = r / 3 }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = r * 2.2f; isFakeBoldText = true }
        val bg = Paint().apply { color = Color.argb(200, 255, 255, 255) }
        for (a in p.accessPoints) {
            if (a.floorId != floor.id) continue
            val x = ((a.x ?: continue) * sx).toFloat(); val y = ((a.y ?: continue) * sx).toFloat()
            c.drawCircle(x, y, r * 1.4f, if (a.mine) ap else apOther)
            c.drawCircle(x, y, r * 1.4f, stroke)
            val tw = text.measureText(a.name)
            c.drawRect(x + r * 1.8f, y - r * 1.8f, x + r * 1.8f + tw + r, y + r * 0.8f, bg)
            c.drawText(a.name, x + r * 2.3f, y, text)
        }
    }

    private fun drawLegend(c: Canvas, res: HeatmapResult, filter: HeatmapFilter, w: Int, top: Float) {
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 22f }
        c.drawText("${filter.metric.label} (${filter.metric.unit})" + (filter.ssid?.let { " · SSID $it" } ?: "") + (filter.band?.let { " · ${it.label}" } ?: ""), 10f, top + 24f, text)
        val legend = res.legend
        val x0 = 10f; val x1 = w - 10f; val y0 = top + 34f; val y1 = top + 52f
        val vmin = legend.first().first; val vmax = legend.last().first
        val steps = (x1 - x0).toInt() / 2
        val p = Paint()
        for (i in 0 until steps) {
            val v = vmin + (vmax - vmin) * i / steps
            p.color = Heatmap.colorFor(v, legend)
            c.drawRect(x0 + i * 2f, y0, x0 + i * 2f + 2f, y1, p)
        }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 16f }
        for ((v, _) in legend) {
            val x = x0 + (x1 - x0) * (v - vmin) / (vmax - vmin)
            c.drawText(v.toInt().toString(), x, y1 + 16f, small)
        }
    }

    companion object {
        fun radiosSummary(p: Project) = SurveyEngine.radios(p)
    }
}
