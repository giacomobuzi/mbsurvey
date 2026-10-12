package it.buzi.wifisurvey.export

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import it.buzi.wifisurvey.R
import java.io.File

object ShareUtil {
    fun exportDir(ctx: Context): File = File(ctx.cacheDir, "exports").apply { mkdirs() }

    fun share(activity: Activity, file: File, mime: String) {
        val uri = FileProvider.getUriForFile(activity, activity.packageName + ".files", file)
        val i = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        activity.startActivity(Intent.createChooser(i, activity.getString(R.string.share)))
    }

    fun safeName(s: String): String = s.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifBlank { "export" }
}
