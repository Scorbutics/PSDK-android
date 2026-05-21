package com.psdk.ui

import com.psdk.ruby.vm.ArchiveKeys
import java.io.File

/**
 * Contract a fragment uses to interact with the long-lived splash
 * interpreter owned by its host activity ([RootActivity]).
 *
 * The interpreter and its GL surface outlive any single fragment, but
 * fragments still need to:
 *   - learn when the user taps the splash icon, or when the splash has
 *     flipped its first frame onto EGL ([LoaderListener]),
 *   - declare what the splash should currently be showing ([setLoaderState]).
 *
 * The activity decides whether a [setLoaderState] call actually triggers
 * a restart — equal states are no-ops. Callers don't dedupe themselves;
 * they just describe the desired state.
 *
 * Implementations call back on the main thread.
 */
interface LoaderHost {
    /** Replace the current event listener. Pass null to clear. */
    fun setLoaderListener(listener: LoaderListener?)

    /**
     * Declare the splash's desired state. If [state] is equal to the
     * activity's currently-running state, no-op. Otherwise the activity
     * prepares the on-disk asset dir (re-installing PSDK defaults, or
     * copying from a project's pre-extracted dir, or leaving it for the
     * loader's own prelude to populate) and restarts the interpreter.
     */
    fun setLoaderState(state: LoaderState)

    /**
     * Request that the host start the game workload for [executionLocation].
     *
     * Implemented by [RootActivity] as an in-process script swap on
     * the persistent interpreter: the controller pushes Closed to
     * signal the loader script to unwind, then enqueues start.rb on
     * the SAME interpreter / SAME render thread / SAME EGL context
     * the loader was running on. No interpreter destroy, no thread
     * handoff, no surface destroy/create cycle.
     */
    fun startGame(executionLocation: String)
}

interface LoaderListener {
    /** Fired once loader.rb's first @window.update has reached the EGL surface. */
    fun onLoaderReady() {}

    /** Fired every time loader.rb reports an icon hit (per touch). */
    fun onIconTapped() {}
}

/**
 * What the splash is currently showing — the activity tracks one of
 * these and treats [setLoaderState] as idempotent on equality.
 */
sealed class LoaderState {
    /**
     * Bundled PSDK assets (no project context). Used by the hub. The
     * activity wipes [LoaderPaths.assetsDir] back to APK defaults before
     * launching the loader; this overwrites any project assets a prior
     * wizard session left on disk.
     */
    object Default : LoaderState()

    /**
     * Project assets the caller has already extracted to [sourceDir].
     * The activity copies them into [LoaderPaths.assetsDir] and launches
     * the loader with the default config (no archive bound, no prelude).
     *
     * Used by the super-user wizard after Kotlin-side archive validation
     * runs `extract_loader_assets.rb` to a per-project dir.
     */
    data class FromExtracted(val sourceDir: File) : LoaderState()

    /**
     * Project assets that don't yet exist on disk — the activity binds
     * [archive] and runs [prelude] inside the loader's own Ruby thread
     * so it can extract through PhysFS before `loader.rb` reads any
     * files.
     *
     * Used by the classic-bootstrap wizard, which has no separate
     * Kotlin-side validation pass.
     */
    data class FromArchive(val archive: ArchiveKeys, val prelude: List<String>) : LoaderState()
}
