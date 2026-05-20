package com.psdk.ui

import com.psdk.ruby.vm.ArchiveKeys

/**
 * Describes what the GL surface should currently be running.
 *
 * Pure data — no behaviour, no Android imports. The behaviour lives in
 * [RubyWorkloadController], which switch-cases on the sealed type.
 * Keeping workloads as data prevents these classes from accreting
 * Android dependencies as the codebase grows.
 *
 * The companion convention: each workload owns its own (script,
 * preludes, execution location, env vars, on-complete callback). Two
 * workloads are interchangeable from the controller's perspective —
 * the controller's job is just to swap one for another atomically,
 * not to know what they do.
 */
sealed class RubyWorkload {

    /** Asset path of the top-level Ruby script to execute. */
    abstract val scriptAsset: String

    /**
     * Asset paths of helper modules concatenated BEFORE [scriptAsset].
     * Same convention RootActivity uses: the embedded
     * VM `eval`s the combined string, so `require_relative` can't
     * resolve siblings.
     */
    abstract val preludes: List<String>

    /**
     * Working directory for the Ruby script's PSDK_EXECUTION_LOCATION
     * env var. May be null if the script doesn't need one.
     */
    abstract val executionLocation: String?

    /**
     * Optional encrypted-archive descriptor. If non-null, the
     * controller exports PSDK_EPSA_* env vars so the script can open
     * the archive via the streaming decrypter.
     */
    abstract val archive: ArchiveKeys?

    /** Logical name for diagnostics. */
    abstract val name: String

    /**
     * The splash / loader (loader.rb + Loader::* helpers). Drawn into
     * the surface while the app's shell UI (hub, wizard fragments) is
     * in the foreground.
     */
    data class Loader(
        override val executionLocation: String,
        override val archive: ArchiveKeys?,
        override val preludes: List<String>,
    ) : RubyWorkload() {
        override val scriptAsset: String = "loader.rb"
        override val name: String = "loader"
    }

    /**
     * The game itself (start.rb → Game.yarb). When this workload is
     * active, the host hides shell UI and routes hardware input
     * through [GameInputForwarder] / [ImeBridge].
     */
    data class Game(
        override val executionLocation: String,
        override val preludes: List<String> = emptyList(),
    ) : RubyWorkload() {
        override val scriptAsset: String = "start.rb"
        override val archive: ArchiveKeys? = null
        override val name: String = "game"
    }
}
