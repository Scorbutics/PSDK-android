package com.psdk.ui

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class CompileWizardViewModel : ViewModel() {
    val archivePath = MutableLiveData<String?>()
    val executionLocation = MutableLiveData<String>()
    val currentStep = MutableLiveData(0)

    // Export-specific
    val apkFolderLocation = MutableLiveData<String?>()

    fun isExportMode(): Boolean = apkFolderLocation.value != null
}
