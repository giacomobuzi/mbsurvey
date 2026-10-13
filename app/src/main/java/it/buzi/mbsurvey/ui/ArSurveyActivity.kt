package it.buzi.mbsurvey.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.PointF
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import it.buzi.mbsurvey.R
import it.buzi.mbsurvey.ar.DeadReckoning
import it.buzi.mbsurvey.data.ProjectStore
import it.buzi.mbsurvey.databinding.ActivityArSurveyBinding
import it.buzi.mbsurvey.model.Floor
import it.buzi.mbsurvey.model.Project
import it.buzi.mbsurvey.survey.RecordingSession
import it.buzi.mbsurvey.wifi.ScanBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Camera survey: live camera preview, mini map with dead-reckoned position, signal bars, and a
 * prompt to record a survey point whenever the user stands still. Recording goes through
 * RecordingSession (continuous mode), so the data is identical to a classic survey.
 */
class ArSurveyActivity : BaseActivity(), DeadReckoning.Listener {

    companion object {
        const val EXTRA_PROJECT = "projectId"
        const val EXTRA_FLOOR = "floorId"
    }

    private enum class MapMode { NONE, ANCHOR, HEADING, CORRECT }

    private lateinit var b: ActivityArSurveyBinding
    private lateinit var store: ProjectStore
    private lateinit var project: Project
    private lateinit var floor: Floor
    private lateinit var dr: DeadReckoning
    private var mapMode = MapMode.NONE
    private var points = 0
    private var promptShownAt = 0L

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else Toast.makeText(this, R.string.ar_camera_perm, Toast.LENGTH_LONG).show()
    }
    private val activityPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { dr.start() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityArSurveyBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        store = ProjectStore(this)
        val pid = intent.getStringExtra(EXTRA_PROJECT) ?: run { finish(); return }
        val fid = intent.getStringExtra(EXTRA_FLOOR) ?: run { finish(); return }
        project = RecordingSession.projectFor(pid) ?: runCatching { store.load(pid) }.getOrElse { finish(); return }
        floor = project.floor(fid) ?: run { finish(); return }
        val s = RecordingSession.survey
        if (s == null || s.floorId != floor.id) { finish(); return }
        points = s.waypoints.size

        val mpp = if (floor.metersPerPx > 0) floor.metersPerPx else 0.05
        dr = DeadReckoning(this, mpp).apply {
            listener = this@ArSurveyActivity
            maxX = floor.widthPx.toDouble(); maxY = floor.heightPx.toDouble()
        }

        for (v in listOf(b.miniMap, b.bigMap)) {
            v.floorWidth = floor.widthPx; v.floorHeight = floor.heightPx
            v.showAps = true
        }
        b.miniMap.compact = true
        lifecycleScope.launch {
            val small = withContext(Dispatchers.IO) { store.loadFloorBitmap(project, floor, 1024) }
            val big = withContext(Dispatchers.IO) { store.loadFloorBitmap(project, floor, 2048) }
            b.miniMap.floorBitmap = small
            b.bigMap.floorBitmap = big
            refreshOverlays()
        }
        b.miniMap.onTap = { _, _ -> openMap(MapMode.CORRECT) }
        b.bigMap.onTap = { x, y -> onBigMapTap(x, y) }
        b.btnMap.setOnClickListener { if (b.bigMapWrap.visibility == View.VISIBLE) closeMap() else openMap(if (dr.anchored) MapMode.CORRECT else MapMode.ANCHOR) }
        b.btnHeading.setOnClickListener { openMap(MapMode.HEADING) }
        b.btnPoint.setOnClickListener { markPoint() }
        b.btnStop.setOnClickListener { stopSurvey() }
        b.promptYes.setOnClickListener { hidePrompt(); markPoint() }
        b.promptNo.setOnClickListener { hidePrompt() }

        // last waypoint as the starting anchor if it exists
        s.waypoints.lastOrNull()?.let { dr.anchor(it.x, it.y) }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else cameraPermission.launch(Manifest.permission.CAMERA)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED) dr.start()
        else activityPermission.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        if (!dr.hasStepDetector) Toast.makeText(this, R.string.ar_no_step, Toast.LENGTH_LONG).show()

        observe()
        if (!dr.anchored) openMap(MapMode.ANCHOR)
        updateStatus()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(b.preview.surfaceProvider) }
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
            }.onFailure { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show() }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { ScanBus.lastScan.collect { scan -> b.signals.entries = scan?.entries ?: emptyList(); b.signals.title = "Wi-Fi · ${scan?.entries?.size ?: 0}"; updateStatus() } }
                launch { RecordingSession.version.collect { refreshOverlays(); updateStatus() } }
            }
        }
    }

    private fun refreshOverlays() {
        val s = RecordingSession.survey
        val wps = s?.waypoints?.map { PointF(it.x.toFloat(), it.y.toFloat()) } ?: emptyList()
        for (v in listOf(b.miniMap, b.bigMap)) {
            v.liveWaypoints = wps
            v.accessPoints = project.accessPoints.filter { it.floorId == floor.id }
            v.notes = project.notesOf(floor.id)
        }
    }

    private fun updateStatus() {
        b.status.text = if (!dr.anchored) getString(R.string.ar_not_anchored)
        else getString(R.string.ar_status, dr.steps, dr.headingMapDeg, ScanBus.totalScans.value, RecordingSession.survey?.waypoints?.size ?: 0)
    }

    // ---------------- map interaction ----------------

    private fun openMap(mode: MapMode) {
        mapMode = mode
        b.bigMapWrap.visibility = View.VISIBLE
        b.bigMapHint.text = when (mode) {
            MapMode.ANCHOR -> getString(R.string.ar_anchor_hint)
            MapMode.HEADING -> getString(R.string.ar_heading_hint)
            else -> getString(R.string.ar_correct_hint)
        }
        b.bigMap.post { b.bigMap.fitToScreen() }
    }

    private fun closeMap() { b.bigMapWrap.visibility = View.GONE; mapMode = MapMode.NONE }

    private fun onBigMapTap(x: Double, y: Double) {
        when (mapMode) {
            MapMode.ANCHOR, MapMode.CORRECT -> {
                dr.anchor(x, y)
                closeMap()
                if (RecordingSession.survey?.waypoints?.isEmpty() == true) markPoint()
            }
            MapMode.HEADING -> {
                if (!dr.anchored) dr.anchor(x, y) else { dr.calibrateHeadingTowards(x, y); Toast.makeText(this, R.string.ar_heading_done, Toast.LENGTH_SHORT).show() }
                closeMap()
            }
            MapMode.NONE -> {}
        }
    }

    private fun markPoint() {
        if (!dr.anchored) { openMap(MapMode.ANCHOR); return }
        val s = RecordingSession.survey ?: return
        RecordingSession.addPoint(dr.x, dr.y)
        points = s.waypoints.size
        Toast.makeText(this, R.string.ar_point_added, Toast.LENGTH_SHORT).show()
        updateStatus()
    }

    private fun showPrompt() {
        if (b.promptCard.visibility == View.VISIBLE || b.bigMapWrap.visibility == View.VISIBLE || !dr.anchored) return
        val now = System.currentTimeMillis()
        if (now - promptShownAt < 8000) return
        promptShownAt = now
        b.promptCard.alpha = 0f; b.promptCard.visibility = View.VISIBLE
        b.promptCard.animate().alpha(1f).setDuration(200).start()
    }

    private fun hidePrompt() { b.promptCard.visibility = View.GONE }

    private fun stopSurvey() {
        val kept = RecordingSession.stop(this)
        Toast.makeText(this, if (kept > 0) getString(R.string.survey_saved, kept) else getString(R.string.survey_discarded), Toast.LENGTH_LONG).show()
        finish()
    }

    // ---------------- DeadReckoning.Listener ----------------

    override fun onPositionChanged(x: Double, y: Double, headingMapDeg: Float) {
        val p = PointF(x.toFloat(), y.toFloat())
        b.miniMap.livePosition = p; b.miniMap.liveHeadingDeg = headingMapDeg
        b.bigMap.livePosition = p; b.bigMap.liveHeadingDeg = headingMapDeg
    }

    override fun onStillnessChanged(still: Boolean) {
        if (still) showPrompt() else hidePrompt()
    }

    override fun onStep(count: Int) { updateStatus() }

    override fun onDestroy() {
        if (::dr.isInitialized) dr.stop()
        super.onDestroy()
    }
}
