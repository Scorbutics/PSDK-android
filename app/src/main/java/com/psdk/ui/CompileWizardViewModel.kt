package com.psdk.ui

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.psdk.ruby.vm.ArchiveKeys

class CompileWizardViewModel : ViewModel() {
    // Encrypted archive path + derived keys. Set after the import step
    // validates the .epsa header and the JVM-side KDF resolves K_enc/K_mac.
    val archive = MutableLiveData<ArchiveKeys?>()
    val executionLocation = MutableLiveData<String>()
    val currentStep = MutableLiveData(0)
    val compilationSuccess = MutableLiveData<Boolean?>()

    // Export-specific
    val apkFolderLocation = MutableLiveData<String?>()

    fun isExportMode(): Boolean = apkFolderLocation.value != null
}
