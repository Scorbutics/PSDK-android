package com.psdk

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

class ReadLogDetailsActivity: ComponentActivity()  {

    private var m_gameOutLogFileLocation: String? = null
    private var m_gameErrorLogFileLocation: String? = null

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.readlogdetails)
        m_gameOutLogFileLocation = intent.getStringExtra("GAME_LOG_FILE_LOCATION")
        m_gameErrorLogFileLocation = intent.getStringExtra("GAME_ERROR_LOG_FILE_LOCATION")
        loadScreen()
    }

    private fun loadScreen() {
        val lastEngineDebugLogs = findViewById<View>(R.id.lastOutLogs) as TextView
        val lastStdoutLog = Paths.get(m_gameOutLogFileLocation)
        if (Files.exists(lastStdoutLog)) {
            try {
                lastEngineDebugLogs.text = String(Files.readAllBytes(lastStdoutLog), StandardCharsets.UTF_8)
            } catch (exception: Exception) {
                lastEngineDebugLogs.text = "Unable to read last stdout log: " + exception.localizedMessage
            }
        } else {
            lastEngineDebugLogs.text = "No log"
        }
        val lastErrorLog = findViewById<View>(R.id.projectLastError) as TextView
        try {
            val encoded = Files.readAllBytes(Paths.get(m_gameErrorLogFileLocation))
            lastErrorLog.text = String(encoded, StandardCharsets.UTF_8)
        } catch (e: IOException) {
            // File does not exist
            lastErrorLog.text = "No log"
        }
    }
}