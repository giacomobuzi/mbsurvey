package it.buzi.wifisurvey.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import it.buzi.wifisurvey.R
import it.buzi.wifisurvey.databinding.ActivityHelpBinding

class HelpActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = ActivityHelpBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.text.text = HtmlCompat.fromHtml(getString(R.string.help_html), HtmlCompat.FROM_HTML_MODE_LEGACY)
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
