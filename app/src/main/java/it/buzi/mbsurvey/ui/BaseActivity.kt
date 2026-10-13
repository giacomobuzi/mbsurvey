package it.buzi.mbsurvey.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import it.buzi.mbsurvey.R

/** Adds slide transitions between screens. */
open class BaseActivity : AppCompatActivity() {
    override fun startActivity(intent: Intent?, options: Bundle?) {
        super.startActivity(intent, options)
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }
}
