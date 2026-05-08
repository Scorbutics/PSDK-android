package com.psdk.compilation

import android.content.Context
import com.psdk.ruby.vm.ArchiveKeys
import com.psdk.zip.EpsaArchive
import java.io.File

/**
 * Validates a candidate .epsa archive and resolves its decryption keys.
 *
 * v4-streaming flow: no on-disk decryption. Validation = header parse +
 * first-chunk HMAC verification (proves the cert lines up and the bundle
 * isn't tampered). The deeper "is this a valid PSDK archive" structural
 * check (pokemonsdk/, Game.rb) now happens at compile time when the Ruby
 * side mounts via EpsaStream — there's no decrypted ZIP to inspect from
 * Java.
 */
object ArchiveValidator {
    data class ValidationResult(
        val isValid: Boolean,
        val error: String?,
        val archive: ArchiveKeys?
    )

    fun validate(context: Context, stagingFile: File): ValidationResult {
        if (!stagingFile.exists() || !stagingFile.canRead()) {
            return ValidationResult(false, "File does not exist or is not readable", null)
        }

        if (!EpsaArchive.isEpsaFile(stagingFile)) {
            return ValidationResult(false, "Only encrypted .epsa archives are supported.", null)
        }

        return when (val r = EpsaArchive.resolve(context, stagingFile)) {
            is EpsaArchive.Result.Failure -> ValidationResult(false, r.message, null)
            is EpsaArchive.Result.Ok      -> ValidationResult(
                true, null,
                ArchiveKeys(
                    epsaPath  = r.keys.epsaPath,
                    encKeyHex = r.keys.encKey.toHex(),
                    macKeyHex = r.keys.macKey.toHex()
                )
            )
        }
    }

    /**
     * Lightweight check used to decide whether the import wizard can skip
     * the "import a file" step. Returns valid only if the staged .epsa
     * exists and its header parses cleanly + first-chunk HMAC verifies.
     */
    fun validateExisting(context: Context, stagingFile: File): ValidationResult {
        if (!stagingFile.exists() || !stagingFile.canRead()) {
            return ValidationResult(false, null, null)
        }
        return validate(context, stagingFile)
    }

    fun getStagingFile(executionLocation: String): File = File(executionLocation, "archive.staging")

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}
