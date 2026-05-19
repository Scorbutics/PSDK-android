package com.psdk.compilation

/**
 * Friendly one-liner derived from a raw compile log message. `label` is
 * the human-readable status text; `fraction` is the 0..1 progress within
 * the "Compilation" step (used by progress bars that want a finer
 * grain than the four high-level engine steps).
 */
data class CompileSubStep(val label: String, val fraction: Float)

/**
 * Maps raw `CompilationEngine.CompilationCallback#onLogMessage` lines to
 * the user-facing sub-step they imply. Used by
 * [com.psdk.ui.wizard.CompileProgressFragment] — writes the label to a
 * small status file the loader.rb splash polls each frame, so the GL
 * surface's `status_text` is the only on-screen UI during compile.
 * (Classic-mode first-launch setup goes through the same fragment now —
 * see CompileWizardFragment's ARG_CLASSIC_BOOTSTRAP path.)
 *
 * Returns null when the message doesn't trigger a sub-step change —
 * callers should leave the previous status in place.
 *
 * Fractions are calibrated from observed compile timings (~56s total):
 * loading dominates the first half, graphics + audio copy dominate the
 * rest.
 */
object CompileSubStepMapper {
    fun fromLog(message: String): CompileSubStep? = when {
        message.startsWith("Loading Game")                       -> CompileSubStep("Loading game data...",       0.00f)
        message.startsWith("Loading /data/")                     -> CompileSubStep("Loading project scripts...", 0.05f)
        message.startsWith("Progress: Start script compilation") -> CompileSubStep("Compiling scripts...",       0.45f)
        message.startsWith("Progress: Make graphics resources")  -> CompileSubStep("Processing graphics...",     0.55f)
        message.startsWith("Progress: Make data")                -> CompileSubStep("Building data files...",     0.80f)
        message.startsWith("Progress: Copy lib")                 -> CompileSubStep("Copying libraries...",       0.82f)
        message.startsWith("Progress: Copy audio")               -> CompileSubStep("Copying audio assets...",    0.84f)
        message.startsWith("Progress: Copy binaries")            -> CompileSubStep("Copying binaries...",        0.98f)
        message.startsWith("Compilation done!")                  -> CompileSubStep("Finalizing...",              1.00f)
        else -> null
    }
}
