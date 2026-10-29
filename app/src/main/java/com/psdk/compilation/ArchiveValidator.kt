package com.psdk.compilation

import android.content.Context
import android.util.Log
import com.psdk.ruby.vm.ArchiveKeys
import com.psdk.ruby.vm.PsdkInterpreter
import com.psdk.ruby.vm.RubyScript
import com.psdk.ruby.vm.ScriptLocation
import com.psdk.zip.EpsaArchive
import com.scorbutics.rubyvm.LogLevel
import com.scorbutics.rubyvm.LogListener
import com.scorbutics.rubyvm.LogMessage
import java.io.File

/**
 * Validates a candidate game archive and resolves how to mount it: an
 * encrypted .epsa (decryption keys) or a plain ZIP (.psa / .zip, no keys).
 * Either may hold project sources or a compiled release (GameContent in
 * lib/game_content.rb).
 *
 * v4-streaming flow: no on-disk decryption of the bulk archive. Validation
 * is now two stages:
 *   1. .epsa: JVM-side header parse + first-chunk HMAC verification —
 *      proves the cert/KDF/build line up and the bundle isn't tampered.
 *      Plain ZIP: magic check only.
 *   2. A short Ruby pass (extract_loader_assets.rb) that mounts the archive
 *      via EpsaStream, copies splash-time assets (game info / icon / a font)
 *      into <executionLocation>/loader_assets/, and unmounts. This is both
 *      an end-to-end smoke test of the streaming decrypter AND the source
 *      of files loader.rb later reads off the filesystem (so loader.rb
 *      itself has zero archive dependency).
 *
 * The Ruby pass treats "no font in Fonts/" as archive-invalid — without
 * one, LiteRGSS::Text raises and the splash can't render its title bar.
 */
object ArchiveValidator {
    private const val TAG = "ArchiveValidator"
    private const val EXTRACT_SCRIPT = "extract_loader_assets.rb"
    /**
     * What extract_loader_assets.rb writes into loader_assets/extract_version
     * (its EXTRACT_VERSION, keep both in sync). Bumped when the written files
     * change: 2 adds the compiled_release marker.
     */
    private const val LOADER_ASSETS_VERSION = "2"
    private const val EXTRACT_TIMEOUT_MS = 120_000L
    const val NOT_AN_ARCHIVE = "Not a game archive: expected an .epsa, .psa or .zip file."

    data class ValidationResult(
        val isValid: Boolean,
        val error: String?,
        val archive: ArchiveKeys?
    )

    fun validate(context: Context, stagingFile: File, executionLocation: String): ValidationResult {
        if (!stagingFile.exists() || !stagingFile.canRead()) {
            return ValidationResult(false, "File does not exist or is not readable", null)
        }

        val keys = when (val r = resolveArchive(context, stagingFile)) {
            is Resolution.Failure -> return ValidationResult(false, r.message, null)
            is Resolution.Ok      -> r.keys
        }

        val extraction = runLoaderAssetExtraction(context, executionLocation, keys)
        if (!extraction.success) {
            return ValidationResult(false, extraction.errorSummary(), null)
        }

        return ValidationResult(true, null, keys)
    }

    /**
     * Lightweight check used to decide whether the import wizard can skip
     * the "import a file" step. Returns valid only if the staged .epsa
     * exists, its header parses cleanly + first-chunk HMAC verifies, and
     * the loader assets extract successfully.
     *
     * Fast path: if `loader_assets/` is present and at least as new as the
     * staging file, the previous extraction is still authoritative — we
     * skip the Ruby pass entirely and only re-resolve the keys from the
     * header. That keeps the returning-user click on Compile snappy
     * (extract_loader_assets.rb is ~2s).
     */
    fun validateExisting(context: Context, stagingFile: File, executionLocation: String): ValidationResult {
        if (!stagingFile.exists() || !stagingFile.canRead()) {
            return ValidationResult(false, null, null)
        }
        if (!loaderAssetsFresh(executionLocation, stagingFile)) {
            return validate(context, stagingFile, executionLocation)
        }
        return when (val r = resolveArchive(context, stagingFile)) {
            is Resolution.Failure -> ValidationResult(false, r.message, null)
            is Resolution.Ok      -> ValidationResult(true, null, r.keys)
        }
    }

    fun getStagingFile(executionLocation: String): File = File(executionLocation, "archive.staging")

    /**
     * Header-parse + KDF only, no extraction. Used by the wizard's
     * classic-bootstrap path where loader.rb handles extract_loader_assets.rb
     * itself in-process — the activity just needs the keys to bind via
     * env vars before launching the loader interpreter. Returns null if
     * the file isn't a game archive or the KDF refuses (e.g. cert mismatch).
     */
    fun resolveKeys(context: Context, stagingFile: File): ArchiveKeys? {
        if (!stagingFile.exists() || !stagingFile.canRead()) return null
        return (resolveArchive(context, stagingFile) as? Resolution.Ok)?.keys
    }

    /** True if the file is an archive the app can mount (.epsa or plain ZIP). */
    fun isGameArchive(file: File): Boolean = EpsaArchive.isEpsaFile(file) || EpsaArchive.isZipFile(file)

    private sealed class Resolution {
        data class Ok(val keys: ArchiveKeys) : Resolution()
        data class Failure(val message: String) : Resolution()
    }

    private fun resolveArchive(context: Context, file: File): Resolution {
        if (EpsaArchive.isZipFile(file)) {
            return Resolution.Ok(ArchiveKeys(path = file.absolutePath))
        }
        if (!EpsaArchive.isEpsaFile(file)) {
            return Resolution.Failure(NOT_AN_ARCHIVE)
        }
        return when (val r = EpsaArchive.resolve(context, file)) {
            is EpsaArchive.Result.Failure -> Resolution.Failure(r.message)
            is EpsaArchive.Result.Ok      -> Resolution.Ok(
                ArchiveKeys(
                    path      = r.keys.epsaPath,
                    encKeyHex = r.keys.encKey.toHex(),
                    macKeyHex = r.keys.macKey.toHex()
                )
            )
        }
    }

    /**
     * True iff `loader_assets/` holds every file extract_loader_assets.rb
     * writes on success, and each was last modified no earlier than the
     * staging file. icon_color.json is intentionally excluded: the script
     * skips it on all-transparent icons, so its absence does not mean the
     * extraction was incomplete. Assets written by another version of the
     * script (extract_version, see LOADER_ASSETS_VERSION) are stale too.
     */
    private fun loaderAssetsFresh(executionLocation: String, stagingFile: File): Boolean {
        val dir = File(executionLocation, "loader_assets")
        if (!dir.isDirectory) return false
        val version = File(dir, "extract_version")
        if (!version.isFile || version.readText().trim() != LOADER_ASSETS_VERSION) return false
        val required = listOf("infos_config.json", "game.png", "game_font.ttf")
        val files = required.map { File(dir, it) }
        if (!files.all { it.exists() && it.length() > 0 }) return false
        val stagingMtime = stagingFile.lastModified()
        return files.all { it.lastModified() >= stagingMtime }
    }

    private data class ExtractionOutcome(
        val success: Boolean,
        val exitCode: Int,
        val errorLines: List<String>
    ) {
        fun errorSummary(): String {
            val tail = errorLines.lastOrNull()
            return if (tail.isNullOrBlank())
                "Loader asset extraction failed (exit=$exitCode)"
            else
                "Loader asset extraction failed: $tail"
        }
    }

    private fun runLoaderAssetExtraction(
        context: Context,
        executionLocation: String,
        keys: ArchiveKeys
    ): ExtractionOutcome {
        val errorLines = mutableListOf<String>()
        val done = Object()
        var finishedExitCode: Int? = null

        val listener = object : LogListener {
            override fun onLogMessage(logMessage: LogMessage) {
                val msg = logMessage.message
                if (logMessage.level === LogLevel.ERROR) {
                    Log.w(TAG, msg)
                    errorLines += msg
                } else {
                    Log.i(TAG, msg)
                }
            }
        }

        val interpreter = PsdkInterpreter.create(
            context = context,
            listener = listener,
            onError = { e ->
                Log.w(TAG, "extraction interpreter error", e)
                errorLines += (e.localizedMessage ?: e.toString())
            }
        )

        try {
            interpreter.enqueue(
                script = RubyScript(context.assets, EXTRACT_SCRIPT, CompilationEngine.ARCHIVE_PRELUDES),
                location = ScriptLocation(executionLocation = executionLocation, archive = keys),
                onComplete = { exitCode ->
                    synchronized(done) {
                        finishedExitCode = exitCode
                        done.notifyAll()
                    }
                }
            )

            synchronized(done) {
                if (finishedExitCode == null) done.wait(EXTRACT_TIMEOUT_MS)
            }
        } finally {
            interpreter.close()
        }

        val exitCode = finishedExitCode ?: run {
            errorLines += "Extraction timed out after ${EXTRACT_TIMEOUT_MS / 1000}s"
            -1
        }
        return ExtractionOutcome(
            success = exitCode == 0,
            exitCode = exitCode,
            errorLines = errorLines
        )
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}
