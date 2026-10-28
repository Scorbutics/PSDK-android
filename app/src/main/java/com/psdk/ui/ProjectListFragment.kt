package com.psdk.ui

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.psdk.R
import com.psdk.compilation.ArchiveValidator
import com.psdk.compilation.CompilationEngine
import com.psdk.db.AppDatabase
import com.psdk.db.entities.Project
import com.psdk.zip.EpsaArchive
import java.io.File
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Constellation layout: 1-3 project "satellites" orbit the centred "+"
 * FAB in a shallow arc. No card chrome, no RecyclerView — the slot count
 * is bounded by [MAX_PROJECTS], so three include'd `satellite_slot.xml`
 * views are enough. Each slot is positioned via translationX/Y in
 * [arrangeSlots]; status is encoded in the halo colour; tapping a slot
 * runs the fly-to-centre transition before launching the wizard.
 */
class ProjectListFragment : Fragment() {

    private val viewModel: ProjectHubViewModel by viewModels({ requireParentFragment() })
    private lateinit var database: AppDatabase

    private lateinit var slots: List<View>
    private lateinit var fabContainer: FrameLayout
    private lateinit var emptyHint: View

    // Breath animators on the centred "+" stack. Kept as fields so
    // onDestroyView can cancel them — otherwise they hold view
    // references past the view's lifetime.
    private var fabBreath: ObjectAnimator? = null
    private var haloBreath: ObjectAnimator? = null
    private var satelliteHaloBreaths: MutableList<ObjectAnimator> = mutableListOf()

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

        val newProjectFab = view.findViewById<FloatingActionButton>(R.id.newProjectFab)
        val newProjectHalo = view.findViewById<View>(R.id.newProjectHalo)
        fabContainer = view.findViewById(R.id.fabContainer)
        emptyHint = view.findViewById(R.id.emptyHint)
        slots = listOf(
            view.findViewById(R.id.satelliteSlot0),
            view.findViewById(R.id.satelliteSlot1),
            view.findViewById(R.id.satelliteSlot2),
        )

        setupCentralAddButton(newProjectFab, newProjectHalo, fabContainer)

        // Observe projects → repaint the constellation.
        viewModel.projects.observe(viewLifecycleOwner) { projects ->
            arrangeSlots(projects)
            // FAB hides at the cap so it can never fly past the satellites
            // and signals "you've maxed your debug-project budget".
            newProjectFab.visibility =
                if (projects.size >= MAX_PROJECTS) View.GONE else View.VISIBLE
            emptyHint.visibility = if (projects.isEmpty()) View.VISIBLE else View.GONE
        }

        // Root directory is owned by the hub fragment; refresh whenever
        // it changes.
        viewModel.rootDirectory.observe(viewLifecycleOwner) { refreshProjects() }
    }

    /**
     * Wizard launch requested while the fragment manager had already saved
     * its state (see [launchCompileWizard]); replayed on the next resume.
     */
    private var pendingWizardProject: Project? = null

    override fun onResume() {
        super.onResume()
        pendingWizardProject?.let { project ->
            android.util.Log.i("ProjectListFragment", "resuming deferred wizard launch for ${project.name}")
            pendingWizardProject = null
            launchCompileWizard(project)
            return
        }
        refreshProjects()
        // Returning from the wizard — restore everything the fly-to-centre
        // transition disturbed.
        fabContainer.alpha = 1f
        slots.forEach { slot ->
            slot.alpha = 1f
            slot.scaleX = 1f
            slot.scaleY = 1f
        }
    }

    private fun refreshProjects() {
        val rootPath = viewModel.rootDirectory.value?.path ?: return
        viewModel.projects.value = database.projectDao().getAllByDirectory(rootPath)
    }

    // ── Constellation ────────────────────────────────────────────────

    /**
     * Place satellites at their resting positions and paint each with the
     * matching project. The mapping rules:
     *   - 1 project  → slot 1 (centre arc).
     *   - 2 projects → slots 0 + 2 (flanking pair).
     *   - 3 projects → all three.
     * Unused slots are GONE so they don't intercept touches.
     */
    private fun arrangeSlots(projects: List<Project>) {
        // Tear down any lingering halo-breath animators before re-binding.
        satelliteHaloBreaths.forEach { it.cancel() }
        satelliteHaloBreaths.clear()

        val density = resources.displayMetrics.density
        val positions = listOf(
            Pair(-SATELLITE_HORIZONTAL_DP * density, -SATELLITE_VERTICAL_DP * density),
            Pair(0f,                                    -SATELLITE_VERTICAL_DP * density),
            Pair(SATELLITE_HORIZONTAL_DP * density,  -SATELLITE_VERTICAL_DP * density),
        )
        val activeIndices = when (projects.size) {
            0 -> emptyList()
            1 -> listOf(1)
            2 -> listOf(0, 2)
            else -> listOf(0, 1, 2)
        }

        // Reset all slots, then populate the active ones.
        slots.forEachIndexed { i, slot ->
            val pos = positions[i]
            slot.translationX = pos.first
            slot.translationY = pos.second
            slot.visibility = View.GONE
            slot.alpha = 0f
            slot.scaleX = 1f
            slot.scaleY = 1f
        }

        activeIndices.forEachIndexed { activeIdx, slotIdx ->
            val project = projects[activeIdx]
            populateSlot(slots[slotIdx], project, slotIdx)
        }
    }

    /**
     * Paint one satellite with a single project's identity:
     *   - icon: project's game.png if extracted, bundled fallback otherwise.
     *   - halo: tinted radial gradient driven by project status (success
     *     green / accent blue / error red). Each halo also gets its own
     *     phase-offset alpha breath so the constellation feels alive.
     *   - name caption: clipped at 96 dp.
     *   - tap: fly-to-centre transition then launch the wizard.
     */
    private fun populateSlot(slot: View, project: Project, slotIdx: Int) {
        val ctx = slot.context
        val icon = slot.findViewById<ImageView>(R.id.satelliteIcon)
        val halo = slot.findViewById<View>(R.id.satelliteHalo)
        val name = slot.findViewById<TextView>(R.id.satelliteName)

        val isReadable = File(project.directory).canRead()
        if (isReadable) CompilationEngine.recoverInterruptedCompilation(project.directory)
        val hasRelease = File(project.directory, "Release").exists()

        name.text = project.name

        // Icon (project game.png, fallback to bundled gear).
        val logoFile = File(project.directory, "loader_assets/game.png")
        val logo = if (isReadable && logoFile.exists()) {
            BitmapFactory.decodeFile(logoFile.absolutePath)
        } else null
        if (logo != null) {
            icon.setImageBitmap(logo)
        } else {
            icon.setImageResource(R.drawable.logo)
        }

        // Halo colour = project status. The drawable is built fresh per
        // slot so the radial gradient picks up the right tint at all
        // three alpha stops.
        val statusColor = ContextCompat.getColor(
            ctx,
            when {
                !isReadable -> R.color.colorError
                hasRelease  -> R.color.colorSuccess
                else        -> R.color.colorSplashAccent
            },
        )
        halo.background = buildHaloDrawable(ctx, statusColor)

        slot.visibility = View.VISIBLE
        // arrangeSlots resets alpha to 0; fade up to the resting opacity
        // so the constellation eases in instead of popping.
        val targetAlpha = if (isReadable) 1f else 0.55f
        slot.animate()
            .alpha(targetAlpha)
            .setDuration(SATELLITE_FADE_IN_MS)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
        // Stay enabled even when unreadable so long-press → delete still
        // works; the click handler guards against launching the wizard.
        slot.isEnabled = true
        slot.setOnClickListener {
            if (isReadable) flyToCentreThenLaunch(slot, project)
        }
        slot.setOnLongClickListener {
            confirmDeleteProject(project)
            true
        }
        attachPressFeedback(slot)

        // Halo breath: phase-offset per slot so neighbours don't pulse in
        // lock-step. Slow cycle (~2.4s) to match the FAB halo cadence.
        val breath = ObjectAnimator.ofFloat(halo, View.ALPHA, 0.55f, 1f).apply {
            duration = SATELLITE_HALO_BREATH_MS
            startDelay = slotIdx * (SATELLITE_HALO_BREATH_MS / 3)
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        satelliteHaloBreaths.add(breath)
    }

    private fun buildHaloDrawable(context: Context, color: Int): GradientDrawable {
        val density = context.resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = 40f * density
            // Stronger inner + middle stops so the halo defines the
            // star's edge against the GL background on its own —
            // replaces the (deleted) outline ring.
            colors = intArrayOf(
                ColorUtils.setAlphaComponent(color, 0xE6),
                ColorUtils.setAlphaComponent(color, 0x77),
                ColorUtils.setAlphaComponent(color, 0x00),
            )
        }
    }

    private fun attachPressFeedback(slot: View) {
        slot.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.92f).scaleY(0.92f)
                        .setDuration(90L)
                        .start()
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f)
                        .setInterpolator(OvershootInterpolator(2.4f))
                        .setDuration(280L)
                        .start()
                }
            }
            false  // click listener still fires on UP.
        }
    }

    /**
     * Fly-to-centre transition: the tapped satellite arcs to the centre
     * while scaling up; everything else dims. When the animation lands,
     * we hand off to the wizard. The fragment-replace transaction tears
     * down our view immediately afterwards, so we don't need to roll
     * the animation back — [onResume] resets state on return.
     */
    private fun flyToCentreThenLaunch(slot: View, project: Project) {
        // Disable the whole constellation so a second tap during the
        // transition can't fire a second wizard.
        slots.forEach { it.isClickable = false }

        slot.animate()
            .translationX(0f).translationY(0f)
            .scaleX(1.45f).scaleY(1.45f)
            .setDuration(SATELLITE_FLY_MS)
            .setInterpolator(AccelerateInterpolator(1.4f))
            .withEndAction {
                if (isAdded) launchCompileWizard(project)
            }
            .start()

        slots.filter { it !== slot }.forEach { other ->
            other.animate().alpha(0.2f).setDuration(180L).start()
        }
        fabContainer.animate().alpha(0.2f).setDuration(180L).start()
    }

    // ── Centred "+" stack ────────────────────────────────────────────

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
            false  // click listener still fires on UP.
        }
        fab.setOnClickListener { showArchivePicker() }
    }

    /**
     * Particle burst from the FAB centre, same beat as loader.rb's
     * spawn_bang. Each particle is a tiny View added to [container],
     * ejected radially, then removed on animation end.
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
                    .withEndAction { (parent as? ViewGroup)?.removeView(this) }
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
        satelliteHaloBreaths.forEach { it.cancel() }
        satelliteHaloBreaths.clear()
        super.onDestroyView()
    }

    // ── Wizard launch + archive import (unchanged) ───────────────────

    private fun confirmDeleteProject(project: Project) {
        MaterialAlertDialogBuilder(requireContext(), R.style.Theme_Psdk_Dialog)
            .setTitle("Delete project")
            .setMessage("Delete \"${project.name}\"? This removes its files from disk.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ -> deleteProject(project) }
            .show()
    }

    private fun deleteProject(project: Project) {
        val ctx = requireContext().applicationContext
        Thread {
            try {
                database.projectDao().delete(project)
                File(project.directory).deleteRecursively()
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    refreshProjects()
                }
            } catch (e: Exception) {
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    Toast.makeText(
                        ctx,
                        "Delete failed: ${e.localizedMessage ?: e.javaClass.simpleName}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }.start()
    }

    private fun launchCompileWizard(project: Project) {
        // Called when an import's background copy (hundreds of MB, many
        // seconds) or the fly-to-centre animation completes. If the user
        // left the app meanwhile, the activity has saved its state and
        // commit() throws IllegalStateException: defer to onResume.
        if (requireActivity().supportFragmentManager.isStateSaved) {
            android.util.Log.i("ProjectListFragment", "state saved, deferring wizard launch for ${project.name}")
            pendingWizardProject = project
            return
        }
        val fragment = CompileWizardFragment.newInstance(
            executionLocation = project.directory,
            classicBootstrap = false,
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

    private fun importArchive(uri: Uri) {
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
                    launchCompileWizard(project)
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
         * Debug harness, not a project hub — capping at 3 matches the
         * constellation's slot count.
         */
        private const val MAX_PROJECTS = 3

        // Resting positions for the 3 slots, in dp from the FAB centre.
        // Same Y for a clean horizontal arc; X spreads outward so the
        // outer slots clear the FAB halo's gradient fade.
        private const val SATELLITE_HORIZONTAL_DP = 110f
        private const val SATELLITE_VERTICAL_DP   = 200f

        private const val SATELLITE_HALO_BREATH_MS = 2400L
        private const val SATELLITE_FLY_MS = 280L
        private const val SATELLITE_FADE_IN_MS = 2000L

        private const val PARTICLE_COUNT = 12
        private const val PARTICLE_SIZE_DP = 8
        private const val PARTICLE_TRAVEL_DP = 64
        private const val PARTICLE_DURATION_MS = 520L
    }
}
