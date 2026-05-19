package com.psdk.ui

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.psdk.R
import com.psdk.compilation.ArchiveValidator
import com.psdk.db.AppDatabase
import com.psdk.db.entities.Project
import com.psdk.zip.EpsaArchive
import java.io.File
import java.util.UUID

class ProjectListFragment : Fragment() {

    // Scoped to the parent (ProjectHubFragment) so root-directory state set
    // by the hub's storage menu is visible to the list.
    private val viewModel: ProjectHubViewModel by viewModels({ requireParentFragment() })
    private lateinit var adapter: ProjectListAdapter
    private lateinit var database: AppDatabase

    // Constantly-running breath animator on the centred "+". Kept as a
    // field so onDestroyView can cancel it — otherwise it holds a view
    // reference past the view's lifetime.
    private var fabBreath: ObjectAnimator? = null

    private val chooseArchiveLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        importArchive(uri)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_project_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        database = AppDatabase.getInstance(requireContext())

        val recyclerView = view.findViewById<RecyclerView>(R.id.projectsRecyclerView)
        val emptyState = view.findViewById<LinearLayout>(R.id.emptyState)
        val newProjectFab = view.findViewById<FloatingActionButton>(R.id.newProjectFab)

        adapter = ProjectListAdapter(
            onProjectClick = { project -> launchCompileWizard(project, autoStart = false) },
            onDeleteClick = { project -> showDeleteDialog(project) }
        )

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        setupCentralAddButton(newProjectFab)

        // Observe projects
        viewModel.projects.observe(viewLifecycleOwner) { projects ->
            adapter.submitList(projects)
            emptyState.visibility = if (projects.isEmpty()) View.VISIBLE else View.GONE
            recyclerView.visibility = if (projects.isEmpty()) View.GONE else View.VISIBLE
            // The "+" sits in the middle of the screen and would otherwise
            // overlap row 3+. Hiding at the cap doubles as the enforcement
            // mechanism for [MAX_PROJECTS] — this is a debug tool, not a
            // general-purpose project hub.
            newProjectFab.visibility = if (projects.size >= MAX_PROJECTS) View.GONE else View.VISIBLE
        }

        // Root directory is owned by the hub fragment; refresh the project
        // list whenever it changes.
        viewModel.rootDirectory.observe(viewLifecycleOwner) { refreshProjects() }
    }

    override fun onResume() {
        super.onResume()
        refreshProjects()
    }

    private fun refreshProjects() {
        val rootPath = viewModel.rootDirectory.value?.path ?: return
        viewModel.projects.value = database.projectDao().getAllByDirectory(rootPath)
    }

    /**
     * Wire up the centred "+" FAB:
     *   - constant gentle "breath" scale animation (the halo behind it is
     *     drawn by loader.rb, see build_icon's @icon_glow path).
     *   - touch-down compresses to 0.9 + release bounces back via
     *     OvershootInterpolator, so the button feels springy.
     *   - click triggers the file picker; on result, we stage the archive,
     *     create a project named after the filename, and launch the
     *     compile wizard in auto-start mode.
     */
    private fun setupCentralAddButton(fab: FloatingActionButton) {
        fabBreath = ObjectAnimator.ofPropertyValuesHolder(
            fab,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.05f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.05f),
        ).apply {
            duration = 1400L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        // Press response — drop scale while finger is down, OvershootInterpolator
        // snap back on release. We don't pause the breath animator: when the
        // press release animation completes the breath resumes from wherever
        // it had progressed to.
        fab.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90L).start()
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    v.animate()
                        .scaleX(1f).scaleY(1f)
                        .setInterpolator(OvershootInterpolator(3f))
                        .setDuration(360L)
                        .start()
                }
            }
            false  // Don't consume — the FAB's onClickListener still fires on UP.
        }
        fab.setOnClickListener { showArchivePicker() }
    }

    override fun onDestroyView() {
        fabBreath?.cancel()
        fabBreath = null
        super.onDestroyView()
    }

    private fun launchCompileWizard(project: Project, autoStart: Boolean) {
        val fragment = CompileWizardFragment.newInstance(
            executionLocation = project.directory,
            classicBootstrap = false,
            autoStart = autoStart,
        )
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun showArchivePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            chooseArchiveLauncher.launch(Intent.createChooser(intent, "Choose archive"))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "No file picker available", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Stage the picked archive into a fresh project directory and hand the
     * wizard control. Runs off the UI thread because copying a .epsa can
     * be tens of MB.
     *
     * Project name comes straight from the picked filename minus its
     * extension ("Lethalmonv1.epsa" → "Lethalmonv1"). If the name
     * collides with an existing project dir we append "-2", "-3", …
     * rather than asking the user — the goal is zero-friction import.
     */
    private fun importArchive(uri: Uri) {
        // Defensive: the FAB is hidden at the cap so the picker shouldn't
        // be reachable, but a stale launcher result could land after the
        // count crossed the limit (e.g. delete happened mid-pick).
        if ((viewModel.projects.value?.size ?: 0) >= MAX_PROJECTS) {
            Toast.makeText(
                requireContext(),
                "Up to $MAX_PROJECTS projects — delete one first.",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        val rootDir = viewModel.readableRootDirectory.value ?: return
        val rootPath = viewModel.rootDirectory.value?.path ?: return
        val displayName = queryDisplayName(uri) ?: "Project"
        val baseName = displayName.substringBeforeLast('.').ifBlank { displayName }
            .ifBlank { "Project" }
        val ctx = requireContext().applicationContext

        Thread {
            var dir: File? = null
            try {
                // De-dupe against existing dirs on disk.
                var projectName = baseName
                var candidate = File("$rootDir/projects/$projectName")
                var attempt = 1
                while (candidate.exists()) {
                    attempt++
                    projectName = "$baseName-$attempt"
                    candidate = File("$rootDir/projects/$projectName")
                }
                candidate.mkdirs()
                dir = candidate

                val staging = ArchiveValidator.getStagingFile(candidate.path)
                staging.parentFile?.mkdirs()
                val opened = ctx.contentResolver.openInputStream(uri)
                    ?: error("could not open archive")
                opened.use { input ->
                    staging.outputStream().use { out -> input.copyTo(out) }
                }

                if (!EpsaArchive.isEpsaFile(staging)) {
                    throw IllegalArgumentException("Selected file is not an .epsa archive")
                }

                val project = Project(projectName, candidate.path, rootPath, UUID.randomUUID())
                database.projectDao().insertAll(project)

                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    refreshProjects()
                    launchCompileWizard(project, autoStart = true)
                }
            } catch (e: Exception) {
                dir?.deleteRecursively()
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    Toast.makeText(
                        requireContext(),
                        "Import failed: ${e.localizedMessage ?: e.javaClass.simpleName}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }.start()
    }

    private fun queryDisplayName(uri: Uri): String? {
        val cursor = requireContext().contentResolver.query(uri, null, null, null, null) ?: return null
        cursor.use {
            val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && it.moveToFirst()) return it.getString(idx)
        }
        return null
    }

    companion object {
        /**
         * This screen is a debug harness, not a general-purpose project
         * hub — capping at 3 keeps the centred "+" FAB from overlapping
         * scrolling rows and signals intent.
         */
        private const val MAX_PROJECTS = 3
    }

    private fun showDeleteDialog(project: Project) {
        MaterialAlertDialogBuilder(requireContext(), R.style.Theme_Psdk_Dialog)
            .setTitle("Delete Project")
            .setMessage("Are you sure you want to remove '${project.name}'?")
            .setPositiveButton("Delete") { _, _ ->
                database.projectDao().delete(project)
                val dir = File(project.directory)
                if (dir.canRead()) dir.deleteRecursively()
                refreshProjects()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
