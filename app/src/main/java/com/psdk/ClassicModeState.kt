package com.psdk

/**
 * Shared-preference keys and state values for the classic-mode setup
 * lifecycle. Previously colocated with ClassicSetupActivity; the setup
 * activity itself is gone (the compile wizard handles first-launch
 * setup now), but the prefs schema is unchanged so installed apps keep
 * working across the upgrade.
 *
 * Lifecycle (read in EntryActivity):
 *   STATE_NEEDS_SETUP    → launch RootActivity in classic-wizard mode
 *                          (CompileWizardFragment with classicBootstrap)
 *   STATE_SETUP_COMPLETE → flip to STATE_READY, then launch the game
 *   STATE_READY          → launch the game directly
 *
 * The wizard writes STATE_SETUP_COMPLETE itself (plus VERSION_CODE) when
 * compilation succeeds while in classic-bootstrap mode — see
 * CompileWizardFragment.onViewCreated.
 */
object ClassicModeState {
    const val PREFS = "CLASSIC_MODE"
    const val STATE_KEY = "CLASSIC_MODE_STATE"
    const val VERSION_CODE_KEY = "VERSION_CODE"

    const val STATE_NEEDS_SETUP = "NEEDS_SETUP"
    const val STATE_SETUP_COMPLETE = "SETUP_COMPLETE"
    const val STATE_READY = "READY"
}
