package it.buzi.wifisurvey.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import it.buzi.wifisurvey.R

object Dialogs {
    fun textInput(
        ctx: Context, title: String, hint: String, initial: String = "",
        inputType: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
        onOk: (String) -> Unit
    ) {
        val edit = EditText(ctx).apply {
            this.hint = hint
            setText(initial)
            this.inputType = inputType
            setSelection(text.length)
        }
        val container = FrameLayout(ctx).apply {
            val pad = (20 * ctx.resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(edit)
        }
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(container)
            .setPositiveButton(R.string.ok) { _, _ -> onOk(edit.text.toString().trim()) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun confirm(ctx: Context, message: String, onYes: () -> Unit) {
        AlertDialog.Builder(ctx)
            .setMessage(message)
            .setPositiveButton(R.string.ok) { _, _ -> onYes() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun info(ctx: Context, title: String, message: String) {
        AlertDialog.Builder(ctx).setTitle(title).setMessage(message).setPositiveButton(R.string.ok, null).show()
    }

    fun throttleHelp(ctx: Context) {
        AlertDialog.Builder(ctx)
            .setTitle(R.string.throttle_help_title)
            .setMessage(R.string.throttle_help)
            .setPositiveButton(R.string.open_dev_settings) { _, _ ->
                runCatching {
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure {
                    runCatching { ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
            .setNegativeButton(R.string.ok, null)
            .show()
    }
}
