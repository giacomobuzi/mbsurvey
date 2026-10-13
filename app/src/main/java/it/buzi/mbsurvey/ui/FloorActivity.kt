package it.buzi.mbsurvey.ui

import android.content.Context
import android.content.Intent
import android.graphics.PointF
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import it.buzi.mbsurvey.R
import it.buzi.mbsurvey.data.ProjectStore
import it.buzi.mbsurvey.databinding.ActivityFloorBinding
import it.buzi.mbsurvey.databinding.DialogSurveyStartBinding
import it.buzi.mbsurvey.heatmap.Heatmap
import it.buzi.mbsurvey.heatmap.HeatmapFilter
import it.buzi.mbsurvey.heatmap.HeatmapResult
import it.buzi.mbsurvey.heatmap.Metric
import it.buzi.mbsurvey.model.AccessPoint
import it.buzi.mbsurvey.model.ApSource
import it.buzi.mbsurvey.model.Band
import it.buzi.mbsurvey.model.Calibration
import it.buzi.mbsurvey.model.Floor
import it.buzi.mbsurvey.model.Project
import it.buzi.mbsurvey.model.SurveyMode
import it.buzi.mbsurvey.survey.RecordingSession
import it.buzi.mbsurvey.survey.SurveyEngine
import it.buzi.mbsurvey.wifi.ScanBus
import it.buzi.mbsurvey.wifi.WifiScanService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FloorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROJECT = "projectId"
        const val EXTRA_FLOOR = "floorId"
    }

    private enum class Mode { SURVEY, CALIBRATE, AP }

    private lateinit var b: ActivityFloorBinding
    private lateinit var store: ProjectStore
    private lateinit var project: Project
    private lateinit var floor: Floor

    private var mode = Mode.SURVEY
    private var calibPoints = ArrayList<PointF>()
    private var heatmapJob: Job? = null
    private var lastHeatmapVersion = -1
    private var currentHeatmap: HeatmapResult? = null

    private val metrics = Metric.values().filter { it != Metric.PER_AP }
    private var ssids: List<String> = emptyList()
    private var bssidChoices: List<Pair<String, String>> = emptyList() // (bssid, label)
    private var perApMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityFloorBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        store = ProjectStore(this)
        val pid = intent.getStringExtra(EXTRA_PROJECT) ?: run { finish(); return }
        val fid = intent.getStringExtra(EXTRA_FLOOR) ?: run { finish(); return }
        project = RecordingSession.projectFor(pid) ?: runCatching { store.load(pid) }.getOrElse { finish(); return }
        floor = project.floor(fid) ?: run { finish(); return }
        supportActionBar?.title = floor.name
        supportActionBar?.subtitle = project.name

        b.plan.floorWidth = floor.widthPx
        b.plan.floorHeight = floor.heightPx
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { store.loadFloorBitmap(project, floor, 2048) }
            b.plan.floorBitmap = bmp
            refreshOverlays()
            scheduleHeatmap(force = true)
        }

        setupSpinners()
        b.modeGroup.check(b.modeSurvey.id)
        b.modeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            mode = when (checkedId) {
                b.modeCalibrate.id -> Mode.CALIBRATE
                b.modeAp.id -> Mode.AP
                else -> Mode.SURVEY
            }
            calibPoints.clear()
            b.plan.calibrationPoints = emptyList()
            updateHint()
        }
        b.plan.onTap = { x, y -> onTap(x, y) }
        b.record.setOnClickListener { toggleRecording() }
        b.undo.setOnClickListener { undo() }

        observe()
        updateHint()
        if (!floor.isCalibrated) Toast.makeText(this, R.string.not_calibrated_warn, Toast.LENGTH_LONG).show()
    }

    // ---------------- spinners / heatmap ----------------

    private fun setupSpinners() {
        val metricLabels = metrics.map { it.label } + listOf(getString(R.string.heatmap_none), Metric.PER_AP.label)
        b.metricSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, metricLabels)
        b.bandSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf(getString(R.string.filter_all_bands), Band.B24.label, Band.B5.label, Band.B6.label))
        rebuildSsidSpinner()
        val listener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (parent === b.metricSpinner) {
                    val wantPerAp = position == metricLabels.size - 1
                    if (wantPerAp != perApMode) { perApMode = wantPerAp; rebuildSsidSpinner() }
                }
                scheduleHeatmap(force = true)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        b.metricSpinner.onItemSelectedListener = listener
        b.ssidSpinner.onItemSelectedListener = listener
        b.bandSpinner.onItemSelectedListener = listener
    }

    private fun rebuildSsidSpinner() {
        if (perApMode) {
            bssidChoices = SurveyEngine.radios(project, floor.id).map { r ->
                val ap = project.accessPoints.firstOrNull { it.bssids.contains(r.bssid) }
                val label = (ap?.name ?: r.ssid.ifBlank { "?" }) + " " + r.bssid.takeLast(8) + " ch" + r.channel
                Pair(r.bssid, label)
            }
            b.ssidSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, bssidChoices.map { it.second }.ifEmpty { listOf("—") })
        } else {
            ssids = SurveyEngine.ssids(project)
            b.ssidSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf(getString(R.string.filter_all_ssid)) + ssids)
        }
    }

    private fun currentFilter(): HeatmapFilter? {
        val mi = b.metricSpinner.selectedItemPosition
        val band = when (b.bandSpinner.selectedItemPosition) { 1 -> Band.B24; 2 -> Band.B5; 3 -> Band.B6; else -> null }
        if (mi == metrics.size) return null // none
        if (mi > metrics.size) {
            val bssid = bssidChoices.getOrNull(b.ssidSpinner.selectedItemPosition)?.first ?: return null
            return HeatmapFilter(Metric.PER_AP, null, band, bssid)
        }
        val si = b.ssidSpinner.selectedItemPosition
        val ssid = if (si <= 0) null else ssids.getOrNull(si - 1)
        return HeatmapFilter(metrics[mi], ssid, band, null)
    }

    private fun scheduleHeatmap(force: Boolean = false) {
        val v = RecordingSession.version.value
        if (!force && v == lastHeatmapVersion) return
        lastHeatmapVersion = v
        val filter = currentFilter()
        if (filter == null) { b.plan.heatmap = null; currentHeatmap = null; return }
        heatmapJob?.cancel()
        heatmapJob = lifecycleScope.launch {
            val res = withContext(Dispatchers.Default) { runCatching { Heatmap.render(project, floor, filter) }.getOrNull() }
            currentHeatmap = res
            b.plan.heatmapCellPx = res?.cellPx ?: 1.0
            b.plan.heatmap = res?.bitmap
        }
    }

    private fun refreshOverlays() {
        b.plan.surveys = project.surveysOf(floor.id).filter { it !== RecordingSession.survey }
        b.plan.liveWaypoints = RecordingSession.survey?.takeIf { it.floorId == floor.id }?.waypoints?.map { PointF(it.x.toFloat(), it.y.toFloat()) } ?: emptyList()
        b.plan.accessPoints = project.accessPoints.filter { it.floorId == floor.id }
    }

    // ---------------- observers ----------------

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    RecordingSession.version.collect {
                        refreshOverlays()
                        updateRecordButton()
                        updateHint()
                    }
                }
                launch {
                    ScanBus.lastScan.collect { updateStatus() }
                }
                launch {
                    ScanBus.throttled.collect { updateStatus() }
                }
                launch {
                    // Live heatmap refresh while recording, at most every 4 s.
                    while (true) {
                        delay(4000)
                        if (RecordingSession.isActive) scheduleHeatmap()
                    }
                }
            }
        }
    }

    private fun updateStatus() {
        val sb = StringBuilder()
        if (ScanBus.running.value) {
            val last = ScanBus.lastScan.value
            val max = last?.entries?.maxOfOrNull { it.rssi } ?: -100
            sb.append(getString(R.string.status_scanning, ScanBus.totalScans.value, last?.entries?.size ?: 0, max))
            if (ScanBus.staleScans.value > 0) sb.append("\n").append(getString(R.string.status_stale, ScanBus.staleScans.value))
        } else {
            sb.append(getString(R.string.status_idle))
        }
        if (ScanBus.throttled.value && WifiScanService.isThrottleEnabled(this)) sb.append("\n").append(getString(R.string.status_throttled))
        b.status.text = sb.toString()
    }

    private fun updateRecordButton() {
        val active = RecordingSession.isActive && RecordingSession.survey?.floorId == floor.id
        b.record.setImageResource(if (active) R.drawable.ic_stop else R.drawable.ic_record)
        b.undo.visibility = if (active) View.VISIBLE else View.INVISIBLE
    }

    private fun updateHint() {
        val s = RecordingSession.survey
        val text: String? = when (mode) {
            Mode.CALIBRATE -> if (calibPoints.isEmpty()) getString(R.string.calib_first) else getString(R.string.calib_second)
            Mode.AP -> getString(R.string.ap_place)
            Mode.SURVEY -> when {
                s == null || s.floorId != floor.id -> null
                s.mode == SurveyMode.CONTINUOUS -> if (s.waypoints.isEmpty()) getString(R.string.tap_start_point) else getString(R.string.tap_next_point)
                else -> {
                    val (c, n) = RecordingSession.collectingState.value
                    if (RecordingSession.isCollecting) getString(R.string.stop_go_collecting, c, n)
                    else if (s.waypoints.isEmpty()) getString(R.string.tap_start_point) else getString(R.string.stop_go_next)
                }
            }
        }
        b.hint.text = text ?: ""
        b.hint.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    // ---------------- taps ----------------

    private fun onTap(x: Double, y: Double) {
        when (mode) {
            Mode.SURVEY -> {
                val s = RecordingSession.survey
                if (s == null || s.floorId != floor.id) {
                    Toast.makeText(this, R.string.start_survey, Toast.LENGTH_SHORT).show()
                    return
                }
                if (s.mode == SurveyMode.STOP_AND_GO && RecordingSession.isCollecting) return
                RecordingSession.addPoint(x, y)
                b.plan.pendingTap = null
            }
            Mode.CALIBRATE -> {
                calibPoints.add(PointF(x.toFloat(), y.toFloat()))
                b.plan.calibrationPoints = calibPoints.toList()
                if (calibPoints.size == 2) askCalibrationDistance()
                updateHint()
            }
            Mode.AP -> placeAp(x, y)
        }
    }

    private fun askCalibrationDistance() {
        Dialogs.textInput(this, getString(R.string.mode_calibrate), getString(R.string.calib_distance), "",
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL) { v ->
            val m = v.replace(',', '.').toDoubleOrNull()
            if (m != null && m > 0 && calibPoints.size == 2) {
                val c = Calibration(calibPoints[0].x.toDouble(), calibPoints[0].y.toDouble(), calibPoints[1].x.toDouble(), calibPoints[1].y.toDouble(), m)
                floor.calibration = c
                floor.metersPerPx = c.metersPerPx()
                store.save(project)
                Toast.makeText(this, getString(R.string.calib_done, floor.metersPerPx), Toast.LENGTH_LONG).show()
                scheduleHeatmap(force = true)
            }
            calibPoints.clear()
            b.plan.calibrationPoints = emptyList()
            updateHint()
        }
    }

    private fun placeAp(x: Double, y: Double) {
        val aps = project.accessPoints
        val labels = aps.map { it.name } + getString(R.string.ap_new)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ap_choose)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < aps.size) {
                    val ap = aps[which]
                    ap.floorId = floor.id; ap.x = x; ap.y = y
                    store.save(project); refreshOverlays()
                } else {
                    Dialogs.textInput(this, getString(R.string.ap_new), getString(R.string.name), "AP-${aps.size + 1}") { n ->
                        val ap = AccessPoint(name = n.ifBlank { "AP" }, floorId = floor.id, x = x, y = y, mine = true, source = ApSource.MANUAL)
                        project.accessPoints.add(ap)
                        store.save(project); refreshOverlays()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------- recording ----------------

    private fun toggleRecording() {
        val s = RecordingSession.survey
        if (s != null) {
            if (s.floorId != floor.id) {
                Toast.makeText(this, "Survey in corso su un altro piano", Toast.LENGTH_SHORT).show()
                return
            }
            val kept = RecordingSession.stop(this)
            Toast.makeText(this, if (kept > 0) getString(R.string.survey_saved, kept) else getString(R.string.survey_discarded), Toast.LENGTH_LONG).show()
            project = runCatching { store.load(project.id) }.getOrDefault(project)
            floor = project.floor(floor.id) ?: floor
            rebuildSsidSpinner()
            refreshOverlays()
            updateRecordButton()
            updateStatus()
            scheduleHeatmap(force = true)
            return
        }
        if (!preflight()) return
        val d = DialogSurveyStartBinding.inflate(layoutInflater)
        d.name.setText("Survey ${project.surveysOf(floor.id).size + 1}")
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.start_survey)
            .setView(d.root)
            .setPositiveButton(R.string.ok) { _, _ ->
                val m = if (d.modeStopGo.isChecked) SurveyMode.STOP_AND_GO else SurveyMode.CONTINUOUS
                RecordingSession.start(this, store, project, floor.id, d.name.text.toString().trim().ifBlank { "Survey" }, m)
                b.modeGroup.check(b.modeSurvey.id)
                updateRecordButton()
                updateHint()
                updateStatus()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun preflight(): Boolean {
        if (!WifiScanService.hasPermissions(this)) {
            Toast.makeText(this, R.string.perm_needed, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, ProjectsActivity::class.java))
            return false
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wm.isWifiEnabled) {
            Toast.makeText(this, R.string.wifi_off, Toast.LENGTH_LONG).show()
            runCatching { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
            return false
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!lm.isLocationEnabled) {
            Toast.makeText(this, R.string.location_off, Toast.LENGTH_LONG).show()
            runCatching { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
            return false
        }
        if (WifiScanService.isThrottleEnabled(this)) {
            Dialogs.throttleHelp(this)
        }
        return true
    }

    private fun undo() {
        RecordingSession.undoPoint()
    }

    // ---------------- menu ----------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_floor, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            R.id.action_show_path -> { item.isChecked = !item.isChecked; b.plan.showPath = item.isChecked }
            R.id.action_show_aps -> { item.isChecked = !item.isChecked; b.plan.showAps = item.isChecked }
            R.id.action_legend -> showLegend()
            R.id.action_throttle_help -> Dialogs.throttleHelp(this)
            R.id.action_help -> startActivity(Intent(this, HelpActivity::class.java))
            R.id.action_tools -> startActivity(Intent(this, NetToolsActivity::class.java))
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun showLegend() {
        val f = currentFilter()
        val res = currentHeatmap
        val sb = StringBuilder()
        if (f == null || res == null) {
            sb.append(getString(R.string.heatmap_none))
        } else {
            sb.append(f.metric.label).append(" (").append(f.metric.unit).append(")\n")
            res.legend.forEach { (v, _) -> sb.append("  ").append(v.toInt()).append(" ").append(f.metric.unit).append("\n") }
            sb.append("\nMin ${"%.0f".format(res.min)} · Media ${"%.1f".format(res.avg)} · Max ${"%.0f".format(res.max)}\n")
            val area = Heatmap.coveredAreaM2(res, floor)
            if (area > 0) sb.append("Area rilevata: ${"%.0f".format(area)} m²\n")
            if (f.metric == Metric.PRIMARY_RSSI || f.metric == Metric.SECONDARY_RSSI || f.metric == Metric.PER_AP) {
                val good = Heatmap.coverageShare(res, f.metric, project.settings.rssiGood.toFloat()) * 100
                val ok = Heatmap.coverageShare(res, f.metric, project.settings.rssiOk.toFloat()) * 100
                sb.append("≥ ${project.settings.rssiGood} dBm: ${"%.0f".format(good)} %\n≥ ${project.settings.rssiOk} dBm: ${"%.0f".format(ok)} %")
            }
        }
        Dialogs.info(this, getString(R.string.legend), sb.toString())
    }

    override fun onResume() {
        super.onResume()
        updateRecordButton()
        updateStatus()
        updateHint()
    }
}
