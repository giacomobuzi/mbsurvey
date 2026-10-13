package it.buzi.mbsurvey.export

import android.content.Context
import android.net.Uri
import it.buzi.mbsurvey.data.ProjectStore
import it.buzi.mbsurvey.model.Project
import it.buzi.mbsurvey.model.Survey
import it.buzi.mbsurvey.model.newId
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/** Restores a project from the app's own backup zip (project.json, survey JSON files, floor JPEGs). */
object JsonImporter {

    fun import(ctx: Context, store: ProjectStore, uri: Uri): Project {
        val tmp = File(ctx.cacheDir, "backup-import").apply { deleteRecursively(); mkdirs() }
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    if (!e.isDirectory) {
                        val out = File(tmp, e.name)
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                    e = zip.nextEntry
                }
            }
        } ?: throw IllegalArgumentException("File non leggibile")

        val pj = File(tmp, "project.json")
        if (!pj.exists()) throw IllegalArgumentException("project.json mancante nel backup")
        val src = Project.fromJson(JSONObject(pj.readText()))
        // New id to avoid clashing with an existing project.
        val p = Project(
            id = newId(), name = src.name, customer = src.customer, location = src.location, surveyor = src.surveyor,
            createdAt = src.createdAt, settings = src.settings
        )
        p.floors.addAll(src.floors)
        p.accessPoints.addAll(src.accessPoints)
        store.projectDir(p.id)
        for (f in p.floors) {
            val img = File(tmp, "floors/${f.imageFile}")
            if (img.exists()) img.copyTo(store.floorImageFile(p.id, f), overwrite = true)
        }
        File(tmp, "surveys").listFiles()?.filter { it.name.endsWith(".json") }?.forEach { f ->
            runCatching { Survey.fromJson(JSONObject(f.readText())) }.getOrNull()?.let { p.surveys.add(it) }
        }
        tmp.deleteRecursively()
        store.save(p)
        return p
    }
}
