package com.psdk.ui

import android.content.Context
import java.io.File
import android.content.res.AssetManager
import android.util.Log
import com.psdk.ruby.vm.PsdkInterpreter
import com.psdk.ruby.vm.RubyScript
import com.psdk.ruby.vm.ScriptLocation
import com.scorbutics.litergss.NativeSurface
import com.scorbutics.rubyvm.LogListener
import com.scorbutics.rubyvm.LogMessage

/**
 * Persistent-interpreter controller for the GL-rendering workload track.
 * Used by both hosts: [RootActivity] runs loader workloads on it for the
 * shell process's whole lifetime, [GameActivity] runs a single game
 * workload on it in the `:game` process. One PsdkInterpreter is created lazily on first
 * launch, owns the cgss::DisplayWindow / EGL context, and survives all
 * subsequent workload swaps for the entire Activity lifetime. Scripts
 * are enqueued sequentially on its single render thread; a swap is
 * just "push Closed → wait for current script's onComplete → enqueue
 * the next".
 *
 * Why persistent: every workload swap in the old multi-interpreter
 * model triggered a render-thread handoff, which forced an EGL context
 * release-on-old / acquire-on-new dance. With multiple threads (Ruby
 * worker, SFML sharedContext background ops, Android's HWUI renderer)
 * all eligible to bind the context, that handoff was racy and produced
 * the recurring "Failed to activate the window's context" spam. Pinning
 * all GL-touching code to one persistent thread eliminates the race
 * entirely — the context never changes hands.
 *
 * What this does NOT do: reset Ruby VM state between scripts. The
 * embedded VM evals every script at TOPLEVEL_BINDING against a
 * process-singleton VM, so classes/constants/globals from script A
 * coexist with script B regardless of whether we destroy the
 * interpreter between them. loader.rb copes by being re-eval-safe
 * (`unless defined?` guards). The game can't — PSDK's alias_method
 * chains compound on re-eval — so it never shares a VM: GameActivity
 * runs it in a throwaway `:game` process.
 *
 * Ephemeral utility interpreters (compile.rb, extract_loader_assets,
 * epsa validation, etc.) are NOT this controller's concern. They
 * create their own PsdkInterpreter instances, run, and destroy
 * themselves. They share the same g_global_vm with the persistent one
 * but never touch the DisplayWindow or EGL context, so they don't
 * conflict with the persistent track.
 *
 * Thread safety: every public method is callable from any thread.
 * All state mutation is inside [lock]. Callbacks ([onLog],
 * [onWorkloadCompleted]) fire from the Ruby render thread or from
 * the KMP enqueue thread that wraps it — consumers are responsible
 * for hopping to the UI thread if they touch View state.
 */
class RubyWorkloadController(
    private val context: Context,
    private val assets: AssetManager,
    private val onLog: (LogMessage) -> Unit = { },
    private val onError: (Exception) -> Unit = { e -> Log.e(TAG, "interpreter error", e) },
    private val onWorkloadCompleted: (RubyWorkload, Int) -> Unit = { _, _ -> },
) {

    private val lock = Any()

    private var surfaceReady: Boolean = false

    /**
     * Created lazily on first [launchLocked] and never destroyed
     * until [shutdown] (called from Activity.onDestroy). Survives
     * every workload swap.
     */
    private var persistentInterpreter: PsdkInterpreter? = null

    /** The workload whose script is currently executing on the persistent
     *  interpreter, or null if idle. */
    private var currentWorkload: RubyWorkload? = null

    /**
     * Set while waiting for the surface to come up (initial launch) or
     * while waiting for the current script to finish unwinding after a
     * swap signal. Last-write-wins — callers can spam start() and only
     * the final workload runs.
     */
    private var pendingWorkload: RubyWorkload? = null

    /**
     * True between "we pushed Closed to interrupt the current script"
     * and "the script's onComplete fired". maybeRunPendingLocked won't
     * launch the next workload while this is true — we must wait for
     * the current script's ensure-block / dispose chain to run so the
     * DisplayWindow is in a clean state before the next script
     * constructs (or reuses) it.
     */
    private var swapping: Boolean = false

    /**
     * The workload currently active or about to be active.
     * Returns the running one if any; otherwise the pending one
     * (which will launch on next state transition).
     */
    fun currentWorkload(): RubyWorkload? = synchronized(lock) {
        currentWorkload ?: pendingWorkload
    }

    /**
     * Notify the controller that the GL surface is attached and
     * ready. Idempotent. If a workload was queued before the
     * surface existed, it launches now (on the freshly-created
     * persistent interpreter's worker thread).
     *
     * Safe to call from any thread.
     */
    fun onSurfaceReady() {
        synchronized(lock) {
            if (surfaceReady) return
            surfaceReady = true
            maybeRunPendingLocked()
        }
    }

    /**
     * Notify the controller that the GL surface was detached (activity
     * hidden). A running workload keeps running — SFML handles the
     * detach/attach cycle itself — but new launches are held until the
     * next [onSurfaceReady]. A script that constructs its DisplayWindow
     * while no surface is attached renders nothing (black screen), e.g.
     * the loader restarted from the game's activity result, which
     * arrives before the shell's surface comes back.
     *
     * Safe to call from any thread.
     */
    fun onSurfaceLost() {
        synchronized(lock) {
            surfaceReady = false
        }
    }

    /**
     * Request [workload] become the active workload.
     *
     * Non-blocking. Behaviour by current state:
     *   - idle: launches immediately (lazily creating the persistent
     *     interpreter on first call), gated on surface ready.
     *   - currently running another workload: pushes Closed to signal
     *     the current script to unwind, queues [workload] for launch
     *     from the current script's onComplete callback.
     *   - already swapping: just queues [workload]. Last write wins —
     *     if multiple start() calls fire before the current script
     *     unwinds, only the most recent one actually runs.
     */
    fun start(workload: RubyWorkload) {
        synchronized(lock) {
            pendingWorkload = workload
            when {
                swapping -> {
                    Log.i(TAG, "start: '${workload.name}' queued (swap in progress)")
                }
                currentWorkload != null -> {
                    Log.i(TAG, "start: signalling '${currentWorkload?.name}' to unwind, then '${workload.name}'")
                    beginSwapLocked()
                }
                else -> {
                    maybeRunPendingLocked()
                }
            }
        }
    }

    /**
     * Signal the current workload to stop without queueing a
     * successor. Non-blocking; the current script's onComplete will
     * fire from the render thread once the script unwinds. The
     * persistent interpreter remains alive.
     *
     * Idempotent.
     */
    fun stop() {
        synchronized(lock) {
            pendingWorkload = null
            if (!swapping && currentWorkload != null) {
                beginSwapLocked()
            }
        }
    }

    /**
     * Destroy the persistent interpreter for good. Call from
     * Activity.onDestroy. After this, no more workloads can run on
     * this controller instance.
     *
     * Blocks the calling thread on the PsdkInterpreter's underlying
     * KMP destroy, which waits for the render thread to fully exit
     * (worst case 5s for the log-flush sentinel timeout). Acceptable
     * here because Activity teardown is a one-shot terminal event.
     */
    fun shutdown() {
        val interp: PsdkInterpreter?
        synchronized(lock) {
            pendingWorkload = null
            if (currentWorkload != null) {
                requestCloseLocked()
            }
            interp = persistentInterpreter
            persistentInterpreter = null
            currentWorkload = null
            swapping = false
        }
        // Close outside the lock — KMP destroyInterpreter blocks
        // waiting for the worker thread to exit, and we don't want
        // to hold the lock while that happens.
        interp?.close()
    }

    /**
     * Caller MUST hold [lock]. Pushes Closed into the DisplayWindow's
     * event queue. The current script's render loop drains it on its
     * next frame, raises rb_eClosedWindow, unwinds. The ensure block
     * (loader.rb's `begin/ensure dispose end` and the equivalent in
     * start.rb / Game.rb) runs on the persistent render thread,
     * releases the EGL context cleanly via cgss::DisplayWindow::stop's
     * setActive(false), and the script returns. PsdkInterpreter.enqueue's
     * thread wrapper then fires onComplete, which is where we launch
     * the next workload.
     */
    private fun beginSwapLocked() {
        swapping = true
        requestCloseLocked()
    }

    /**
     * Caller MUST hold [lock]. Asks the running script to close its window.
     * NativeSurface.requestClose is lost if the script hasn't created its
     * window yet, so for the splash also drop a marker file it checks
     * before creating the window and while it runs (loader/util.rb
     * LOADER_STOP_PATH). Loader only: the `:game` process shares the data
     * dir, and a marker written at game shutdown could land after the
     * shell already cleared it for the restarted splash.
     */
    private fun requestCloseLocked() {
        NativeSurface.requestClose()
        if (currentWorkload is RubyWorkload.Loader) setLoaderStopMarker(true)
    }

    private fun setLoaderStopMarker(present: Boolean) {
        val marker = File(LoaderPaths.executionLocation(context), LOADER_STOP_MARKER)
        try {
            if (present) marker.writeText("") else marker.delete()
        } catch (e: Exception) {
            Log.w(TAG, "loader stop marker update failed (present=$present)", e)
        }
    }

    /**
     * Caller MUST hold [lock]. Launches [pendingWorkload] iff:
     *   - the surface is ready,
     *   - no swap is in flight, and
     *   - no workload is currently running.
     */
    private fun maybeRunPendingLocked() {
        if (!surfaceReady) return
        if (swapping) return
        if (currentWorkload != null) return
        val workload = pendingWorkload ?: return
        pendingWorkload = null
        launchLocked(workload)
    }

    /** Caller MUST hold [lock]. */
    private fun launchLocked(workload: RubyWorkload) {
        Log.i(TAG, "launching workload '${workload.name}' (execLoc=${workload.executionLocation})")
        if (workload is RubyWorkload.Loader) setLoaderStopMarker(false)

        // Lazy first-use creation. The interpreter and its render
        // thread (rgss-worker-N) survive every subsequent workload
        // swap — only Activity.onDestroy via [shutdown] tears it down.
        if (persistentInterpreter == null) {
            val listener = object : LogListener {
                override fun onLogMessage(logMessage: LogMessage) {
                    onLog(logMessage)
                }
            }
            persistentInterpreter = PsdkInterpreter.create(context, listener, onError)
        }
        currentWorkload = workload

        val script = RubyScript(assets, workload.scriptAsset, workload.preludes)
        persistentInterpreter!!.enqueue(
            script = script,
            location = ScriptLocation(
                executionLocation = workload.executionLocation,
                archive = workload.archive,
            ),
            onComplete = { exitCode ->
                Log.i(TAG, "workload '${workload.name}' exited (code=$exitCode)")
                val justCompleted: RubyWorkload?
                synchronized(lock) {
                    justCompleted = currentWorkload
                    currentWorkload = null
                    swapping = false
                    // Launch the next queued workload — possibly the one
                    // that triggered our swap, possibly a more recent
                    // overwrite (last-write-wins).
                    maybeRunPendingLocked()
                }
                if (justCompleted != null) {
                    onWorkloadCompleted(justCompleted, exitCode)
                }
            }
        )
    }

    companion object {
        /** Read by the splash as Loader::LOADER_STOP_PATH. */
        private const val LOADER_STOP_MARKER = ".loader_stop"

        private const val TAG = "RubyWorkloadCtrl"
    }
}
