package it.buzi.mbsurvey.ui

import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import it.buzi.mbsurvey.data.ProjectStore
import it.buzi.mbsurvey.databinding.ActivitySettingsBinding
import it.buzi.mbsurvey.model.Project

class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding
    private lateinit var store: ProjectStore
    private lateinit var project: Project

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        store = ProjectStore(this)
        val id = intent.getStringExtra(ProjectActivity.EXTRA_ID) ?: run { finish(); return }
        project = runCatching { store.load(id) }.getOrElse { finish(); return }

        val s = project.settings
        b.interval.setText(s.scanIntervalMs.toString())
        b.stopgo.setText(s.stopAndGoScans.toString())
        b.radius.setText(s.heatmapRadiusM.toString())
        b.good.setText(s.rssiGood.toString())
        b.okThreshold.setText(s.rssiOk.toString())
        b.min.setText(s.rssiMin.toString())
        b.apcount.setText(s.apCountThreshold.toString())
        b.noise.setText(s.noiseFloorDbm.toString())
        b.activeSurvey.isChecked = s.activeSurvey
        b.pingTarget.setText(s.pingTarget)
        b.pingProbes.setText(s.pingProbes.toString())
        b.latencyGood.setText(s.latencyGoodMs.toString())

        b.saveBtn.setOnClickListener {
            s.scanIntervalMs = longOf(b.interval, s.scanIntervalMs).coerceIn(500, 60000)
            s.stopAndGoScans = intOf(b.stopgo, s.stopAndGoScans).coerceIn(1, 20)
            s.heatmapRadiusM = doubleOf(b.radius, s.heatmapRadiusM).coerceIn(0.5, 30.0)
            s.rssiGood = intOf(b.good, s.rssiGood)
            s.rssiOk = intOf(b.okThreshold, s.rssiOk)
            s.rssiMin = intOf(b.min, s.rssiMin)
            s.apCountThreshold = intOf(b.apcount, s.apCountThreshold)
            s.noiseFloorDbm = intOf(b.noise, s.noiseFloorDbm)
            s.activeSurvey = b.activeSurvey.isChecked
            s.pingTarget = b.pingTarget.text.toString().trim()
            s.pingProbes = intOf(b.pingProbes, s.pingProbes).coerceIn(1, 10)
            s.latencyGoodMs = intOf(b.latencyGood, s.latencyGoodMs).coerceIn(1, 5000)
            store.save(project)
            Toast.makeText(this, "Impostazioni salvate", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun intOf(e: EditText, def: Int): Int = e.text.toString().trim().toIntOrNull() ?: def
    private fun longOf(e: EditText, def: Long): Long = e.text.toString().trim().toLongOrNull() ?: def
    private fun doubleOf(e: EditText, def: Double): Double = e.text.toString().trim().replace(',', '.').toDoubleOrNull() ?: def

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
