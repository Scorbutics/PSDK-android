package com.psdk.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.psdk.R
import com.psdk.ui.wizard.CompileDoneFragment
import com.psdk.ui.wizard.CompileProgressFragment
import com.psdk.ui.wizard.ExportApkFragment
import com.psdk.ui.wizard.ImportArchiveFragment

class CompileWizardActivity : AppCompatActivity() {

    private val viewModel: CompileWizardViewModel by viewModels()

    private lateinit var stepIndicators: List<TextView>
    private lateinit var connectors: List<View>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_compile_wizard)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener {
            // Block back navigation once compilation has started (VM is dirty)
            val step = viewModel.currentStep.value ?: 0
            if (step < 1) {
                onBackPressedDispatcher.onBackPressed()
            }
        }

        // Initialize step indicators
        stepIndicators = listOf(
            findViewById(R.id.stepIndicator1),
            findViewById(R.id.stepIndicator2),
            findViewById(R.id.stepIndicator3)
        )
        connectors = listOf(
            findViewById(R.id.connector12),
            findViewById(R.id.connector23)
        )

        // Get intent extras
        val executionLocation = intent.getStringExtra(EXTRA_EXECUTION_LOCATION) ?: return finish()
        viewModel.executionLocation.value = executionLocation
        intent.getStringExtra(EXTRA_APK_FOLDER_LOCATION)?.let {
            viewModel.apkFolderLocation.value = it
        }

        // Block system back button once compilation starts (VM is dirty)
        val backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                // Swallow the back press — restart is the only way out
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        // Observe step changes
        viewModel.currentStep.observe(this) { step ->
            updateStepIndicators(step)
            navigateToStep(step)
            // Hide back arrow and block back button once compilation starts
            val locked = step >= 1
            toolbar.navigationIcon = if (locked) null else getDrawable(R.drawable.ic_arrow_back)
            backCallback.isEnabled = locked
        }

        // If export mode, go directly to export fragment
        if (viewModel.isExportMode()) {
            toolbar.title = "Export"
            findViewById<View>(R.id.stepIndicator).visibility = View.GONE
            navigateToFragment(ExportApkFragment())
        } else {
            // Start at step 0 if not already set
            if (savedInstanceState == null) {
                viewModel.currentStep.value = 0
            }
        }
    }

    private fun navigateToStep(step: Int) {
        val fragment = when (step) {
            0 -> ImportArchiveFragment()
            1 -> CompileProgressFragment()
            2 -> CompileDoneFragment()
            else -> return
        }
        navigateToFragment(fragment)
    }

    private fun navigateToFragment(fragment: androidx.fragment.app.Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.wizardContainer, fragment)
            .commit()
    }

    private fun updateStepIndicators(currentStep: Int) {
        for (i in stepIndicators.indices) {
            when {
                i < currentStep -> {
                    // Completed
                    stepIndicators[i].setBackgroundResource(R.drawable.bg_step_completed)
                    stepIndicators[i].text = "\u2713"
                    stepIndicators[i].setTextColor(getColor(R.color.white))
                }
                i == currentStep -> {
                    // Active
                    stepIndicators[i].setBackgroundResource(R.drawable.bg_step_active)
                    stepIndicators[i].text = (i + 1).toString()
                    stepIndicators[i].setTextColor(getColor(R.color.colorOnPrimary))
                }
                else -> {
                    // Pending
                    stepIndicators[i].setBackgroundResource(R.drawable.bg_step_pending)
                    stepIndicators[i].text = (i + 1).toString()
                    stepIndicators[i].setTextColor(getColor(R.color.colorOnSurfaceVariant))
                }
            }
        }

        for (i in connectors.indices) {
            connectors[i].setBackgroundResource(
                if (i < currentStep) R.drawable.bg_step_connector_active
                else R.drawable.bg_step_connector
            )
        }
    }

    companion object {
        const val EXTRA_EXECUTION_LOCATION = "EXECUTION_LOCATION"
        const val EXTRA_APK_FOLDER_LOCATION = "APK_FOLDER_LOCATION"
    }
}
