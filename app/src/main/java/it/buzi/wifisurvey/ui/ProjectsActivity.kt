package it.buzi.wifisurvey.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import it.buzi.wifisurvey.R
import it.buzi.wifisurvey.data.ProjectStore
import it.buzi.wifisurvey.databinding.ActivityProjectsBinding
import it.buzi.wifisurvey.databinding.DialogProjectBinding
import it.buzi.wifisurvey.export.EsxImporter
import it.buzi.wifisurvey.export.JsonImporter
import it.buzi.wifisurvey.model.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProjectsActivity : AppCompatActivity() {

    private lateinit var b: ActivityProjectsBinding
    private lateinit var store: ProjectStore
    private lateinit var adapter: TwoLineAdapter<Project>

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    private val pickEsx = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importFile(it, true) }
    }
    private val pickJson = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importFile(it, false) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityProjectsBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        store = ProjectStore(this)

        adapter = TwoLineAdapter(
            title = { it.name },
            subtitle = { p ->
                val c = listOf(p.customer, p.location).filter { it.isNotBlank() }.joinToString(" · ")
                val s = getString(R.string.project_summary, p.floors.size, surveyCount(p), p.accessPoints.size)
                if (c.isBlank()) s else "$c\n$s"
            },
            onClick = { open(it) },
            onMore = { p, v -> showMenu(p, v) }
        )
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        b.fab.setOnClickListener { newProject() }
        requestPermissions()
    }

    private fun surveyCount(p: Project): Int = store.surveysDir(p.id).listFiles()?.count { it.name.endsWith(".json") } ?: 0

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val list = store.listProjects()
        adapter.items = list
        b.empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun open(p: Project) {
        startActivity(Intent(this, ProjectActivity::class.java).putExtra(ProjectActivity.EXTRA_ID, p.id))
    }

    private fun newProject() {
        val d = DialogProjectBinding.inflate(layoutInflater)
        AlertDialog.Builder(this)
            .setTitle(R.string.new_project)
            .setView(d.root)
            .setPositiveButton(R.string.ok) { _, _ ->
                val name = d.name.text.toString().trim().ifBlank { "Progetto" }
                val p = Project(
                    name = name,
                    customer = d.customer.text.toString().trim(),
                    location = d.location.text.toString().trim(),
                    surveyor = d.surveyor.text.toString().trim()
                )
                store.save(p)
                open(p)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showMenu(p: Project, anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, R.string.delete)
        m.setOnMenuItemClickListener {
            Dialogs.confirm(this, getString(R.string.delete_project_confirm, p.name)) {
                store.delete(p.id)
                refresh()
            }
            true
        }
        m.show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_projects, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_import_esx -> pickEsx.launch(arrayOf("*/*"))
            R.id.action_import_json -> pickJson.launch(arrayOf("application/zip", "*/*"))
            R.id.action_throttle_help -> Dialogs.throttleHelp(this)
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun importFile(uri: Uri, esx: Boolean) {
        Toast.makeText(this, "Importazione in corso…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (esx) EsxImporter.import(this@ProjectsActivity, store, uri)
                    else JsonImporter.import(this@ProjectsActivity, store, uri)
                }
            }
            result.onSuccess { p ->
                refresh()
                open(p)
            }.onFailure { e ->
                Dialogs.info(this@ProjectsActivity, "Import", e.message ?: e.toString())
            }
        }
    }
}
