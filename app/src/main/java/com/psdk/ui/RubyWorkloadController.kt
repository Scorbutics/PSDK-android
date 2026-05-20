package com.psdk.ui

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import com.psdk.ruby.vm.PsdkInterpreter
import com.psdk.ruby.vm.RubyScript
import com.psdk.ruby.vm.ScriptLocation
import com.scorbutics.litergss.NativeSurface
import com.scorbutics.rubyvm.LogListener
import com.scorbutics.rubyvm.LogMessage

/**
 * Single point of truth for "what Ruby script is currently running in
 * the embedded VM, and how do we swap it for another."
 *
 * Owns the [PsdkInterpreter] lifecycle. Callers pass a [RubyWorkload];
 * the controller serializes start/stop so the previous workload is
 * torn down cleanly before the next one is launched.
 *
 * No knowledge of fragments, no knowledge of input forwarding, no
 * knowledge of the Activity. Takes:
 *   - a Context for the PsdkInterpreter factory (for asset access,
 *     paths)
 *   - an AssetManager for [RubyScript] construction
 *   - optional callbacks for diagnostics ([onLog], [onError]) and for
 *     workload completion ([onWorkloadCompleted])
 *
 * Thread safety: all public methods are intended to be called from
 * the UI thread. Workload teardown blocks the calling thread (matches
 * how PsdkInterpreter.close already works) — typically tens of ms.
 *
 * Surface gating: [start] is idempotent w.r.t. "no surface yet". If
 * the surface isn't ready, the workload is remembered; once
 * [onSurfaceReady] is called, the queued workload starts.
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
    private var currentWorkload: RubyWorkload? = null
    private var pendingWorkload: RubyWorkload? = null
    private var interpreter: PsdkInterpreter? = null

    /** The workload currently running (or queued to run when the surface comes up). */
    fun currentWorkload(): RubyWorkload? = synchronized(lock) { currentWorkload ?: pendingWorkload }

    /**
     * Notify the controller the GL surface is attached and ready to be
     * drawn into. Idempotent. If a workload was queued before the
     * surface existed, it starts now.
     */
    fun onSurfaceReady() {
        synchronized(lock) {
            surfaceReady = true
            val queued = pendingWorkload ?: return
            pendingWorkload = null
            launchLocked(queued)
        }
    }

    /**
     * Request that [workload] be the active workload. If a workload
     * is currently running, tear it down first. If the surface isn't
     * ready, queue the workload and start it once [onSurfaceReady]
     * fires.
     *
     * Does NOT dedupe on workload equality — two semantically-distinct
     * loader sessions can produce equal RubyWorkload.Loader instances
     * (e.g. switching between LoaderState.Default and
     * LoaderState.FromExtracted both yield the same Loader data because
     * the asset-on-disk difference lives outside the workload). The
     * caller is the source of truth for "should I restart" — it knows
     * about side effects this class doesn't. RootActivity.setLoaderState
     * is the semantic gate.
     *
     * Blocking: the teardown half (NativeSurface.requestClose +
     * interpreter.close) is synchronous on the calling thread.
     * Typically tens of ms once the Closed event reaches the render
     * loop. Same blocking semantics RootActivity.shutdownLoader and
     * the loader-restart path have always had.
     */
    fun start(workload: RubyWorkload) {
        synchronized(lock) {
            if (currentWorkload != null) {
                Log.i(TAG, "start: tearing down ${currentWorkload?.name} before ${workload.name}")
                teardownLocked()
            }
            if (!surfaceReady) {
                Log.i(TAG, "start: surface not ready, queueing ${workload.name}")
                pendingWorkload = workload
                return
            }
            launchLocked(workload)
        }
    }

    /**
     * Stop the current workload (if any) without queueing a successor.
     *
     * Use cases:
     *   - host activity onDestroy
     *   - explicit "shut down the splash before handing the process to
     *     a sibling activity" (legacy LoaderHost.shutdownLoader path).
     *
     * Idempotent.
     */
    fun stop() {
        synchronized(lock) {
            pendingWorkload = null
            if (currentWorkload == null) return
            teardownLocked()
        }
    }

    /** Caller MUST hold [lock]. Tears down the current workload. */
    private fun teardownLocked() {
        // Order matters (same as RootActivity's historical teardown):
        // requestClose pushes sf::Event::Closed into the active
        // DisplayWindow's queue. The Ruby render loop drains it on
        // its next frame, raises rb_eClosedWindow, unwinds the
        // script. close() then synchronously joins. Reversing the
        // order deadlocks the UI thread because :stop sits behind an
        // infinite render loop.
        NativeSurface.requestClose()
        interpreter?.close()
        interpreter = null
        currentWorkload = null
    }

    /** Caller MUST hold [lock] AND [surfaceReady] must be true. */
    private fun launchLocked(workload: RubyWorkload) {
        Log.i(TAG, "launching workload '${workload.name}' (execLoc=${workload.executionLocation})")

        val listener = object : LogListener {
            override fun onLogMessage(logMessage: LogMessage) {
                onLog(logMessage)
            }
        }
        val interp = PsdkInterpreter.create(context, listener, onError)
        interpreter = interp
        currentWorkload = workload

        val script = RubyScript(assets, workload.scriptAsset, workload.preludes)
        interp.enqueue(
            script = script,
            location = ScriptLocation(
                executionLocation = workload.executionLocation,
                archive = workload.archive,
            ),
            onComplete = { exitCode ->
                Log.i(TAG, "workload '${workload.name}' exited (code=$exitCode)")
                onWorkloadCompleted(workload, exitCode)
            }
        )
    }

    companion object {
        private const val TAG = "RubyWorkloadCtrl"
    }
}
