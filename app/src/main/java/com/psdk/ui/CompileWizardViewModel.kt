package com.psdk.ui

import android.content.Context
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.psdk.compilation.CompilationEngine
import com.psdk.ruby.vm.ArchiveKeys

class CompileWizardViewModel : ViewModel() {
    // Encrypted archive path + derived keys. Set after the import step
    // validates the .epsa header and the JVM-side KDF resolves K_enc/K_mac.
    val archive = MutableLiveData<ArchiveKeys?>()
    val executionLocation = MutableLiveData<String>()
    val currentStep = MutableLiveData(0)
    val compilationSuccess = MutableLiveData<Boolean?>()

    // Compilation engine outlives the fragment so an orientation change
    // (or any other view-recreation) doesn't restart compilation. Started
    // once when the user enters the progress step; closed when the
    // ViewModel is cleared (activity finished for good — not on rotation).
    // Callers re-attach via [CompilationEngine.setCallback], which atomically
    // returns a snapshot so a fresh fragment can replay UI state.
    private var engine: CompilationEngine? = null

    /**
     * Returns the running engine, starting it on first call. Returns null
     * if the prerequisite state (archive + executionLocation) isn't set
     * yet — caller should treat that as a programming error.
     *
     * The application context is captured deliberately; we must not hold
     * an activity reference past its lifecycle.
     */
    fun ensureCompilationStarted(context: Context): CompilationEngine? {
        engine?.let { return it }
        val arch = archive.value ?: return null
        val loc = executionLocation.value ?: return null
        val newEngine = CompilationEngine(
            context = context.applicationContext,
            executionLocation = loc,
            archive = arch
        )
        engine = newEngine
        newEngine.start()
        return newEngine
    }

    fun getEngine(): CompilationEngine? = engine

    override fun onCleared() {
        super.onCleared()
        engine?.close()
        engine = null
    }
}
