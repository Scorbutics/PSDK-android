package com.psdk.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.psdk.AppInstall
import com.psdk.GameLauncher
import com.psdk.PathUtil
import com.psdk.R
import com.scorbutics.rubyvm.RubyVMPaths

/**
 * Fragment incarnation of the (now-deleted) ProjectHubActivity. Hosts
 * [ProjectListFragment]; tapping a project pushes a [CompileWizardFragment]
 * onto the host activity's stack. The toolbar lives inside this fragment's
 * layout — the parent [RootActivity] is intentionally chrome-less — and
 * carries the only entry point for switching root storage location.
 */
class ProjectHubFragment : Fragment() {

    private val viewModel: ProjectHubViewModel by viewModels()

    private val chooseRootDirectoryResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                loadDirectory(uri)
                saveDirectoryPersistent(uri)
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_project_hub, container, false)
    }

    override fun onResume() {
        super.onResume()
        // Hub has no project context — declare default state so any
        // project assets a prior wizard session swapped in get rolled
        // back to PSDK defaults. Idempotent at the activity level: a
        // second call while already in Default is a no-op.
        (requireActivity() as LoaderHost).setLoaderState(LoaderState.Default)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val activity = requireActivity()

        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.inflateMenu(R.menu.project_hub_menu)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_storage_location -> {
                    showStorageLocationDialog()
                    true
                }
                else -> false
            }
        }

        // System back press routes through the activity's
        // OnBackPressedDispatcher, which only pops the activity-level
        // FragmentManager by default. The hub's inner stack lives in
        // childFragmentManager, so we install our own callback to pop it
        // when it has entries.
        val popChildOnBack = object : OnBackPressedCallback(childFragmentManager.backStackEntryCount > 0) {
            override fun handleOnBackPressed() {
                childFragmentManager.popBackStack()
            }
        }
        activity.onBackPressedDispatcher.addCallback(viewLifecycleOwner, popChildOnBack)
        childFragmentManager.addOnBackStackChangedListener {
            popChildOnBack.isEnabled = childFragmentManager.backStackEntryCount > 0
        }

        // Initialize Ruby VM paths in background
        Thread {
            try {
                RubyVMPaths.getDefaultPaths(activity)
            } catch (e: Exception) {
                activity.runOnUiThread {
                    Toast.makeText(
                        activity.applicationContext,
                        "Unable to initialize Ruby VM: ${e.localizedMessage}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }

            activity.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                val releaseLocation = "${activity.applicationInfo.dataDir}/Release"
                val shouldAutoStart = AppInstall.unpackToStartGameIfRelease(activity, "Release", releaseLocation)
                if (shouldAutoStart) {
                    // Drop the splash interpreter before GameActivity spawns
                    // its own — both share g_global_vm. See
                    // LoaderHost.shutdownLoader for the rationale.
                    (activity as? LoaderHost)?.shutdownLoader()
                    GameLauncher.launchDirect(activity, activity.applicationInfo.dataDir)
                }
            }
        }.start()

        // Initial-add only — on recreate, the FragmentManager has restored
        // the back stack and the VM already carries the loaded directory.
        if (savedInstanceState == null) {
            loadSavedDirectory()
            childFragmentManager.beginTransaction()
                .replace(R.id.hubContainer, ProjectListFragment())
                .commit()
        }
    }

    private fun loadSavedDirectory() {
        val prefs = requireContext().getSharedPreferences("PSDK-PROJECT", Activity.MODE_PRIVATE)
        val isInternal = prefs.getBoolean(ROOT_PROJECT_INTERNAL, false)
        val baseUri = if (isInternal) null else requireContext().contentResolver.persistedUriPermissions.firstOrNull()?.uri
        loadDirectory(baseUri)
    }

    private fun loadDirectory(uri: Uri?) {
        if (uri == null) {
            viewModel.isInternal.value = true
            viewModel.rootDirectory.value = requireContext().filesDir.toUri()
            viewModel.readableRootDirectory.value = PathUtil(requireContext()).getPathFromUri(viewModel.rootDirectory.value!!)
        } else {
            viewModel.isInternal.value = false
            viewModel.rootDirectory.value = uri
            val realUri = DocumentsContract.buildDocumentUriUsingTree(
                uri,
                DocumentsContract.getTreeDocumentId(uri)
            )
            viewModel.readableRootDirectory.value = PathUtil(requireContext()).getPathFromUri(realUri)
        }
    }

    private fun saveDirectoryPersistent(uri: Uri) {
        val prefs = requireContext().getSharedPreferences("PSDK-PROJECT", Activity.MODE_PRIVATE)
        prefs.edit().remove(ROOT_PROJECT_INTERNAL).apply()
        val existing = requireContext().contentResolver.persistedUriPermissions.firstOrNull()?.uri
        if (existing != null) {
            requireContext().contentResolver.releasePersistableUriPermission(existing, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        requireContext().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun showStorageLocationDialog() {
        MaterialAlertDialogBuilder(requireContext(), R.style.Theme_Psdk_Dialog)
            .setTitle("Storage Location")
            .setSingleChoiceItems(
                arrayOf("Internal storage", "Custom location"),
                if (viewModel.isInternal.value == true) 0 else 1
            ) { dialog, which ->
                when (which) {
                    0 -> {
                        loadDirectory(null)
                        val prefs = requireContext().getSharedPreferences("PSDK-PROJECT", Activity.MODE_PRIVATE)
                        prefs.edit().putBoolean(ROOT_PROJECT_INTERNAL, true).apply()
                        dialog.dismiss()
                    }
                    1 -> {
                        dialog.dismiss()
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                        }
                        chooseRootDirectoryResult.launch(intent)
                    }
                }
            }
            .show()
    }

    companion object {
        private const val ROOT_PROJECT_INTERNAL = "ROOT_PROJECT_INTERNAL"
    }
}
