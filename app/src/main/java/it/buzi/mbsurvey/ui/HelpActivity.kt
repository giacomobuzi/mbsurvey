package it.buzi.mbsurvey.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import it.buzi.mbsurvey.R
import it.buzi.mbsurvey.databinding.ActivityHelpBinding

class HelpActivity : AppCompatActivity() {
    companion object { const val EXTRA_LICENSE = "license" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = ActivityHelpBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (intent.getBooleanExtra(EXTRA_LICENSE, false)) {
            supportActionBar?.title = getString(R.string.license_title)
            val lic = runCatching { assets.open("LICENSE.txt").bufferedReader().readText() }.getOrDefault("")
            b.text.typeface = android.graphics.Typeface.MONOSPACE
            b.text.textSize = 12f
            b.text.text = lic
        } else {
            val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
            b.text.text = HtmlCompat.fromHtml(getString(R.string.help_html) + "<br/><br/><small>" + getString(R.string.about_footer) + " · v" + version + "</small>", HtmlCompat.FROM_HTML_MODE_LEGACY)
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
