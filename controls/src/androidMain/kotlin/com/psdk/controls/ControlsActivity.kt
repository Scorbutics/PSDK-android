package com.psdk.controls

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import java.io.File

/**
 * The Controls screen of one game: its player's layout file in
 * [EXTRA_EXECUTION_LOCATION]. Every change is saved at once; a running game
 * loads the file again once this closes (the opener's job).
 */
class ControlsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val executionLocation = intent.getStringExtra(EXTRA_EXECUTION_LOCATION) ?: return finish()
        // The screen is dark whatever the system's theme
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        val model = ControlsModel(FileControlsStore(File(executionLocation)), AndroidLabelRenderer())
        setContent { ControlsScreen(model, onClose = ::finish) }
    }

    companion object {
        const val EXTRA_EXECUTION_LOCATION = "EXECUTION_LOCATION"

        fun intent(context: Context, executionLocation: String): Intent =
            Intent(context, ControlsActivity::class.java).putExtra(EXTRA_EXECUTION_LOCATION, executionLocation)
    }
}
