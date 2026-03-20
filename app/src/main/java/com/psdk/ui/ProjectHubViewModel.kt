package com.psdk.ui

import android.net.Uri
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.psdk.db.entities.Project

class ProjectHubViewModel : ViewModel() {
    val projects = MutableLiveData<List<Project>>(emptyList())
    val selectedProject = MutableLiveData<Project?>()
    val rootDirectory = MutableLiveData<Uri>()
    val readableRootDirectory = MutableLiveData<String>()
    val isInternal = MutableLiveData(false)
}
