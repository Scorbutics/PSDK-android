package com.psdk.zip

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Decrypts .epsa (Encrypted PSDK Source Archive) files into plain ZIP data.
 *
 * File format:
 *   [4 bytes: magic "PSAE"]
 *   [4 bytes: version uint32 LE]
 *   [16 bytes: IV]
 *   [rest: AES-256-CBC encrypted payload]
 *
 * Encrypted payload:
 *   v1: raw ZIP data
 *   v2: [32 bytes: SHA-256 hash of ZIP data] + [ZIP data]
 */
object EpsaDecryptor {
    private const val MAGIC = "PSAE"
    private const val HEADER_SIZE = 24 // 4 (magic) + 4 (version) + 16 (IV)
    private const val IV_SIZE = 16
    private const val SHA256_SIZE = 32
    private const val MAX_SUPPORTED_VERSION = 2

    private val ENCRYPTION_KEY = hexStringToByteArray("1f24dd020fb077983c537dd29af01b9188406ce835bca75567b54db9be9f83f9")

    /**
     * Checks if the given file is an encrypted .epsa archive by reading its magic bytes.
     */
    fun isEpsaFile(file: File): Boolean {
        if (!file.exists() || file.length() < HEADER_SIZE) return false
        FileInputStream(file).use { fis ->
            val magic = ByteArray(4)
            if (fis.read(magic) != 4) return false
            return String(magic, Charsets.US_ASCII) == MAGIC
        }
    }

    /**
     * Decrypts an .epsa file and writes the resulting ZIP data to the output file.
     * @param epsaFile the encrypted archive
     * @param outputFile the destination for the decrypted ZIP
     * @return null on success, or an error message string
     */
    fun decrypt(epsaFile: File, outputFile: File): String? {
        if (!epsaFile.exists() || !epsaFile.canRead()) {
            return "Encrypted archive not found or not readable: ${epsaFile.path}"
        }
        if (epsaFile.length() < HEADER_SIZE) {
            return "File too small to be a valid .epsa archive"
        }

        try {
            BufferedInputStream(FileInputStream(epsaFile)).use { input ->
                // Read and verify magic
                val header = ByteArray(HEADER_SIZE)
                if (input.read(header) != HEADER_SIZE) {
                    return "Failed to read archive header"
                }

                val magic = String(header, 0, 4, Charsets.US_ASCII)
                if (magic != MAGIC) {
                    return "Invalid archive: expected magic '$MAGIC', got '$magic'"
                }

                // Read version (uint32 little-endian)
                val version = (header[4].toInt() and 0xFF) or
                        ((header[5].toInt() and 0xFF) shl 8) or
                        ((header[6].toInt() and 0xFF) shl 16) or
                        ((header[7].toInt() and 0xFF) shl 24)

                if (version < 1 || version > MAX_SUPPORTED_VERSION) {
                    return "Unsupported archive version: $version (supported: 1-$MAX_SUPPORTED_VERSION)"
                }

                // Read IV from header
                val iv = header.copyOfRange(8, 8 + IV_SIZE)

                val secretKey = SecretKeySpec(ENCRYPTION_KEY, "AES")
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))

                val hasIntegrityCheck = version >= 2
                val digest = if (hasIntegrityCheck) MessageDigest.getInstance("SHA-256") else null
                var expectedHash: ByteArray? = null
                // Buffer to accumulate the first 32 decrypted bytes (the hash) for v2
                var hashBuf: ByteArray? = if (hasIntegrityCheck) ByteArray(SHA256_SIZE) else null
                var hashBufFilled = 0

                // Stream decrypt → write to file + feed SHA-256 digest
                BufferedOutputStream(FileOutputStream(outputFile)).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int

                    fun processDecrypted(data: ByteArray) {
                        var offset = 0
                        // If we haven't extracted the full hash prefix yet, peel it off
                        if (hashBuf != null && hashBufFilled < SHA256_SIZE) {
                            val needed = SHA256_SIZE - hashBufFilled
                            val toCopy = minOf(needed, data.size)
                            System.arraycopy(data, 0, hashBuf!!, hashBufFilled, toCopy)
                            hashBufFilled += toCopy
                            offset = toCopy
                            if (hashBufFilled == SHA256_SIZE) {
                                expectedHash = hashBuf!!.copyOf()
                            }
                        }
                        // Write remaining bytes (actual ZIP data) to file and digest
                        if (offset < data.size) {
                            output.write(data, offset, data.size - offset)
                            digest?.update(data, offset, data.size - offset)
                        }
                    }

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        val decrypted = cipher.update(buffer, 0, bytesRead)
                        if (decrypted != null && decrypted.isNotEmpty()) {
                            processDecrypted(decrypted)
                        }
                    }
                    val finalBlock = cipher.doFinal()
                    if (finalBlock != null && finalBlock.isNotEmpty()) {
                        processDecrypted(finalBlock)
                    }
                }

                // Verify integrity for v2
                if (hasIntegrityCheck) {
                    if (expectedHash == null) {
                        return "Decrypted payload too small to contain integrity hash"
                    }
                    val actualHash = digest!!.digest()
                    if (!expectedHash!!.contentEquals(actualHash)) {
                        return "Integrity check failed: archive is corrupted (SHA-256 mismatch). " +
                                "Expected ${expectedHash!!.toHexString()}, got ${actualHash.toHexString()}"
                    }
                }
            }

            return null
        } catch (e: javax.crypto.BadPaddingException) {
            return "Decryption failed: invalid key or corrupted archive"
        } catch (e: javax.crypto.IllegalBlockSizeException) {
            return "Decryption failed: corrupted archive data"
        } catch (e: IOException) {
            return "I/O error during decryption: ${e.localizedMessage}"
        } catch (e: Exception) {
            return "Decryption error: ${e.localizedMessage}"
        }
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x".format(it) }

    private fun hexStringToByteArray(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
    }
}
