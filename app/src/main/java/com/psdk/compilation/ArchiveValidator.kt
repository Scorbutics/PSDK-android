package com.psdk.compilation

import com.psdk.zip.EpsaDecryptor
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

object ArchiveValidator {
    data class ValidationResult(
        val isValid: Boolean,
        val error: String?,
        val archivePath: String?
    )

    fun validate(executionLocation: String, stagingFile: File): ValidationResult {
        if (!stagingFile.exists() || !stagingFile.canRead()) {
            return ValidationResult(false, "File does not exist or is not readable", null)
        }

        if (!EpsaDecryptor.isEpsaFile(stagingFile)) {
            return ValidationResult(false, "Only encrypted .epsa archives are supported.", null)
        }

        val decryptedFile = File(executionLocation, "archive.psa")
        val error = EpsaDecryptor.decrypt(stagingFile, decryptedFile)
        if (error != null) {
            return ValidationResult(false, error, null)
        }

        return validateZipStructure(decryptedFile)
    }

    fun validateExisting(executionLocation: String): ValidationResult {
        val existingArchive = File(executionLocation, "archive.psa")
        if (!existingArchive.exists() || !existingArchive.canRead()) {
            return ValidationResult(false, null, null)
        }
        return validateZipStructure(existingArchive)
    }

    private fun validateZipStructure(file: File): ValidationResult {
        val absPath = file.absolutePath
        val filename = file.name.trim()
        if (!filename.endsWith(".psa")) {
            return ValidationResult(false, "Selected file is not a valid archive: '$filename'", null)
        }

        return try {
            ZipFile(file).use { zip ->
                when {
                    zip.getEntry("pokemonsdk/version.txt") == null ->
                        ValidationResult(false, "pokemonsdk folder not found in the archive", null)
                    zip.getEntry("Game.rb") == null ->
                        ValidationResult(false, "no Game.rb init script in the archive", null)
                    else ->
                        ValidationResult(true, null, file.absolutePath)
                }
            }
        } catch (e: IOException) {
            ValidationResult(false, "Error while reading the archive: ${e.localizedMessage}", null)
        }
    }

    fun getStagingFile(executionLocation: String): File = File(executionLocation, "archive.staging")
    fun getArchiveFile(executionLocation: String): File = File(executionLocation, "archive.psa")
}
