package com.psdk.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.psdk.AppInstall
import com.psdk.GameLauncher
import com.psdk.R
import com.psdk.ruby.vm.PsdkInterpreter
import com.scorbutics.rubyvm.RubyVMPaths

class ProjectHubActivity : AppCompatActivity() {

    private val viewModel: ProjectHubViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_project_hub)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = "PSDK"

        // Initialize Ruby VM paths in background
        Thread {
            try {
                RubyVMPaths.getDefaultPaths(this@ProjectHubActivity)
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(applicationContext, "Unable to initialize Ruby VM: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }

            runOnUiThread {
                val releaseLocation = "${applicationInfo.dataDir}/Release"
                val shouldAutoStart = AppInstall.unpackToStartGameIfRelease(this@ProjectHubActivity, "Release", releaseLocation)
                if (shouldAutoStart) {
                    GameLauncher.launchDirect(this@ProjectHubActivity, applicationInfo.dataDir)
                }
            }
        }.start()

        // Show project list fragment
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.hubContainer, ProjectListFragment())
                .commit()
        }
    }
}
