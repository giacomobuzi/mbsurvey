package it.buzi.mbsurvey.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import it.buzi.mbsurvey.R
import it.buzi.mbsurvey.data.ProjectStore
import it.buzi.mbsurvey.databinding.ActivityProjectBinding
import it.buzi.mbsurvey.databinding.DialogApBinding
import it.buzi.mbsurvey.databinding.DialogProjectBinding
import it.buzi.mbsurvey.model.AccessPoint
import it.buzi.mbsurvey.model.Floor
import it.buzi.mbsurvey.model.PlanDrawing
import it.buzi.mbsurvey.plan.PlanRenderer
import it.buzi.mbsurvey.model.Project
import it.buzi.mbsurvey.model.Survey
import it.buzi.mbsurvey.survey.SurveyEngine
import it.buzi.mbsurvey.wifi.WifiScanService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProjectActivity : BaseActivity() {

    companion object { const val EXTRA_ID = "projectId" }

    private lateinit var b: ActivityProjectBinding
    private lateinit var store: ProjectStore
    private lateinit var project: Project
    private lateinit var floorsAdapter: TwoLineAdapter<Floor>
    private lateinit var surveysAdapter: TwoLineAdapter<Survey>
    private lateinit var apsAdapter: TwoLineAdapter<AccessPoint>
    private val dateFmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY)

    private val exportUi = ExportUi(this)

    private val pickImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { addFloorFrom(it, false) }
    }
    private val pickPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { addFloorFrom(it, true) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityProjectBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        store = ProjectStore(this)
        val id = intent.getStringExtra(EXTRA_ID) ?: run { finish(); return }
        project = runCatching { store.load(id) }.getOrElse { finish(); return }

        floorsAdapter = TwoLineAdapter(
            title = { it.name },
            subtitle = { f ->
                val surveys = project.surveysOf(f.id).size
                val cal = if (f.isCalibrated) getString(R.string.floor_calibrated, f.widthPx * f.metersPerPx, f.heightPx * f.metersPerPx)
                else getString(R.string.floor_not_calibrated)
                "$cal · ${f.widthPx}×${f.heightPx} px · $surveys ${getString(R.string.surveys).lowercase()}"
            },
            onClick = { f -> startActivity(Intent(this, FloorActivity::class.java).putExtra(FloorActivity.EXTRA_PROJECT, project.id).putExtra(FloorActivity.EXTRA_FLOOR, f.id)) },
            onMore = { f, v -> floorMenu(f, v) }
        )
        surveysAdapter = TwoLineAdapter(
            title = { it.name },
            subtitle = { s ->
                val floor = project.floor(s.floorId)?.name ?: "?"
                getString(R.string.survey_summary, floor, s.locatedScans.size, s.waypoints.size) + " · " + dateFmt.format(Date(s.startedAt))
            },
            onClick = { s -> startActivity(Intent(this, FloorActivity::class.java).putExtra(FloorActivity.EXTRA_PROJECT, project.id).putExtra(FloorActivity.EXTRA_FLOOR, s.floorId)) },
            onMore = { s, v -> surveyMenu(s, v) }
        )
        apsAdapter = TwoLineAdapter(
            title = { ap -> (if (ap.mine) "● " else "○ ") + ap.name },
            subtitle = { ap ->
                val floor = project.floor(ap.floorId)?.name
                val pos = if (ap.x != null && floor != null) floor else getString(R.string.ap_unplaced)
                val vm = listOf(ap.vendor, ap.model).filter { it.isNotBlank() }.joinToString(" ")
                listOf(getString(R.string.ap_summary, pos, ap.bssids.size), vm, ap.source.name.lowercase()).filter { it.isNotBlank() }.joinToString(" · ")
            },
            onClick = { ap -> editAp(ap) },
            onMore = { ap, v -> apMenu(ap, v) }
        )
        b.floorsList.layoutManager = LinearLayoutManager(this)
        b.floorsList.adapter = floorsAdapter
        b.surveysList.layoutManager = LinearLayoutManager(this)
        b.surveysList.adapter = surveysAdapter
        b.apsList.layoutManager = LinearLayoutManager(this)
        b.apsList.adapter = apsAdapter

        b.addFloor.setOnClickListener { v ->
            val m = PopupMenu(this, v)
            m.menu.add(0, 3, 0, R.string.add_floor_draw)
            m.menu.add(0, 1, 1, R.string.add_floor_image)
            m.menu.add(0, 2, 2, R.string.add_floor_pdf)
            m.setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> pickImage.launch(arrayOf("image/*"))
                    2 -> pickPdf.launch(arrayOf("application/pdf"))
                    3 -> startActivity(Intent(this, PlanEditorActivity::class.java).putExtra(PlanEditorActivity.EXTRA_PROJECT, project.id))
                }
                true
            }
            m.show()
        }
        b.syncAps.setOnClickListener {
            SurveyEngine.syncMeasuredAps(project)
            store.save(project)
            refresh()
        }
        b.throttleWarn.setOnClickListener { Dialogs.throttleHelp(this) }
        b.wizardBtn.setOnClickListener { startActivity(Intent(this, WizardActivity::class.java).putExtra(EXTRA_ID, project.id)) }
    }

    override fun onResume() {
        super.onResume()
        project = runCatching { store.load(project.id) }.getOrElse { finish(); return }
        refresh()
        b.throttleWarn.visibility = if (WifiScanService.isThrottleEnabled(this)) View.VISIBLE else View.GONE
    }

    private fun refresh() {
        supportActionBar?.title = project.name
        supportActionBar?.subtitle = listOf(project.customer, project.location).filter { it.isNotBlank() }.joinToString(" · ")
        floorsAdapter.items = project.floors
        surveysAdapter.items = project.surveys.sortedByDescending { it.startedAt }
        apsAdapter.items = project.accessPoints.sortedWith(compareByDescending<AccessPoint> { it.mine }.thenBy { it.name })
        b.floorsEmpty.visibility = if (project.floors.isEmpty()) View.VISIBLE else View.GONE
        b.surveysEmpty.visibility = if (project.surveys.isEmpty()) View.VISIBLE else View.GONE
        b.apsEmpty.visibility = if (project.accessPoints.isEmpty()) View.VISIBLE else View.GONE
    }

    // ---------------- floors ----------------

    private fun addFloorFrom(uri: Uri, pdf: Boolean) {
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { runCatching { if (pdf) renderPdf(uri) else decodeImage(uri) }.getOrNull() }
            if (bmp == null) {
                Toast.makeText(this@ProjectActivity, getString(R.string.floor_read_error), Toast.LENGTH_LONG).show()
                return@launch
            }
            Dialogs.textInput(this@ProjectActivity, getString(R.string.add_floor), getString(R.string.floor_name_hint), getString(R.string.default_floor, project.floors.size + 1)) { name ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        store.addFloor(project, name.ifBlank { getString(R.string.default_floor_plain) }, bmp)
                        store.save(project)
                    }
                    refresh()
                }
            }
        }
    }

    private fun decodeImage(uri: Uri): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / sample > 4096) sample *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) } ?: return null
        return flattenOnWhite(bmp)
    }

    private fun renderPdf(uri: Uri): Bitmap? {
        val pfd: ParcelFileDescriptor = contentResolver.openFileDescriptor(uri, "r") ?: return null
        var result: Bitmap? = null
        pfd.use { fd ->
            val renderer = PdfRenderer(fd)
            try {
                if (renderer.pageCount > 0) {
                    val page = renderer.openPage(0)
                    try {
                        val scale = 3500f / maxOf(page.width, page.height)
                        val w = (page.width * scale).toInt()
                        val h = (page.height * scale).toInt()
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        Canvas(bmp).drawColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        result = bmp
                    } finally {
                        page.close()
                    }
                }
            } finally {
                renderer.close()
            }
        }
        return result
    }

    private fun flattenOnWhite(src: Bitmap): Bitmap {
        if (!src.hasAlpha()) return src
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        c.drawBitmap(src, 0f, 0f, null)
        return out
    }

    private fun floorMenu(f: Floor, anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, R.string.rename)
        if (f.isDrawn) { m.menu.add(0, 3, 1, R.string.edit_drawing); m.menu.add(0, 4, 2, R.string.duplicate_floor) }
        m.menu.add(0, 2, 3, R.string.delete)
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                3 -> startActivity(Intent(this, PlanEditorActivity::class.java).putExtra(PlanEditorActivity.EXTRA_PROJECT, project.id).putExtra(PlanEditorActivity.EXTRA_FLOOR, f.id))
                4 -> Dialogs.textInput(this, getString(R.string.duplicate_floor), getString(R.string.floor_name_hint), getString(R.string.default_floor, project.floors.size + 1)) { n ->
                    val drawing = f.drawing ?: return@textInput
                    lifecycleScope.launch {
                        withContext(Dispatchers.Default) {
                            val copy = drawing.copy()
                            val nf = store.addFloor(project, n.ifBlank { getString(R.string.default_floor_plain) }, PlanRenderer.toBitmap(copy), 4096)
                            nf.drawing = copy
                            nf.metersPerPx = 1.0 / PlanDrawing.pxPerMeter(copy.widthM, copy.heightM)
                            store.save(project)
                        }
                        refresh()
                    }
                }
                1 -> Dialogs.textInput(this, getString(R.string.rename), getString(R.string.floor_name_hint), f.name) { n ->
                    if (n.isNotBlank()) { f.name = n; store.save(project); refresh() }
                }
                2 -> Dialogs.confirm(this, getString(R.string.delete_floor_confirm, f.name)) {
                    project.surveys.removeAll { s -> s.floorId == f.id }
                    project.accessPoints.forEach { ap -> if (ap.floorId == f.id) { ap.floorId = null; ap.x = null; ap.y = null } }
                    project.floors.remove(f)
                    store.save(project); refresh()
                }
            }
            true
        }
        m.show()
    }

    // ---------------- surveys ----------------

    private fun surveyMenu(s: Survey, anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, R.string.rename)
        m.menu.add(0, 2, 1, R.string.delete)
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> Dialogs.textInput(this, getString(R.string.rename), getString(R.string.survey_name_hint), s.name) { n ->
                    if (n.isNotBlank()) { s.name = n; store.save(project); refresh() }
                }
                2 -> Dialogs.confirm(this, getString(R.string.delete_survey_confirm, s.name)) {
                    project.surveys.remove(s); store.save(project); refresh()
                }
            }
            true
        }
        m.show()
    }

    // ---------------- access points ----------------

    private fun editAp(ap: AccessPoint) {
        val d = DialogApBinding.inflate(layoutInflater)
        d.name.setText(ap.name)
        d.vendor.setText(ap.vendor)
        d.model.setText(ap.model)
        d.mine.isChecked = ap.mine
        d.bssids.text = ap.bssids.joinToString("\n")
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.access_points)
            .setView(d.root)
            .setPositiveButton(R.string.save) { _, _ ->
                ap.name = d.name.text.toString().trim().ifBlank { ap.name }
                ap.vendor = d.vendor.text.toString().trim()
                ap.model = d.model.text.toString().trim()
                ap.mine = d.mine.isChecked
                store.save(project); refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun apMenu(ap: AccessPoint, anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, R.string.delete)
        m.setOnMenuItemClickListener {
            project.accessPoints.remove(ap); store.save(project); refresh(); true
        }
        m.show()
    }

    // ---------------- menu ----------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_project, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            R.id.action_export -> exportDialog()
            R.id.action_wizard -> startActivity(Intent(this, WizardActivity::class.java).putExtra(EXTRA_ID, project.id))
            R.id.action_help -> startActivity(Intent(this, HelpActivity::class.java))
            R.id.action_tools -> startActivity(Intent(this, NetToolsActivity::class.java))
            R.id.action_channels -> startActivity(Intent(this, NetToolsActivity::class.java).putExtra(NetToolsActivity.EXTRA_PROJECT, project.id))
            R.id.action_settings -> startActivity(Intent(this, SettingsActivity::class.java).putExtra(EXTRA_ID, project.id))
            R.id.action_edit -> editProject()
            R.id.action_delete -> Dialogs.confirm(this, getString(R.string.delete_project_confirm, project.name)) {
                store.delete(project.id); finish()
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun editProject() {
        val d = DialogProjectBinding.inflate(layoutInflater)
        d.name.setText(project.name); d.customer.setText(project.customer)
        d.location.setText(project.location); d.surveyor.setText(project.surveyor)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.edit_project)
            .setView(d.root)
            .setPositiveButton(R.string.save) { _, _ ->
                project.name = d.name.text.toString().trim().ifBlank { project.name }
                project.customer = d.customer.text.toString().trim()
                project.location = d.location.text.toString().trim()
                project.surveyor = d.surveyor.text.toString().trim()
                store.save(project); refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun exportDialog() = exportUi.showMenu(project)
}
