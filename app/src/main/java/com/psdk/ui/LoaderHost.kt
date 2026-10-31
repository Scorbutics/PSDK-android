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
     * Request that the host start the game workload for [executionLocation]
     * in the chosen [mode]. When [mode] is [GameMode.DEBUG_UNCOMPILED] the
     * host MUST already have the project's archive keys resolved — the
     * implementation surfaces them via env vars so start.rb's prelude can
     * `ArchiveMount.mount!` from the encrypted .epsa.
     *
     * Implemented by [RootActivity] by stopping the loader and launching
     * [GameActivity] in its own `:game` process, so the game always
     * starts on a fresh Ruby VM. The splash is restarted when the game
     * activity returns.
     */
    fun startGame(executionLocation: String, mode: GameMode = GameMode.RELEASE)

    /**
     * While true, the host does not restart the splash on its own (it does
     * after a surface resize, to re-lay it out); a pending restart runs once
     * this goes back to false. Set during a compile: the compile shares the
     * Ruby VM, and so the process working directory, that every splash
     * restart re-chdirs, which broke the compile's relative paths.
     */
    fun setLoaderRestartsDeferred(deferred: Boolean)

    /**
     * Send [message] to the splash. Callable from any thread, never waits.
     * A splash restarted in the same state shows the last message again;
     * [setLoaderState] to another state drops it, along with any message
     * sent before it and not yet applied.
     */
    fun sendLoaderMessage(message: LoaderMessage)
}

/** A message to the splash: loader/host_messages.rb applies each topic. */
sealed class LoaderMessage(val topic: String, val payload: String = "") {
    /** The status line: a compile sub-step, a call to action. */
    class Status(text: String) : LoaderMessage("loader.status", text)

    /** Lift the centerpiece to make room for the result card, then show [text]. */
    class Lift(text: String) : LoaderMessage("loader.lift", text)

    /** Settle the centerpiece back once the result card is dismissed. */
    object Unlift : LoaderMessage("loader.unlift")

    /** Play the release-on-icon visual without a finger (classic bootstrap). */
    object Tap : LoaderMessage("loader.tap")

    /** Wipe the compile cycle's scene state in place, then show [text]. */
    class Reset(text: String) : LoaderMessage("loader.reset", text)
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

    /**
     * Bundled PSDK background, no icon and no hub affordances (halo, "Add
     * project"): just [message] centred. Used while the host prepares a
     * project the splash can't show yet, e.g. the classic wizard copying
     * the bundled archive out of the APK.
     */
    data class Preparing(val message: String) : LoaderState()
}
