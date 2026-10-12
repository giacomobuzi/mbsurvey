package it.buzi.wifisurvey.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import it.buzi.wifisurvey.R
import it.buzi.wifisurvey.data.ProjectStore
import it.buzi.wifisurvey.databinding.ActivityPlanEditorBinding
import it.buzi.wifisurvey.databinding.DialogPlanSizeBinding
import it.buzi.wifisurvey.model.Floor
import it.buzi.wifisurvey.model.PlanDrawing
import it.buzi.wifisurvey.model.Project
import it.buzi.wifisurvey.model.Room
import it.buzi.wifisurvey.plan.PlanRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileOutputStream

/**
 * Draws a floor plan from scratch (rooms, walls, doors) or edits one drawn earlier.
 * On save the drawing is rasterised into the floor image and the floor is calibrated automatically.
 */
class PlanEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROJECT = "projectId"
        const val EXTRA_FLOOR = "floorId"   // optional: edit an existing drawn floor
    }

    private lateinit var b: ActivityPlanEditorBinding
    private lateinit var store: ProjectStore
    private lateinit var project: Project
    private var floor: Floor? = null
    private val undo = ArrayDeque<PlanDrawing>()
    private var dirty = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlanEditorBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.pe_title)
        store = ProjectStore(this)
        val pid = intent.getStringExtra(EXTRA_PROJECT) ?: run { finish(); return }
        project = runCatching { store.load(pid) }.getOrElse { finish(); return }
        floor = intent.getStringExtra(EXTRA_FLOOR)?.let { project.floor(it) }
        supportActionBar?.subtitle = floor?.name ?: project.name

        b.editor.plan = floor?.drawing?.copy() ?: PlanDrawing()
        b.editor.onBeforeChange = { snapshot() }
        b.editor.onChangeCancelled = { undo.removeLastOrNull() }
        b.editor.onChanged = { dirty = true }
        b.editor.onRoomCreated = { r -> askRoomName(r) }
        b.editor.onRoomTapped = { r -> askRoomName(r) }
        b.editor.onSelectionChanged = { id -> b.renameBtn.isEnabled = b.editor.selectedRoom() != null; b.deleteBtn.isEnabled = id != null }

        b.toolGroup.check(b.toolSelect.id)
        b.toolGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            b.editor.tool = when (id) {
                b.toolRoom.id -> PlanEditorView.Tool.ROOM
                b.toolWall.id -> PlanEditorView.Tool.WALL
                b.toolDoor.id -> PlanEditorView.Tool.DOOR
                else -> PlanEditorView.Tool.SELECT
            }
            b.hint.text = when (b.editor.tool) {
                PlanEditorView.Tool.ROOM -> getString(R.string.pe_hint_room)
                PlanEditorView.Tool.WALL -> getString(R.string.pe_hint_wall)
                PlanEditorView.Tool.DOOR -> getString(R.string.pe_hint_door)
                PlanEditorView.Tool.SELECT -> getString(R.string.pe_hint_select)
            }
        }
        b.hint.text = getString(R.string.pe_hint_select)
        b.sizeBtn.setOnClickListener { askSize() }
        b.renameBtn.setOnClickListener { b.editor.selectedRoom()?.let { askRoomName(it) } }
        b.deleteBtn.setOnClickListener { b.editor.deleteSelected() }
        b.undoBtn.setOnClickListener { undoLast() }
        b.renameBtn.isEnabled = false
        b.deleteBtn.isEnabled = false

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { confirmExit() }
        })

        if (floor == null) askSize(first = true)
    }

    private fun snapshot() {
        undo.addLast(b.editor.plan.copy())
        if (undo.size > 30) undo.removeFirst()
    }

    private fun undoLast() {
        val prev = undo.removeLastOrNull() ?: return
        b.editor.plan = prev
        dirty = true
    }

    private fun askSize(first: Boolean = false) {
        val d = DialogPlanSizeBinding.inflate(layoutInflater)
        d.widthM.setText(b.editor.plan.widthM.toString())
        d.heightM.setText(b.editor.plan.heightM.toString())
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pe_size)
            .setView(d.root)
            .setCancelable(!first)
            .setPositiveButton(R.string.ok) { _, _ ->
                val w = d.widthM.text.toString().replace(',', '.').toDoubleOrNull()
                val h = d.heightM.text.toString().replace(',', '.').toDoubleOrNull()
                if (w != null && h != null && w in 2.0..500.0 && h in 2.0..500.0) {
                    snapshot()
                    b.editor.plan.widthM = w; b.editor.plan.heightM = h
                    b.editor.fitToScreen()
                    dirty = true
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun askRoomName(r: Room) {
        Dialogs.textInput(this, getString(R.string.pe_room_name), getString(R.string.pe_room_name_hint), r.name) { n ->
            r.name = n
            dirty = true
            b.editor.invalidate()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, R.string.pe_save).setIcon(R.drawable.ic_save).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> confirmExit()
            1 -> save()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun confirmExit() {
        if (!dirty) { finish(); return }
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.pe_discard)
            .setPositiveButton(R.string.pe_save) { _, _ -> save() }
            .setNegativeButton(R.string.delete) { _, _ -> finish() }
            .setNeutralButton(R.string.cancel, null)
            .show()
    }

    private fun save() {
        val plan = b.editor.plan
        if (plan.rooms.isEmpty() && plan.walls.isEmpty()) {
            Toast.makeText(this, R.string.pe_empty, Toast.LENGTH_LONG).show(); return
        }
        val existing = floor
        if (existing == null) {
            Dialogs.textInput(this, getString(R.string.pe_floor_name), getString(R.string.floor_name_hint), "Piano ${project.floors.size + 1}") { n ->
                persist(null, n.ifBlank { "Piano" }, plan)
            }
        } else persist(existing, existing.name, plan)
    }

    private fun persist(existing: Floor?, name: String, plan: PlanDrawing) {
        lifecycleScope.launch {
            withContext(Dispatchers.Default) {
                val bmp = PlanRenderer.toBitmap(plan)
                val ppm = PlanDrawing.pxPerMeter(plan.widthM, plan.heightM)
                val f = if (existing == null) {
                    store.addFloor(project, name, bmp, maxSide = 4096)
                } else {
                    FileOutputStream(store.floorImageFile(project.id, existing)).use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }
                    existing.widthPx = bmp.width; existing.heightPx = bmp.height
                    existing
                }
                f.drawing = plan
                f.metersPerPx = 1.0 / ppm
                f.calibration = null
                store.save(project)
            }
            dirty = false
            Toast.makeText(this@PlanEditorActivity, R.string.pe_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
