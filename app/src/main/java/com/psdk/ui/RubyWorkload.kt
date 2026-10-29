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
     * The game itself (start.rb → Game.yarb). Only ever run by
     * [GameActivity], in its own `:game` process, which routes hardware
     * input through [GameInputForwarder] / [ImeBridge].
     *
     * Two launch modes:
     *   - [GameMode.RELEASE]: start.rb chdir's into `<exec>/Release` and
     *     `require './Game.rb'` against the real FS. No archive needed.
     *   - [GameMode.DEBUG_UNCOMPILED]: start.rb mounts [archive] via PhysFS
     *     and `require './Game.rb'` against the encrypted .epsa, skipping
     *     PSDK's project_compilation pipeline. [archive] MUST be non-null
     *     for this mode.
     */
    data class Game(
        override val executionLocation: String,
        val mode: GameMode = GameMode.RELEASE,
        override val archive: ArchiveKeys? = null,
        override val preludes: List<String> = emptyList(),
    ) : RubyWorkload() {
        init {
            if (mode == GameMode.DEBUG_UNCOMPILED) {
                require(archive != null) {
                    "GameMode.DEBUG_UNCOMPILED requires a non-null archive"
                }
            }
        }
        override val scriptAsset: String = "start.rb"
        override val name: String = "game"
    }
}

/**
 * Selects which on-disk layout `start.rb` runs against. See [RubyWorkload.Game].
 */
enum class GameMode {
    /** Real FS, `<exec>/Release` (the artifact of compile.rb / project_compilation). */
    RELEASE,
    /** PhysFS-mounted .epsa, no precompile needed. PSDK boots with skip_lib / skip_binary. */
    DEBUG_UNCOMPILED;

    fun envValue(): String = when (this) {
        RELEASE          -> "release"
        DEBUG_UNCOMPILED -> "debug_uncompiled"
    }

    companion object {
        /** Inverse of [envValue]. Unknown values fall back to [RELEASE]. */
        fun fromEnvValue(value: String?): GameMode = when (value) {
            "debug_uncompiled" -> DEBUG_UNCOMPILED
            else               -> RELEASE
        }

        /**
         * The mode a project runs in: the one chosen for it, except that a
         * compiled release has no Ruby sources to run in Debug.
         */
        fun forProject(chosen: GameMode, compiledRelease: Boolean): GameMode =
            if (compiledRelease) RELEASE else chosen
    }
}
