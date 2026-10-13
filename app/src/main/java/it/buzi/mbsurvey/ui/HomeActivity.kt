package it.buzi.mbsurvey.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import it.buzi.mbsurvey.R
import it.buzi.mbsurvey.data.ProjectStore
import it.buzi.mbsurvey.databinding.ActivityHomeBinding
import it.buzi.mbsurvey.databinding.DialogProjectBinding
import it.buzi.mbsurvey.databinding.ItemHomeCardBinding
import it.buzi.mbsurvey.model.Project
import it.buzi.mbsurvey.net.NetTools
import it.buzi.mbsurvey.wifi.WifiScanService

/** Welcome screen: entry points to the sections. */
class HomeActivity : BaseActivity() {

    private lateinit var b: ActivityHomeBinding
    private lateinit var store: ProjectStore
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(b.root)
        store = ProjectStore(this)

        card(b.cardNew, R.drawable.ic_wizard, R.string.home_new, getString(R.string.home_new_desc)) { newProject() }
        card(b.cardProjects, R.drawable.ic_folder, R.string.home_projects, "") { startActivity(Intent(this, ProjectsActivity::class.java)) }
        card(b.cardTools, R.drawable.ic_tools, R.string.home_tools, getString(R.string.home_tools_desc)) { startActivity(Intent(this, NetToolsActivity::class.java)) }
        card(b.cardHelp, R.drawable.ic_book, R.string.home_help, getString(R.string.home_help_desc)) { startActivity(Intent(this, HelpActivity::class.java)) }
        b.homeStatus.setOnClickListener { if (WifiScanService.isThrottleEnabled(this)) Dialogs.throttleHelp(this) }

        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) { perms.add(Manifest.permission.NEARBY_WIFI_DEVICES); perms.add(Manifest.permission.POST_NOTIFICATIONS) }
        if (!WifiScanService.hasPermissions(this)) permissionLauncher.launch(perms.toTypedArray())
    }

    private fun card(c: ItemHomeCardBinding, icon: Int, title: Int, subtitle: String, onClick: () -> Unit) {
        c.icon.setImageResource(icon)
        c.title.setText(title)
        c.subtitle.text = subtitle
        c.card.setOnClickListener { onClick() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val n = store.listProjects().size
        b.cardProjects.subtitle.text = getString(R.string.home_projects_desc, n)
        val info = runCatching { NetTools.connectionInfo(this) }.getOrNull()
        val throttle = WifiScanService.isThrottleEnabled(this)
        b.homeStatus.text = when {
            info?.ssid == null -> getString(R.string.home_status_nowifi)
            throttle -> getString(R.string.home_status_throttle, info.ssid)
            else -> getString(R.string.home_status_ok, info.ssid, info.rssi ?: 0)
        }
        b.homeStatus.setBackgroundResource(if (throttle && info?.ssid != null) R.drawable.bg_status_warn else R.drawable.bg_status)
    }

    private fun newProject() {
        val d = DialogProjectBinding.inflate(layoutInflater)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.new_project)
            .setView(d.root)
            .setPositiveButton(R.string.ok) { _, _ ->
                val p = Project(
                    name = d.name.text.toString().trim().ifBlank { getString(R.string.default_project) },
                    customer = d.customer.text.toString().trim(),
                    location = d.location.text.toString().trim(),
                    surveyor = d.surveyor.text.toString().trim()
                )
                store.save(p)
                startActivity(Intent(this, WizardActivity::class.java).putExtra(ProjectActivity.EXTRA_ID, p.id))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun finish() {
        super.finish()
    }
}
