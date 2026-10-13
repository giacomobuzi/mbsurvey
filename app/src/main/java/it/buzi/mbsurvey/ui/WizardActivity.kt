package it.buzi.mbsurvey.ui

import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import it.buzi.mbsurvey.R
import it.buzi.mbsurvey.data.ProjectStore
import it.buzi.mbsurvey.databinding.ActivityWizardBinding
import it.buzi.mbsurvey.databinding.ItemWizardStepBinding
import it.buzi.mbsurvey.model.Project
import it.buzi.mbsurvey.survey.SurveyEngine
import it.buzi.mbsurvey.wifi.WifiScanService

/** Step-by-step checklist for a complete survey, with the state of each step read from the project. */
class WizardActivity : BaseActivity() {

    private lateinit var b: ActivityWizardBinding
    private lateinit var store: ProjectStore
    private lateinit var project: Project
    private val exportUi = ExportUi(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityWizardBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        store = ProjectStore(this)
        val id = intent.getStringExtra(ProjectActivity.EXTRA_ID) ?: run { finish(); return }
        project = runCatching { store.load(id) }.getOrElse { finish(); return }
        supportActionBar?.subtitle = project.name
    }

    override fun onResume() {
        super.onResume()
        project = runCatching { store.load(project.id) }.getOrElse { finish(); return }
        build()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menu.add(0, 1, 0, R.string.edit_project).setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == 1) { startActivity(Intent(this, ProjectActivity::class.java).putExtra(ProjectActivity.EXTRA_ID, project.id)); return true }
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun build() {
        b.steps.removeAllViews()
        val floors = project.floors.size
        val calibrated = project.floors.count { it.isCalibrated }
        val scans = project.surveys.sumOf { it.locatedScans.size }
        val aps = project.accessPoints.size
        val mine = project.accessPoints.count { it.mine }
        val prepProblems = prepProblems()

        step(1, getString(R.string.wz_step_floor), floors > 0, getString(R.string.wz_floor_status, floors), getString(R.string.wz_floor_desc),
            getString(R.string.add_floor_draw)) { startActivity(Intent(this, PlanEditorActivity::class.java).putExtra(PlanEditorActivity.EXTRA_PROJECT, project.id)) }

        step(2, getString(R.string.wz_step_calib), floors > 0 && calibrated == floors, getString(R.string.wz_calib_status, calibrated, floors), getString(R.string.wz_calib_desc),
            getString(R.string.wz_open_floor)) { openFloor() }

        step(3, getString(R.string.wz_step_prep), prepProblems.isEmpty(),
            if (prepProblems.isEmpty()) getString(R.string.wz_prep_ok) else getString(R.string.wz_prep_ko, prepProblems.joinToString(", ")),
            getString(R.string.wz_prep_desc), getString(R.string.wz_check)) { fixPrep(prepProblems) }

        step(4, getString(R.string.wz_step_survey), scans > 0, getString(R.string.wz_survey_status, project.surveys.size, scans), getString(R.string.wz_survey_desc),
            getString(R.string.start_survey)) { openFloor() }

        step(5, getString(R.string.wz_step_aps), aps > 0, getString(R.string.wz_aps_status, aps, mine), getString(R.string.wz_aps_desc),
            getString(R.string.sync_aps)) {
            SurveyEngine.syncMeasuredAps(project); store.save(project); build()
        }

        step(6, getString(R.string.wz_step_export), false, getString(R.string.wz_export_status), getString(R.string.wz_export_desc),
            getString(R.string.export)) { exportUi.showMenu(project) }
    }

    private fun step(n: Int, title: String, done: Boolean, status: String, desc: String, action: String, onAction: () -> Unit) {
        val v = ItemWizardStepBinding.inflate(LayoutInflater.from(this), b.steps, false)
        v.badge.text = if (done) "✓" else n.toString()
        v.badge.setBackgroundResource(if (done) R.drawable.bg_badge_done else if (n == 6) R.drawable.bg_badge else R.drawable.bg_badge_todo)
        v.title.text = title
        v.status.text = (if (done) getString(R.string.wz_done) else getString(R.string.wz_todo)) + " · " + status
        v.description.text = desc
        v.action.text = action
        v.action.setOnClickListener { onAction() }
        b.steps.addView(v.root)
    }

    private fun prepProblems(): List<String> {
        val out = mutableListOf<String>()
        if (!WifiScanService.hasPermissions(this)) out.add(getString(R.string.wz_prep_perm))
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wm.isWifiEnabled) out.add(getString(R.string.wz_prep_wifi))
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!lm.isLocationEnabled) out.add(getString(R.string.wz_prep_loc))
        if (WifiScanService.isThrottleEnabled(this)) out.add(getString(R.string.wz_prep_throttle))
        return out
    }

    private fun fixPrep(problems: List<String>) {
        when {
            problems.contains(getString(R.string.wz_prep_perm)) -> startActivity(Intent(this, HomeActivity::class.java))
            problems.contains(getString(R.string.wz_prep_wifi)) -> runCatching { startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)) }
            problems.contains(getString(R.string.wz_prep_loc)) -> runCatching { startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
            problems.contains(getString(R.string.wz_prep_throttle)) -> Dialogs.throttleHelp(this)
            else -> build()
        }
    }

    private fun openProject() {
        startActivity(Intent(this, ProjectActivity::class.java).putExtra(ProjectActivity.EXTRA_ID, project.id))
    }

    private fun openFloor() {
        val f = project.floors.firstOrNull { !it.isCalibrated } ?: project.floors.firstOrNull()
        if (f == null) { finish(); return }
        startActivity(Intent(this, FloorActivity::class.java).putExtra(FloorActivity.EXTRA_PROJECT, project.id).putExtra(FloorActivity.EXTRA_FLOOR, f.id))
    }
}
