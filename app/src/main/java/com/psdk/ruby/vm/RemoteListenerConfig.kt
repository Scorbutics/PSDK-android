package com.psdk.ruby.vm

import com.psdk.BuildConfig

/**
 * Single source of truth for the rdbg/DAP + line-eval remote listener
 * configuration. Both PsdkInterpreter (Kotlin JNI path) and GameLauncher
 * (NativeActivity / rgss_runtime path) read from here, so the host-side
 * `adb forward` and `rdbg --attach` / `nc` commands stay the same no
 * matter which entry point spawned the VM.
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
 */
object RemoteListenerConfig {
    const val DEBUG_HOST  = "127.0.0.1"
    const val DEBUG_PORT  = 7777
    const val EVAL_HOST   = "127.0.0.1"
    const val EVAL_PORT   = 7778
    const val TOKEN       = "psdk-dev-cookie"

    /** Off in release builds, on in debug builds. Inlined by R8 in release. */
    val enabled: Boolean get() = BuildConfig.DEBUG
}
