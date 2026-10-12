package it.buzi.wifisurvey.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import it.buzi.wifisurvey.model.Floor
import it.buzi.wifisurvey.model.Project
import it.buzi.wifisurvey.model.Survey
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * File layout under filesDir/projects/<projectId>/:
 *   project.json          project metadata, floors, access points, settings
 *   floors/<floorId>.jpg  floor plan images
 *   surveys/<surveyId>.json
 */
class ProjectStore(context: Context) {
    private val root = File(context.filesDir, "projects").apply { mkdirs() }

    fun projectDir(projectId: String): File = File(root, projectId).apply { mkdirs() }
    fun floorsDir(projectId: String): File = File(projectDir(projectId), "floors").apply { mkdirs() }
    fun surveysDir(projectId: String): File = File(projectDir(projectId), "surveys").apply { mkdirs() }
    fun floorImageFile(projectId: String, floor: Floor): File = File(floorsDir(projectId), floor.imageFile)

    fun listProjects(): List<Project> = root.listFiles()
        ?.filter { it.isDirectory && File(it, "project.json").exists() }
        ?.mapNotNull { runCatching { loadMeta(it.name) }.getOrNull() }
        ?.sortedByDescending { it.modifiedAt }
        ?: emptyList()

    private fun loadMeta(projectId: String): Project {
        val f = File(projectDir(projectId), "project.json")
        return Project.fromJson(JSONObject(f.readText()))
    }

    /** Loads project with all its surveys. */
    fun load(projectId: String): Project {
        val p = loadMeta(projectId)
        val dir = surveysDir(projectId)
        dir.listFiles()?.filter { it.name.endsWith(".json") }?.sortedBy { it.name }?.forEach { f ->
            runCatching { Survey.fromJson(JSONObject(f.readText())) }.getOrNull()?.let { p.surveys.add(it) }
        }
        p.surveys.sortBy { it.startedAt }
        return p
    }

    fun save(p: Project) {
        p.modifiedAt = System.currentTimeMillis()
        val dir = projectDir(p.id)
        writeAtomic(File(dir, "project.json"), p.toJson().toString())
        val sdir = surveysDir(p.id)
        val keep = p.surveys.map { "${it.id}.json" }.toSet()
        sdir.listFiles()?.forEach { if (it.name !in keep) it.delete() }
        p.surveys.forEach { saveSurvey(p, it) }
        val keepImg = p.floors.map { it.imageFile }.toSet()
        floorsDir(p.id).listFiles()?.forEach { if (it.name !in keepImg) it.delete() }
    }

    fun saveSurvey(p: Project, s: Survey) {
        writeAtomic(File(surveysDir(p.id), "${s.id}.json"), s.toJson().toString())
    }

    fun delete(projectId: String) {
        projectDir(projectId).deleteRecursively()
    }

    /** Stores a floor image (re-encoded as JPEG, downscaled to at most maxSide px) and returns the Floor. */
    fun addFloor(p: Project, name: String, bitmap: Bitmap, maxSide: Int = 4096): Floor {
        var bmp = bitmap
        val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
        if (scale < 1f) {
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        }
        val floor = Floor(name = name, imageFile = "", widthPx = bmp.width, heightPx = bmp.height)
        floor.imageFile = "${floor.id}.jpg"
        FileOutputStream(floorImageFile(p.id, floor)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        p.floors.add(floor)
        return floor
    }

    fun loadFloorBitmap(p: Project, floor: Floor, maxSide: Int = 0): Bitmap? {
        val f = floorImageFile(p.id, floor)
        if (!f.exists()) return null
        val opts = BitmapFactory.Options()
        if (maxSide > 0) {
            opts.inJustDecodeBounds = true
            BitmapFactory.decodeFile(f.absolutePath, opts)
            var sample = 1
            while (maxOf(opts.outWidth, opts.outHeight) / sample > maxSide) sample *= 2
            opts.inJustDecodeBounds = false
            opts.inSampleSize = sample
        }
        return BitmapFactory.decodeFile(f.absolutePath, opts)
    }

    /** Full export of a project (metadata + surveys) as a single JSON document. */
    fun exportJson(p: Project): JSONObject {
        val o = p.toJson()
        val arr = JSONArray()
        p.surveys.forEach { arr.put(it.toJson()) }
        o.put("surveys", arr)
        return o
    }

    private fun writeAtomic(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }
}
