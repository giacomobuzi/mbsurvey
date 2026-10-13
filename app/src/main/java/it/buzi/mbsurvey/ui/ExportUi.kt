package it.buzi.mbsurvey.ui

import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import it.buzi.mbsurvey.R
import it.buzi.mbsurvey.data.ProjectStore
import it.buzi.mbsurvey.export.ExportKind
import it.buzi.mbsurvey.export.Exporter
import it.buzi.mbsurvey.export.ShareUtil
import it.buzi.mbsurvey.model.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Export menu + share/save flow, shared by the project screen and the wizard. Create it as an Activity field. */
class ExportUi(private val activity: AppCompatActivity) {

    private val store: ProjectStore by lazy { ProjectStore(activity) }

    private var pendingFile: File? = null

    private val saveFile = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val f = pendingFile
        if (uri != null && f != null) {
            runCatching {
                activity.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
                Toast.makeText(activity, activity.getString(R.string.export_done, f.name), Toast.LENGTH_SHORT).show()
            }.onFailure { Toast.makeText(activity, it.message, Toast.LENGTH_LONG).show() }
        }
    }

    fun showMenu(project: Project) {
        val kinds = ExportKind.values()
        val labels = kinds.map { activity.getString(it.labelRes) }.toTypedArray()
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.export_title)
            .setItems(labels) { _, which -> run(project, kinds[which]) }
            .show()
    }

    fun run(project: Project, kind: ExportKind) {
        Toast.makeText(activity, "Esportazione…", Toast.LENGTH_SHORT).show()
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching { Exporter(activity, store).export(project, kind) }
            }
            result.onSuccess { file -> offer(file, kind.mime) }
                .onFailure { e -> Dialogs.info(activity, activity.getString(R.string.export_title), activity.getString(R.string.export_error, e.message ?: e.toString())) }
        }
    }

    private fun offer(file: File, mime: String) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(file.name)
            .setMessage(R.string.export_saved_share)
            .setPositiveButton(R.string.share) { _, _ -> ShareUtil.share(activity, file, mime) }
            .setNeutralButton(R.string.save) { _, _ -> pendingFile = file; saveFile.launch(file.name) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
