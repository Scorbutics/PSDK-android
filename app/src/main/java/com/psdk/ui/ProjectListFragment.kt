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
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class ProjectListFragment : Fragment() {

    // Scoped to the parent (ProjectHubFragment) so root-directory state set
    // by the hub's storage menu is visible to the list.
    private val viewModel: ProjectHubViewModel by viewModels({ requireParentFragment() })
    private lateinit var adapter: ProjectListAdapter
    private lateinit var database: AppDatabase

    // Constantly-running breath animators on the centred "+" stack.
    // Kept as fields so onDestroyView can cancel them — otherwise they
    // hold view references past the view's lifetime.
    private var fabBreath: ObjectAnimator? = null
    private var haloBreath: ObjectAnimator? = null

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
        val newProjectHalo = view.findViewById<View>(R.id.newProjectHalo)
        val fabContainer = view.findViewById<FrameLayout>(R.id.fabContainer)

        // Delete moved to the wizard's toolbar overflow menu. The adapter
        // no longer needs a delete callback.
        adapter = ProjectListAdapter(
            onProjectClick = { project -> launchCompileWizard(project, autoStart = false) },
        )

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        setupCentralAddButton(newProjectFab, newProjectHalo, fabContainer)

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
     * Wire up the centred "+" stack:
     *   - FAB itself: gentle scale "breath" (1.0↔1.05).
     *   - Halo behind it: alpha breathes 0.5↔0.85 on a slower cycle so the
     *     glow feels like it's pulsing the FAB outward, not in lock-step.
     *   - Touch-down compresses scale + release snaps back via
     *     OvershootInterpolator; release also spawns a particle burst
     *     outward from the FAB centre (see [spawnFabParticles]) — same
     *     vocabulary as loader.rb's spawn_bang.
     *   - Click triggers the file picker.
     */
    private fun setupCentralAddButton(
        fab: FloatingActionButton,
        halo: View,
        container: FrameLayout,
    ) {
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

        haloBreath = ObjectAnimator.ofFloat(halo, View.ALPHA, 0.5f, 0.85f).apply {
            duration = 2400L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        // Press response — scale dip on down, OvershootInterpolator snap
        // back + particle burst on release. We don't pause the breath
        // animators: when the press-release animation completes the breath
        // resumes from wherever it had progressed to.
        fab.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90L).start()
                }
                MotionEvent.ACTION_UP -> {
                    v.animate()
                        .scaleX(1f).scaleY(1f)
                        .setInterpolator(OvershootInterpolator(3f))
                        .setDuration(360L)
                        .start()
                    spawnFabParticles(container, fab)
                }
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

    /**
     * Burst of [PARTICLE_COUNT] dots from the FAB centre — same beat as
     * loader.rb's spawn_bang. Each particle is a tiny View added to
     * [container], animated outward at a random angle, then removed.
     * Cheap (one View per particle, removed on end) and works without
     * any custom drawing surface.
     */
    private fun spawnFabParticles(container: FrameLayout, fab: View) {
        val ctx = container.context
        val density = ctx.resources.displayMetrics.density
        val fabCx = fab.x + fab.width / 2f
        val fabCy = fab.y + fab.height / 2f
        val baseRadius = fab.width / 2f
        repeat(PARTICLE_COUNT) { i ->
            val particle = View(ctx).apply {
                background = ContextCompat.getDrawable(ctx, R.drawable.bg_fab_particle)
                val sizePx = (PARTICLE_SIZE_DP * density).toInt()
                layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
                // Random angle around the FAB; start just outside the
                // FAB's edge so particles look ejected, not warped.
                val angle = (i.toDouble() / PARTICLE_COUNT) * Math.PI * 2 +
                    Random.nextDouble(-0.2, 0.2)
                val travel = (PARTICLE_TRAVEL_DP * density) *
                    Random.nextDouble(0.7, 1.0).toFloat()
                val startX = fabCx + (baseRadius * cos(angle)).toFloat() - sizePx / 2f
                val startY = fabCy + (baseRadius * sin(angle)).toFloat() - sizePx / 2f
                x = startX
                y = startY
                alpha = 1f
                animate()
                    .translationXBy((travel * cos(angle)).toFloat())
                    .translationYBy((travel * sin(angle)).toFloat())
                    .alpha(0f)
                    .scaleX(0.4f)
                    .scaleY(0.4f)
                    .setDuration(PARTICLE_DURATION_MS)
                    .setInterpolator(DecelerateInterpolator(1.6f))
                    .withEndAction {
                        (parent as? ViewGroup)?.removeView(this)
                    }
                    .start()
            }
            container.addView(particle)
        }
    }

    override fun onDestroyView() {
        fabBreath?.cancel()
        fabBreath = null
        haloBreath?.cancel()
        haloBreath = null
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

        private const val PARTICLE_COUNT = 12
        private const val PARTICLE_SIZE_DP = 8
        private const val PARTICLE_TRAVEL_DP = 64
        private const val PARTICLE_DURATION_MS = 520L
    }
}
