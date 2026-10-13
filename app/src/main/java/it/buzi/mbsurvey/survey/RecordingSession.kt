package it.buzi.mbsurvey.survey

import android.content.Context
import it.buzi.mbsurvey.data.ProjectStore
import it.buzi.mbsurvey.model.Project
import it.buzi.mbsurvey.model.Scan
import it.buzi.mbsurvey.model.Survey
import it.buzi.mbsurvey.model.SurveyMode
import it.buzi.mbsurvey.model.Waypoint
import it.buzi.mbsurvey.net.NetTools
import kotlinx.coroutines.withContext
import it.buzi.mbsurvey.wifi.ScanBus
import it.buzi.mbsurvey.wifi.WifiScanService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Holds the survey being recorded, independent of the Activity lifecycle, and feeds it with scans
 * from [ScanBus]. Only one recording at a time.
 */
object RecordingSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectJob: Job? = null
    private var autosaveJob: Job? = null

    var projectId: String? = null; private set
    var survey: Survey? = null; private set
    private var project: Project? = null
    private var store: ProjectStore? = null

    /** Stop-and-go state. */
    private var pendingX = 0.0
    private var pendingY = 0.0
    private var collecting = false
    private var collectedAtPoint = 0
    private var scansPerPoint = 3

    /** UI observables. */
    val active = MutableStateFlow(false)
    val version = MutableStateFlow(0)          // bumps whenever the survey changes
    val collectingState = MutableStateFlow(Pair(0, 0)) // collected / target at the current point

    val isActive: Boolean get() = survey != null

    /** The live Project instance when a recording for that project is running. */
    fun projectFor(id: String): Project? = if (projectId == id) project else null
    val mode: SurveyMode? get() = survey?.mode
    val isCollecting: Boolean get() = collecting

    fun start(ctx: Context, store: ProjectStore, project: Project, floorId: String, name: String, mode: SurveyMode) {
        stopInternal(save = true)
        this.store = store
        this.project = project
        this.projectId = project.id
        val s = Survey(floorId = floorId, name = name, mode = mode, startedAt = System.currentTimeMillis())
        survey = s
        project.surveys.add(s)
        scansPerPoint = project.settings.stopAndGoScans
        pingProbes = project.settings.pingProbes.coerceIn(1, 10)
        pingHost = if (project.settings.activeSurvey) (project.settings.pingTarget.ifBlank { NetTools.gateway(ctx) }) else null
        collecting = false
        collectedAtPoint = 0
        collectingState.value = Pair(0, scansPerPoint)
        ScanBus.totalScans.value = 0
        ScanBus.staleScans.value = 0
        WifiScanService.start(ctx, project.settings.scanIntervalMs)
        collectJob = scope.launch { ScanBus.scans.collect { onScan(it) } }
        autosaveJob = scope.launch {
            while (true) { delay(20000); saveNow() }
        }
        active.value = true
        version.value++
    }

    private var pingHost: String? = null
    private var pingProbes = 3

    private fun onScan(scan: Scan) {
        val s = survey ?: return
        val host = pingHost
        if (host != null) {
            scope.launch(Dispatchers.IO) {
                val r = runCatching { NetTools.probe(host, pingProbes, 1) }.getOrNull()
                if (r != null) { scan.rttMs = if (r.received > 0) r.avgMs else null; scan.lossPct = r.lossPct }
                withContext(Dispatchers.Main) { version.value++ }
            }
        }
        when (s.mode) {
            SurveyMode.CONTINUOUS -> {
                if (s.waypoints.isNotEmpty()) s.scans.add(scan)
            }
            SurveyMode.STOP_AND_GO -> {
                if (collecting) {
                    scan.x = pendingX; scan.y = pendingY
                    s.scans.add(scan)
                    collectedAtPoint++
                    collectingState.value = Pair(collectedAtPoint, scansPerPoint)
                    if (collectedAtPoint >= scansPerPoint) collecting = false
                }
            }
        }
        version.value++
    }

    /** User tapped the floor plan at (x, y). */
    fun addPoint(x: Double, y: Double) {
        val s = survey ?: return
        val now = System.currentTimeMillis()
        s.waypoints.add(Waypoint(now, x, y))
        when (s.mode) {
            SurveyMode.CONTINUOUS -> SurveyEngine.resolveLastSegment(s)
            SurveyMode.STOP_AND_GO -> {
                pendingX = x; pendingY = y
                collecting = true
                collectedAtPoint = 0
                collectingState.value = Pair(0, scansPerPoint)
            }
        }
        version.value++
        saveNow()
    }

    fun undoPoint() {
        val s = survey ?: return
        if (s.waypoints.isEmpty()) return
        val removed = s.waypoints.removeAt(s.waypoints.size - 1)
        when (s.mode) {
            SurveyMode.CONTINUOUS -> SurveyEngine.resolveAll(s)
            SurveyMode.STOP_AND_GO -> {
                s.scans.removeAll { it.x == removed.x && it.y == removed.y && it.t >= removed.t }
                collecting = false
            }
        }
        version.value++
    }

    fun saveNow() {
        val p = project ?: return
        val s = survey ?: return
        runCatching { store?.saveSurvey(p, s) }
    }

    /** Ends the recording. Returns the number of located scans kept (0 = survey discarded). */
    fun stop(ctx: Context): Int {
        val s = survey
        val p = project
        val kept = stopInternal(save = true)
        WifiScanService.stop(ctx)
        if (s != null && p != null && kept == 0) {
            p.surveys.remove(s)
            runCatching { store?.save(p) }
        }
        return kept
    }

    private fun stopInternal(save: Boolean): Int {
        collectJob?.cancel(); collectJob = null
        autosaveJob?.cancel(); autosaveJob = null
        val s = survey
        val p = project
        var kept = 0
        if (s != null && p != null) {
            s.endedAt = System.currentTimeMillis()
            if (s.mode == SurveyMode.CONTINUOUS) {
                // Drop scans after the last waypoint: they have no position.
                s.scans.removeAll { !it.isLocated }
            } else {
                s.scans.removeAll { !it.isLocated }
            }
            kept = s.scans.size
            if (save && kept > 0) runCatching { store?.save(p) }
        }
        survey = null
        project = null
        projectId = null
        collecting = false
        active.value = false
        version.value++
        return kept
    }
}
