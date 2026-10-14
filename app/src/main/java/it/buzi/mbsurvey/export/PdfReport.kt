package it.buzi.mbsurvey.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import it.buzi.mbsurvey.R
import it.buzi.mbsurvey.data.ProjectStore
import it.buzi.mbsurvey.heatmap.Heatmap
import it.buzi.mbsurvey.heatmap.HeatmapFilter
import it.buzi.mbsurvey.heatmap.Metric
import it.buzi.mbsurvey.model.Floor
import it.buzi.mbsurvey.model.Project
import it.buzi.mbsurvey.survey.SurveyEngine
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A4 PDF report: cover, per-floor heatmaps with statistics, AP and BSSID tables. */
class PdfReport(private val ctx: Context, private val store: ProjectStore) {

    private val W = 595
    private val H = 842
    private val margin = 40f
    private val doc = PdfDocument()
    private var pageNo = 0
    private var page: PdfDocument.Page? = null
    private var canvas: Canvas? = null
    private var y = 0f

    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; isFakeBoldText = true; color = Color.rgb(11, 110, 79) }
    private val h2 = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 14f; isFakeBoldText = true; color = Color.BLACK }
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f; color = Color.BLACK }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8f; color = Color.DKGRAY }
    private val line = Paint().apply { color = Color.LTGRAY; strokeWidth = 0.5f }
    private val dateFmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
    private fun g(id: Int, vararg args: Any): String = ctx.getString(id, *args)

    fun write(p: Project, file: File) {
        cover(p)
        for (floor in p.floors) floorSection(p, floor)
        networksSection(p)
        apTable(p)
        notesSection(p)
        radioTable(p)
        endPage()
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
    }

    // ---------------- page helpers ----------------

    private fun newPage(p: Project) {
        endPage()
        pageNo++
        val pg = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
        page = pg
        canvas = pg.canvas
        y = margin
        canvas!!.drawText("${p.name} · ${g(R.string.report_footer)}", margin, H - 20f, small)
        canvas!!.drawText("Pagina $pageNo", W - margin - 40f, H - 20f, small)
    }

    private fun endPage() {
        page?.let { doc.finishPage(it) }
        page = null
    }

    private fun ensure(p: Project, needed: Float) {
        if (page == null || y + needed > H - margin) newPage(p)
    }

    private fun text(p: Project, s: String, paint: Paint = body, indent: Float = 0f) {
        val maxW = W - 2 * margin - indent
        var rest = s
        while (rest.isNotEmpty()) {
            var n = paint.breakText(rest, true, maxW, null)
            if (n < rest.length) {
                val sp = rest.lastIndexOf(' ', n)
                if (sp > 0) n = sp + 1
            }
            ensure(p, paint.textSize + 4f)
            canvas!!.drawText(rest.substring(0, n).trimEnd(), margin + indent, y + paint.textSize, paint)
            y += paint.textSize + 4f
            rest = rest.substring(n)
        }
    }

    private fun gap(h: Float) { y += h }

    private fun tableRow(p: Project, cells: List<String>, widths: List<Float>, paint: Paint = body, header: Boolean = false) {
        ensure(p, paint.textSize + 8f)
        var x = margin
        if (header) {
            canvas!!.drawRect(margin, y, W - margin, y + paint.textSize + 6f, Paint().apply { color = Color.rgb(235, 240, 238) })
        }
        for ((i, cell) in cells.withIndex()) {
            val w = widths[i]
            var t = cell
            while (t.isNotEmpty() && paint.measureText(t) > w - 4f) t = t.dropLast(1)
            canvas!!.drawText(t, x + 2f, y + paint.textSize + 1f, paint)
            x += w
        }
        y += paint.textSize + 6f
        canvas!!.drawLine(margin, y, W - margin, y, line)
    }

    // ---------------- sections ----------------

    private fun cover(p: Project) {
        newPage(p)
        gap(80f)
        text(p, g(R.string.r_title), title)
        gap(10f)
        text(p, p.name, h2)
        gap(20f)
        if (p.customer.isNotBlank()) text(p, g(R.string.r_customer, p.customer))
        if (p.location.isNotBlank()) text(p, g(R.string.r_location, p.location))
        if (p.surveyor.isNotBlank()) text(p, g(R.string.r_surveyor, p.surveyor))
        text(p, g(R.string.r_date, dateFmt.format(Date())))
        val first = p.surveys.minOfOrNull { it.startedAt }
        val last = p.surveys.maxOfOrNull { it.endedAt ?: it.startedAt }
        if (first != null && last != null) text(p, g(R.string.r_period, dateFmt.format(Date(first)), dateFmt.format(Date(last))))
        gap(20f)
        text(p, g(R.string.r_summary), h2)
        gap(6f)
        val widths = listOf(150f, 70f, 70f, 80f, 70f, 75f)
        tableRow(p, listOf(g(R.string.r_col_floor), g(R.string.surveys), g(R.string.r_col_scans), g(R.string.r_col_area), "≥ ${p.settings.rssiGood} dBm", "≥ ${p.settings.rssiOk} dBm"), widths, body, true)
        for (f in p.floors) {
            val surveys = p.surveysOf(f.id)
            val scans = surveys.sumOf { it.locatedScans.size }
            val res = Heatmap.render(p, f, HeatmapFilter(Metric.PRIMARY_RSSI))
            val area = if (res != null) "%.0f".format(Heatmap.coveredAreaM2(res, f)) else "-"
            val good = if (res != null) "%.0f %%".format(Heatmap.coverageShare(res, Metric.PRIMARY_RSSI, p.settings.rssiGood.toFloat()) * 100) else "-"
            val ok = if (res != null) "%.0f %%".format(Heatmap.coverageShare(res, Metric.PRIMARY_RSSI, p.settings.rssiOk.toFloat()) * 100) else "-"
            tableRow(p, listOf(f.name, surveys.size.toString(), scans.toString(), area, good, ok), widths)
        }
        gap(20f)
        text(p, g(R.string.r_method), h2)
        gap(4f)
        text(p, g(R.string.r_method_text, p.settings.noiseFloorDbm, p.settings.heatmapRadiusM))
        gap(8f)
        text(p, g(R.string.r_thresholds, p.settings.rssiGood, p.settings.rssiOk, p.settings.rssiMin, p.settings.apCountThreshold))
    }

    private fun floorSection(p: Project, floor: Floor) {
        if (p.surveysOf(floor.id).isEmpty()) return
        val metrics = listOf(Metric.PRIMARY_RSSI, Metric.SECONDARY_RSSI, Metric.SNR, Metric.AP_COUNT, Metric.INTERFERENCE, Metric.LATENCY, Metric.PACKET_LOSS)
        for (m in metrics) {
            val filter = HeatmapFilter(m)
            val res = Heatmap.render(p, floor, filter, alpha = 160) ?: continue
            val bmp = Exporter(ctx, store).compositeWith(p, floor, res, filter, 1400) ?: continue
            newPage(p)
            text(p, "${floor.name} · ${m.label(ctx)}", h2)
            gap(4f)
            val maxW = W - 2 * margin
            val maxH = H - margin - y - 110f
            val s = minOf(maxW / bmp.width, maxH / bmp.height)
            val dw = bmp.width * s; val dh = bmp.height * s
            canvas!!.drawBitmap(bmp, null, RectF(margin, y, margin + dw, y + dh), Paint(Paint.FILTER_BITMAP_FLAG))
            y += dh + 10f
            bmp.recycle()
            val area = Heatmap.coveredAreaM2(res, floor)
            text(p, g(R.string.area_surveyed, area) + " · " + g(R.string.stat_min_avg_max, res.min, res.avg, res.max) + " " + m.unit)
            when (m) {
                Metric.PRIMARY_RSSI, Metric.SECONDARY_RSSI -> {
                    val good = Heatmap.coverageShare(res, m, p.settings.rssiGood.toFloat()) * 100
                    val ok = Heatmap.coverageShare(res, m, p.settings.rssiOk.toFloat()) * 100
                    val bad = 100 - Heatmap.coverageShare(res, m, p.settings.rssiMin.toFloat()) * 100
                    text(p, g(R.string.r_cov_rssi, p.settings.rssiGood, good, p.settings.rssiOk, ok, p.settings.rssiMin, bad))
                }
                Metric.SNR -> text(p, g(R.string.r_cov_snr, Heatmap.coverageShare(res, m, 25f) * 100))
                Metric.AP_COUNT -> text(p, g(R.string.r_cov_ap, p.settings.apCountThreshold, Heatmap.coverageShare(res, m, 2f) * 100))
                Metric.INTERFERENCE -> text(p, g(R.string.r_cov_int, Heatmap.coverageShare(res, m, 0f) * 100))
                Metric.LATENCY -> text(p, g(R.string.r_cov_lat, p.settings.latencyGoodMs, Heatmap.coverageShare(res, m, p.settings.latencyGoodMs.toFloat()) * 100, p.settings.pingTarget.ifBlank { "gateway" }))
                Metric.PACKET_LOSS -> text(p, g(R.string.r_cov_loss, Heatmap.coverageShare(res, m, 0f) * 100))
                else -> {}
            }
        }
    }

    private fun networksSection(p: Project) {
        if (SurveyEngine.ssidSummary(p).isEmpty()) return
        newPage(p)
        networkTables(p, null, g(R.string.r_networks))
        if (p.floors.size > 1) for (f in p.floors) {
            if (SurveyEngine.ssidSummary(p, f.id).isEmpty()) continue
            gap(18f)
            networkTables(p, f.id, g(R.string.r_floor_networks, f.name))
        }
    }

    private fun networkTables(p: Project, floorId: String?, title: String) {
        val ssids = SurveyEngine.ssidSummary(p, floorId)
        val channels = SurveyEngine.channelSummary(p, floorId)
        ensure(p, 60f)
        text(p, title, h2)
        gap(4f)
        text(p, g(R.string.r_net_summary, SurveyEngine.apCount(p, floorId), ssids.size, ssids.sumOf { it.bssids.size }, channels.size))
        gap(6f)
        val w1 = listOf(150f, 90f, 80f, 60f, 60f, 75f)
        tableRow(p, listOf("SSID", g(R.string.r_col_bands), g(R.string.r_col_security), "BSSID", g(R.string.r_col_aps), "Max dBm"), w1, body, true)
        for (s in ssids) tableRow(p, listOf(s.ssid, s.bands.joinToString("/") { it.label.replace(" GHz", "") }, s.security, s.bssids.size.toString(), s.apCount.toString(), s.maxRssi.toString()), w1, small)
        gap(16f)
        text(p, g(R.string.r_channels), h2)
        gap(6f)
        val w2 = listOf(90f, 80f, 80f, 80f, 80f, 105f)
        tableRow(p, listOf(g(R.string.nt_lbl_band), g(R.string.r_col_channel), g(R.string.r_col_width), "BSSID", g(R.string.r_col_aps), "Max dBm"), w2, body, true)
        for (c in channels) tableRow(p, listOf(c.band.label, c.channel.toString(), "${c.widthMhz} MHz", c.bssids.toString(), c.aps.toString(), c.maxRssi.toString()), w2, small)
    }

    private fun apTable(p: Project) {
        if (p.accessPoints.isEmpty()) return
        newPage(p)
        text(p, g(R.string.access_points), h2)
        gap(6f)
        val widths = listOf(130f, 90f, 90f, 40f, 165f)
        tableRow(p, listOf(g(R.string.name), g(R.string.r_col_floor), g(R.string.r_col_vendor), g(R.string.ap_mine), "BSSID"), widths, body, true)
        for (ap in p.accessPoints.sortedWith(compareByDescending<it.buzi.mbsurvey.model.AccessPoint> { it.mine }.thenBy { it.name })) {
            tableRow(p, listOf(
                ap.name, p.floor(ap.floorId)?.name ?: "-", listOf(ap.vendor, ap.model).filter { it.isNotBlank() }.joinToString(" "),
                g(if (ap.mine) R.string.yes else R.string.no), ap.bssids.joinToString(", ")
            ), widths, small)
        }
    }

    private fun notesSection(p: Project) {
        if (p.notes.isEmpty()) return
        newPage(p)
        text(p, g(R.string.report_notes), h2)
        gap(6f)
        for (floor in p.floors) {
            val notes = p.notesOf(floor.id)
            if (notes.isEmpty()) continue
            text(p, floor.name, body.apply { isFakeBoldText = true }); body.isFakeBoldText = false
            notes.forEachIndexed { i, n ->
                val pos = if (floor.metersPerPx > 0) " (%.1f m, %.1f m)".format(n.x * floor.metersPerPx, n.y * floor.metersPerPx) else ""
                text(p, "${i + 1}. ${n.text.ifBlank { "-" }}$pos", body, 10f)
                val photo = n.photoFile?.let { store.notePhotoFile(p.id, it) }
                if (photo != null && photo.exists()) {
                    val bmp = store.loadFloorBitmapFile(photo, 600)
                    if (bmp != null) {
                        val w = 220f; val h = w * bmp.height / bmp.width
                        ensure(p, h + 8f)
                        canvas!!.drawBitmap(bmp, null, RectF(margin + 10f, y, margin + 10f + w, y + h), Paint(Paint.FILTER_BITMAP_FLAG))
                        y += h + 8f
                        bmp.recycle()
                    }
                }
            }
            gap(6f)
        }
    }

    private fun radioTable(p: Project) {
        val radios = SurveyEngine.radios(p)
        if (radios.isEmpty()) return
        newPage(p)
        text(p, g(R.string.r_radios), h2)
        gap(6f)
        val widths = listOf(95f, 120f, 50f, 40f, 45f, 55f, 55f, 55f)
        tableRow(p, listOf("BSSID", "SSID", g(R.string.nt_lbl_band), "Ch", g(R.string.r_col_width), "Max dBm", g(R.string.r_col_avg), g(R.string.r_col_samples)), widths, body, true)
        for (r in radios) {
            tableRow(p, listOf(r.bssid, r.ssid, r.band.label, r.channel.toString(), "${r.widthMhz}", r.maxRssi.toString(), "%.0f".format(r.avgRssi), r.samples.toString()), widths, small)
        }
    }
}
