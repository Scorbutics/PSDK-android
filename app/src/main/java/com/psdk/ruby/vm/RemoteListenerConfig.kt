package com.psdk.ruby.vm

import com.psdk.BuildConfig

/**
 * Single source of truth for the rdbg/DAP + line-eval remote listener
 * configuration. PsdkInterpreter (Kotlin JNI path) and GameActivity's
 * env-var setup read from here, so the host-side `adb forward` and
 * `rdbg --attach` / `nc` commands stay the same no matter which entry
 * point spawned the VM.
 *
 * Debug-only by design — release builds short-circuit [enabled] to false
 * so the listeners are never armed in shipped APKs. The cookie is
 * hardcoded here for predictable `adb forward` smoke tests; rotate it
 * by editing this file (or wire it to a `local.properties` field if you
 * want per-developer overrides).
 *
 * Default ports / token chosen to be obvious and easy to type:
 *
 *     adb forward tcp:7777 tcp:7777   # rdbg
 *     adb forward tcp:7778 tcp:7778   # eval
 *
 *     RUBY_DEBUG_COOKIE=psdk-dev-cookie rdbg _1.4.0_ --attach 127.0.0.1 7777
 *     rlwrap nc 127.0.0.1 7778        # then paste:  psdk-dev-cookie
 *
 * Those ports reach the GAME's VM (the `:game` process, see GameActivity).
 * The shell process (loader, compile) has its own VM and listens on
 * 7787 / 7788 instead — both processes are alive while a game runs, so
 * they can't share a port.
 *
 * Ports are device-global, and every APK built from this template (the
 * PSDK hub, each exported game) asks for the same ones. The pair actually
 * used is the first free one among the defaults, +100, +200, ... — check
 * logcat (tag PsdkInterpreter / GameActivity) for the ports to forward.
 */
object RemoteListenerConfig {
    const val DEBUG_HOST  = "127.0.0.1"
    const val DEBUG_PORT  = 7777
    const val EVAL_HOST   = "127.0.0.1"
    const val EVAL_PORT   = 7778
    const val SHELL_DEBUG_PORT = 7787
    const val SHELL_EVAL_PORT  = 7788
    const val TOKEN       = "psdk-dev-cookie"

    /** Off in release builds, on in debug builds. Inlined by R8 in release. */
    val enabled: Boolean get() = BuildConfig.DEBUG

    /** Ports for the VM living in the current process. */
    val debugPort: Int get() = ports.first
    val evalPort: Int get() = ports.second

    private const val PORT_PAIR_STEP     = 100
    private const val PORT_PAIR_ATTEMPTS = 10

    /**
     * Resolved once per process: the first (debug, eval) pair both free on
     * the loopback, starting at this process's defaults. Falls back to the
     * defaults when none is free; the VM then logs the bind failure and
     * runs without the listener (the probe is non-fatal on its side).
     */
    private val ports: Pair<Int, Int> by lazy {
        val defaultDebug = if (isGameProcess) DEBUG_PORT else SHELL_DEBUG_PORT
        val defaultEval  = if (isGameProcess) EVAL_PORT else SHELL_EVAL_PORT
        val pair = (0 until PORT_PAIR_ATTEMPTS)
            .map { defaultDebug + it * PORT_PAIR_STEP to defaultEval + it * PORT_PAIR_STEP }
            .firstOrNull { (dbg, eval) -> isBindable(DEBUG_HOST, dbg) && isBindable(EVAL_HOST, eval) }
            ?: (defaultDebug to defaultEval)
        if (pair.first != defaultDebug) {
            android.util.Log.w("RemoteListenerConfig",
                "ports $defaultDebug/$defaultEval busy (another PSDK app?), using ${pair.first}/${pair.second}")
        }
        pair
    }

    private fun isBindable(host: String, port: Int): Boolean = runCatching {
        java.net.ServerSocket().use { socket ->
            socket.reuseAddress = true
            socket.bind(java.net.InetSocketAddress(host, port))
        }
    }.isSuccess

    /**
     * True in the `:game` process. Read from /proc rather than
     * Application.getProcessName(), which needs API 28 (minSdk is 26).
     */
    private val isGameProcess: Boolean by lazy {
        val name = runCatching {
            java.io.File("/proc/self/cmdline").readText().substringBefore('\u0000')
        }.getOrDefault("")
        name.endsWith(":game")
    }
}
