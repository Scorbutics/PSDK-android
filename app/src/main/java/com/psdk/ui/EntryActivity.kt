package com.psdk.ui

import android.app.NativeActivity
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.psdk.AppModeDetector
import com.psdk.GameLauncher
import com.psdk.ruby.vm.PsdkInterpreter

class EntryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        PsdkInterpreter.initialize()

        val mode = AppModeDetector.detect(this)
        val target = when (mode) {
            AppModeDetector.AppMode.CLASSIC_USER -> {
                val prefs = getSharedPreferences(ClassicSetupActivity.CLASSIC_MODE_PREFS, MODE_PRIVATE)
                val state = prefs.getString(ClassicSetupActivity.CLASSIC_MODE_STATE_KEY, ClassicSetupActivity.STATE_NEEDS_SETUP)

                when (state) {
                    ClassicSetupActivity.STATE_READY -> {
                        // Launch game directly
                        GameLauncher.launchDirect(this, applicationInfo.dataDir)
                        finish()
                        return
                    }
                    ClassicSetupActivity.STATE_SETUP_COMPLETE -> {
                        // Mark as ready for next time, then launch game
                        prefs.edit().putString(ClassicSetupActivity.CLASSIC_MODE_STATE_KEY, ClassicSetupActivity.STATE_READY).apply()
                        GameLauncher.launchDirect(this, applicationInfo.dataDir)
                        finish()
                        return
                    }
                    else -> {
                        Intent(this, ClassicSetupActivity::class.java)
                    }
                }
            }
            AppModeDetector.AppMode.SUPER_USER -> {
                Intent(this, ProjectHubActivity::class.java)
            }
        }

        startActivity(target)
        finish()
    }
}
