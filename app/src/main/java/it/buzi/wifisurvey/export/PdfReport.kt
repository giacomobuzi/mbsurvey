package it.buzi.wifisurvey.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import it.buzi.wifisurvey.data.ProjectStore
import it.buzi.wifisurvey.heatmap.Heatmap
import it.buzi.wifisurvey.heatmap.HeatmapFilter
import it.buzi.wifisurvey.heatmap.Metric
import it.buzi.wifisurvey.model.Floor
import it.buzi.wifisurvey.model.Project
import it.buzi.wifisurvey.survey.SurveyEngine
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
    private val dateFmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY)

    fun write(p: Project, file: File) {
        cover(p)
        for (floor in p.floors) floorSection(p, floor)
        apTable(p)
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
        canvas!!.drawText("${p.name} · WiFi Survey report", margin, H - 20f, small)
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
        text(p, "Report site survey Wi-Fi", title)
        gap(10f)
        text(p, p.name, h2)
        gap(20f)
        if (p.customer.isNotBlank()) text(p, "Cliente: ${p.customer}")
        if (p.location.isNotBlank()) text(p, "Sede: ${p.location}")
        if (p.surveyor.isNotBlank()) text(p, "Rilevatore: ${p.surveyor}")
        text(p, "Data report: ${dateFmt.format(Date())}")
        val first = p.surveys.minOfOrNull { it.startedAt }
        val last = p.surveys.maxOfOrNull { it.endedAt ?: it.startedAt }
        if (first != null && last != null) text(p, "Rilevazioni: dal ${dateFmt.format(Date(first))} al ${dateFmt.format(Date(last))}")
        gap(20f)
        text(p, "Sintesi", h2)
        gap(6f)
        val widths = listOf(150f, 70f, 70f, 80f, 70f, 75f)
        tableRow(p, listOf("Piano", "Survey", "Scansioni", "Area (m²)", "≥ ${p.settings.rssiGood} dBm", "≥ ${p.settings.rssiOk} dBm"), widths, body, true)
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
        text(p, "Metodo", h2)
        gap(4f)
        text(p, "Survey passiva effettuata con smartphone Android (scheda Wi-Fi integrata). Il percorso è stato tracciato " +
            "sulla planimetria e le posizioni delle scansioni sono interpolate sul tempo tra i punti toccati. I valori di segnale " +
            "sono quelli misurati dalla scheda del telefono e possono differire di alcuni dB da un adattatore professionale. " +
            "L'SNR è stimato con un rumore di fondo di ${p.settings.noiseFloorDbm} dBm perché Android non espone il livello di rumore. " +
            "Le heatmap sono interpolate (IDW) con raggio ${p.settings.heatmapRadiusM} m.")
        gap(8f)
        text(p, "Soglie: buono ≥ ${p.settings.rssiGood} dBm, sufficiente ≥ ${p.settings.rssiOk} dBm, minimo utile ${p.settings.rssiMin} dBm, " +
            "conteggio AP con soglia ${p.settings.apCountThreshold} dBm.")
    }

    private fun floorSection(p: Project, floor: Floor) {
        if (p.surveysOf(floor.id).isEmpty()) return
        val metrics = listOf(Metric.PRIMARY_RSSI, Metric.SECONDARY_RSSI, Metric.SNR, Metric.AP_COUNT, Metric.INTERFERENCE)
        for (m in metrics) {
            val filter = HeatmapFilter(m)
            val res = Heatmap.render(p, floor, filter, alpha = 160) ?: continue
            val bmp = Exporter(ctx, store).compositeWith(p, floor, res, filter, 1400) ?: continue
            newPage(p)
            text(p, "${floor.name} · ${m.label}", h2)
            gap(4f)
            val maxW = W - 2 * margin
            val maxH = H - margin - y - 110f
            val s = minOf(maxW / bmp.width, maxH / bmp.height)
            val dw = bmp.width * s; val dh = bmp.height * s
            canvas!!.drawBitmap(bmp, null, RectF(margin, y, margin + dw, y + dh), Paint(Paint.FILTER_BITMAP_FLAG))
            y += dh + 10f
            bmp.recycle()
            val area = Heatmap.coveredAreaM2(res, floor)
            text(p, "Area rilevata: ${"%.0f".format(area)} m² · min ${"%.0f".format(res.min)} · media ${"%.1f".format(res.avg)} · max ${"%.0f".format(res.max)} ${m.unit}")
            when (m) {
                Metric.PRIMARY_RSSI, Metric.SECONDARY_RSSI -> {
                    val good = Heatmap.coverageShare(res, m, p.settings.rssiGood.toFloat()) * 100
                    val ok = Heatmap.coverageShare(res, m, p.settings.rssiOk.toFloat()) * 100
                    val bad = 100 - Heatmap.coverageShare(res, m, p.settings.rssiMin.toFloat()) * 100
                    text(p, "Copertura ≥ ${p.settings.rssiGood} dBm: ${"%.0f".format(good)} % · ≥ ${p.settings.rssiOk} dBm: ${"%.0f".format(ok)} % · sotto ${p.settings.rssiMin} dBm: ${"%.0f".format(bad)} %")
                }
                Metric.SNR -> text(p, "Area con SNR ≥ 25 dB: ${"%.0f".format(Heatmap.coverageShare(res, m, 25f) * 100)} %")
                Metric.AP_COUNT -> text(p, "Area con almeno 2 AP ≥ ${p.settings.apCountThreshold} dBm: ${"%.0f".format(Heatmap.coverageShare(res, m, 2f) * 100)} %")
                Metric.INTERFERENCE -> text(p, "Area senza sovrapposizioni di canale: ${"%.0f".format(Heatmap.coverageShare(res, m, 0f) * 100)} %")
                else -> {}
            }
        }
    }

    private fun apTable(p: Project) {
        if (p.accessPoints.isEmpty()) return
        newPage(p)
        text(p, "Access point", h2)
        gap(6f)
        val widths = listOf(130f, 90f, 90f, 40f, 165f)
        tableRow(p, listOf("Nome", "Piano", "Vendor / modello", "Mio", "BSSID"), widths, body, true)
        for (ap in p.accessPoints.sortedWith(compareByDescending<it.buzi.wifisurvey.model.AccessPoint> { it.mine }.thenBy { it.name })) {
            tableRow(p, listOf(
                ap.name, p.floor(ap.floorId)?.name ?: "-", listOf(ap.vendor, ap.model).filter { it.isNotBlank() }.joinToString(" "),
                if (ap.mine) "sì" else "no", ap.bssids.joinToString(", ")
            ), widths, small)
        }
    }

    private fun radioTable(p: Project) {
        val radios = SurveyEngine.radios(p)
        if (radios.isEmpty()) return
        newPage(p)
        text(p, "Radio rilevate (BSSID)", h2)
        gap(6f)
        val widths = listOf(95f, 120f, 50f, 40f, 45f, 55f, 55f, 55f)
        tableRow(p, listOf("BSSID", "SSID", "Banda", "Ch", "Larg.", "Max dBm", "Media", "Campioni"), widths, body, true)
        for (r in radios) {
            tableRow(p, listOf(r.bssid, r.ssid, r.band.label, r.channel.toString(), "${r.widthMhz}", r.maxRssi.toString(), "%.0f".format(r.avgRssi), r.samples.toString()), widths, small)
        }
    }
}
