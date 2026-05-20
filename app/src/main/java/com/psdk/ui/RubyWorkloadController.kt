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
 * Async state machine over the PsdkInterpreter lifecycle. Callers
 * request workloads via [start]; the controller serialises swaps
 * (tear down old → start new) without ever blocking the calling
 * thread on interpreter teardown.
 *
 * State:
 *   IDLE              — nothing running.
 *   RUNNING(workload) — interpreter alive, executing workload.
 *   TEARING_DOWN      — background thread is waiting for the old
 *                       interpreter to finish; [pendingWorkload]
 *                       records what to launch next (or null for
 *                       "stop and stay idle"). Reassigning pending
 *                       during teardown is fine — last write wins.
 *
 * Why async: PsdkInterpreter.close() is a synchronous native call
 * that waits for the Ruby render thread to fully exit. That can
 * take anywhere from a few ms (graceful unwind through `ensure`
 * blocks) to several seconds (KMP's log-flush sentinel timeout
 * kicks in at 5s if the render thread doesn't signal cleanly).
 * Calling it on the UI thread — as the old design did — turns
 * every fragment-driven workload swap into a potential ANR. With
 * the close moved to a dedicated thread, the UI thread returns
 * immediately and the next workload launches when the close
 * finishes, on whatever thread that finally runs.
 *
 * Thread safety: every public method is callable from any thread.
 * All state mutation happens inside [lock]. Callbacks ([onLog],
 * [onError], [onWorkloadCompleted]) fire from whichever thread the
 * underlying KMP layer chooses (typically the Ruby render thread)
 * — consumers are responsible for hopping to the UI thread if they
 * touch View state.
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
    private var currentInterpreter: PsdkInterpreter? = null

    /**
     * Set to the next workload while we're either:
     *   - Waiting for the surface to come up (initial launch); or
     *   - In TEARING_DOWN, waiting for the old interpreter to die.
     * Cleared once launched. Last write wins — caller can spam
     * start() with different workloads and only the final one
     * actually runs.
     */
    private var pendingWorkload: RubyWorkload? = null

    /**
     * True while the dedicated teardown thread is running. Reset
     * to false in that thread's finally block. While true, no new
     * workload can launch — we must wait for the old interpreter
     * to fully exit so the EGL context releases cleanly before
     * the next render thread tries to acquire it.
     */
    private var tearingDown: Boolean = false

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
     * surface existed, it launches now.
     *
     * Safe to call from any thread.
     */
    fun onSurfaceReady() {
        synchronized(lock) {
            if (surfaceReady) return
            surfaceReady = true
            maybeLaunchPendingLocked()
        }
    }

    /**
     * Request [workload] become the active workload.
     *
     * Non-blocking. The actual close-of-previous + launch-of-new
     * happens on a background thread (and the launch happens on
     * whatever thread the close finished on, since
     * PsdkInterpreter spawns its own worker thread inside enqueue).
     *
     * If a workload is already running, this tears it down first.
     * If a teardown is already in flight, the new workload replaces
     * any queued one — last write wins.
     */
    fun start(workload: RubyWorkload) {
        synchronized(lock) {
            pendingWorkload = workload
            when {
                tearingDown -> {
                    // Already tearing down. When the teardown thread
                    // finishes it'll pick up pendingWorkload.
                    Log.i(TAG, "start: '${workload.name}' queued (teardown in progress)")
                }
                currentWorkload != null -> {
                    // Need to tear down the current one before launching.
                    Log.i(TAG, "start: tearing down '${currentWorkload?.name}' before '${workload.name}'")
                    beginTeardownLocked()
                }
                else -> {
                    // Idle — try to launch immediately (gated on surface ready).
                    maybeLaunchPendingLocked()
                }
            }
        }
    }

    /**
     * Stop the current workload (if any) without queueing a
     * successor. Non-blocking; the close happens on a background
     * thread.
     *
     * Idempotent.
     */
    fun stop() {
        synchronized(lock) {
            pendingWorkload = null
            if (!tearingDown && currentWorkload != null) {
                beginTeardownLocked()
            }
        }
    }

    /** Caller MUST hold [lock]. Kicks off async teardown. */
    private fun beginTeardownLocked() {
        val interp = currentInterpreter ?: return
        val oldName = currentWorkload?.name ?: "?"
        tearingDown = true
        currentInterpreter = null
        currentWorkload = null

        // Push Closed so the render loop will unwind cleanly via
        // the script's ensure block(s). The actual close() below
        // blocks until that unwind completes (or KMP's log-flush
        // sentinel times out at 5s).
        NativeSurface.requestClose()

        Thread {
            try {
                Log.i(TAG, "teardown thread: waiting for '$oldName' to exit")
                interp.close()
                Log.i(TAG, "teardown thread: '$oldName' exited cleanly")
            } catch (e: Exception) {
                Log.e(TAG, "teardown thread: '$oldName' close threw", e)
            } finally {
                synchronized(lock) {
                    tearingDown = false
                    maybeLaunchPendingLocked()
                }
            }
        }.apply {
            name = "RubyWorkloadTeardown"
            start()
        }
    }

    /**
     * Caller MUST hold [lock]. Launches [pendingWorkload] iff:
     *   - the surface is ready,
     *   - no teardown is in flight, and
     *   - no workload is currently running.
     */
    private fun maybeLaunchPendingLocked() {
        if (!surfaceReady) return
        if (tearingDown) return
        if (currentWorkload != null) return
        val workload = pendingWorkload ?: return
        pendingWorkload = null
        launchLocked(workload)
    }

    /** Caller MUST hold [lock]. */
    private fun launchLocked(workload: RubyWorkload) {
        Log.i(TAG, "launching workload '${workload.name}' (execLoc=${workload.executionLocation})")

        val listener = object : LogListener {
            override fun onLogMessage(logMessage: LogMessage) {
                onLog(logMessage)
            }
        }
        val interp = PsdkInterpreter.create(context, listener, onError)
        currentInterpreter = interp
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
