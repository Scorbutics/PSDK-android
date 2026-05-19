package com.psdk.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.psdk.R

/**
 * Single-activity host for in-app screens. Owns the [LiteRgssSurfaceView]
 * so the GL surface (and the EGL/native window bound through NativeSurface)
 * survives navigation between fragments.
 *
 * The Ruby splash interpreter that renders into the surface is still owned
 * by [CompileWizardFragment] in this cut — promote to the activity in a
 * later step if we want the splash to persist across the hub.
 *
 * Routed into by [EntryActivity] with one of:
 *   EXTRA_TARGET = TARGET_HUB           → [ProjectHubFragment]
 *   EXTRA_TARGET = TARGET_CLASSIC_WIZARD → [CompileWizardFragment] in
 *                                           classic-bootstrap mode, args
 *                                           taken from EXTRA_* below.
 */
class RootActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_root)

        if (savedInstanceState != null) return

        val target = intent.getStringExtra(EXTRA_TARGET) ?: TARGET_HUB
        val fragment = when (target) {
            TARGET_HUB -> ProjectHubFragment()
            TARGET_CLASSIC_WIZARD -> CompileWizardFragment.newInstance(
                executionLocation = intent.getStringExtra(EXTRA_EXECUTION_LOCATION) ?: return finish(),
                classicBootstrap = true,
            )
            else -> return finish()
        }

        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    companion object {
        const val EXTRA_TARGET = "TARGET"
        const val EXTRA_EXECUTION_LOCATION = "EXECUTION_LOCATION"

        const val TARGET_HUB = "HUB"
        const val TARGET_CLASSIC_WIZARD = "CLASSIC_WIZARD"

        fun hubIntent(activity: AppCompatActivity): Intent =
            Intent(activity, RootActivity::class.java).putExtra(EXTRA_TARGET, TARGET_HUB)

        fun classicWizardIntent(activity: AppCompatActivity, executionLocation: String): Intent =
            Intent(activity, RootActivity::class.java)
                .putExtra(EXTRA_TARGET, TARGET_CLASSIC_WIZARD)
                .putExtra(EXTRA_EXECUTION_LOCATION, executionLocation)
    }
}
