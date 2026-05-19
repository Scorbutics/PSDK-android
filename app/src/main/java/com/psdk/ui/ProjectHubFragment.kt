package com.psdk.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import com.psdk.AppInstall
import com.psdk.GameLauncher
import com.psdk.R
import com.scorbutics.rubyvm.RubyVMPaths

/**
 * Fragment incarnation of the (now-deleted) ProjectHubActivity. Hosts
 * [ProjectListFragment] (and pushes [ProjectDetailFragment] on top of it
 * via the child fragment manager). The hub's own toolbar lives inside the
 * fragment layout — the parent [RootActivity] is intentionally chrome-less.
 */
class ProjectHubFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_project_hub, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val activity = requireActivity()

        // System back press routes through the activity's
        // OnBackPressedDispatcher, which only pops the activity-level
        // FragmentManager by default. The hub's inner stack (list →
        // detail) lives in childFragmentManager, so we install our own
        // callback to pop it when it has entries.
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
                    GameLauncher.launchDirect(activity, activity.applicationInfo.dataDir)
                }
            }
        }.start()

        // Show project list in the inner container. Initial-add only — on
        // recreate, the FragmentManager has restored the back stack
        // already.
        if (savedInstanceState == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.hubContainer, ProjectListFragment())
                .commit()
        }
    }
}
