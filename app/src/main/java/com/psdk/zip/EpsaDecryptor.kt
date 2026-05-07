package com.psdk.zip

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
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
 * Header formats:
 *   v1 / v2:
 *     [4 bytes: magic "PSAE"]
 *     [4 bytes: version uint32 LE]
 *     [16 bytes: IV]
 *   v3:
 *     [4 bytes: magic "PSAE"]
 *     [4 bytes: version uint32 LE = 3]
 *     [1 byte: kdf_version]
 *     [3 bytes: reserved]
 *     [8 bytes: build_id]
 *     [16 bytes: IV]
 *
 * Encrypted payload (all versions):
 *   v1: raw ZIP data
 *   v2/v3: [32 bytes: SHA-256 hash of ZIP data] + [ZIP data]
 *
 * Key source:
 *   v1/v2: legacy hardcoded key (deprecated; kept for backward compatibility
 *          while older archives still circulate).
 *   v3:    derived in native via EpsaKdfNative from (signing-cert DER, build_id,
 *          kdf_version), gated by KeyAttestationGate.
 */
object EpsaDecryptor {
    private const val TAG = "EpsaDecryptor"

    private const val MAGIC = "PSAE"
    private const val MAGIC_VERSION_SIZE = 8       // magic (4) + version (4)
    private const val IV_SIZE = 16
    private const val SHA256_SIZE = 32
    private const val MAX_SUPPORTED_VERSION = 3

    // v3 header tail (after the magic+version 8 bytes):
    //   [1 byte kdf_version][3 reserved][8 build_id][16 IV] = 28 bytes
    private const val V3_TAIL_SIZE = 1 + 3 + 8 + IV_SIZE
    private const val V3_BUILD_ID_SIZE = 8

    // Legacy v1/v2 hardcoded key. Removable once all in-flight v2 archives have
    // been re-exported as v3.
    private val LEGACY_KEY = hexStringToByteArray("1f24dd020fb077983c537dd29af01b9188406ce835bca75567b54db9be9f83f9")

    /**
     * Checks if the given file is an encrypted .epsa archive by reading its magic bytes.
     */
    fun isEpsaFile(file: File): Boolean {
        if (!file.exists() || file.length() < MAGIC_VERSION_SIZE) return false
        FileInputStream(file).use { fis ->
            val magic = ByteArray(4)
            if (fis.read(magic) != 4) return false
            return String(magic, Charsets.US_ASCII) == MAGIC
        }
    }

    /**
     * Decrypts an .epsa file and writes the resulting ZIP data to the output file.
     *
     * @param context    the application context (needed to read the APK signing cert and run attestation for v3)
     * @param epsaFile   the encrypted archive
     * @param outputFile the destination for the decrypted ZIP
     * @return null on success, or an error message string
     */
    fun decrypt(context: Context, epsaFile: File, outputFile: File): String? {
        if (!epsaFile.exists() || !epsaFile.canRead()) {
            return "Encrypted archive not found or not readable: ${epsaFile.path}"
        }
        if (epsaFile.length() < MAGIC_VERSION_SIZE) {
            return "File too small to be a valid .epsa archive"
        }

        try {
            BufferedInputStream(FileInputStream(epsaFile)).use { input ->
                val mvHeader = ByteArray(MAGIC_VERSION_SIZE)
                if (input.read(mvHeader) != MAGIC_VERSION_SIZE) {
                    return "Failed to read archive header"
                }

                val magic = String(mvHeader, 0, 4, Charsets.US_ASCII)
                if (magic != MAGIC) {
                    return "Invalid archive: expected magic '$MAGIC', got '$magic'"
                }

                val version = (mvHeader[4].toInt() and 0xFF) or
                        ((mvHeader[5].toInt() and 0xFF) shl 8) or
                        ((mvHeader[6].toInt() and 0xFF) shl 16) or
                        ((mvHeader[7].toInt() and 0xFF) shl 24)

                if (version < 1 || version > MAX_SUPPORTED_VERSION) {
                    return "Unsupported archive version: $version (supported: 1-$MAX_SUPPORTED_VERSION)"
                }

                val (key, iv) = when (version) {
                    1, 2 -> readLegacyKeyAndIv(input)
                    3    -> readV3KeyAndIv(context, input) ?: return "v3 key derivation failed"
                    else -> return "Unsupported archive version: $version"
                }

                val secretKey = SecretKeySpec(key, "AES")
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))

                val hasIntegrityCheck = version >= 2
                val digest = if (hasIntegrityCheck) MessageDigest.getInstance("SHA-256") else null
                var expectedHash: ByteArray? = null
                var hashBuf: ByteArray? = if (hasIntegrityCheck) ByteArray(SHA256_SIZE) else null
                var hashBufFilled = 0

                BufferedOutputStream(FileOutputStream(outputFile)).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int

                    fun processDecrypted(data: ByteArray) {
                        var offset = 0
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

                // Wipe key from memory after use.
                java.util.Arrays.fill(key, 0)
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

    private fun readLegacyKeyAndIv(input: BufferedInputStream): Pair<ByteArray, ByteArray> {
        Log.w(TAG, "Loading legacy v1/v2 .epsa archive — hardcoded key path is deprecated.")
        val iv = ByteArray(IV_SIZE)
        if (input.read(iv) != IV_SIZE) {
            throw IOException("Failed to read v1/v2 IV")
        }
        return LEGACY_KEY.copyOf() to iv
    }

    private fun readV3KeyAndIv(context: Context, input: BufferedInputStream): Pair<ByteArray, ByteArray>? {
        // Attestation gate first — if this fails, we don't even attempt to decrypt.
        when (val verdict = KeyAttestationGate.check(context)) {
            is KeyAttestationGate.Result.Pass -> { /* proceed */ }
            is KeyAttestationGate.Result.Fail -> {
                Log.w(TAG, "Key attestation failed: ${verdict.reason}")
                return null
            }
        }

        val tail = ByteArray(V3_TAIL_SIZE)
        if (input.read(tail) != V3_TAIL_SIZE) {
            Log.w(TAG, "Failed to read v3 header tail")
            return null
        }
        val kdfVersion = tail[0].toInt() and 0xFF
        // tail[1..3]: reserved, ignored
        val buildId = tail.copyOfRange(4, 4 + V3_BUILD_ID_SIZE)
        val iv = tail.copyOfRange(4 + V3_BUILD_ID_SIZE, V3_TAIL_SIZE)

        val certDer = signingCertDer(context) ?: return null

        return try {
            val key = EpsaKdfNative.derive(certDer, buildId, kdfVersion)
            key to iv
        } catch (e: Throwable) {
            Log.w(TAG, "Native KDF failed: ${e.message}")
            null
        }
    }

    private fun signingCertDer(context: Context): ByteArray? {
        val pm = context.packageManager
        val cert = try {
            if (Build.VERSION.SDK_INT >= 28) {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val signingInfo = info.signingInfo ?: return null
                val sigs = signingInfo.signingCertificateHistory
                if (sigs.isNullOrEmpty()) null else sigs[0].toByteArray()
            } else {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                val sigs = info.signatures
                if (sigs.isNullOrEmpty()) null else sigs[0].toByteArray()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read signing cert: ${e.message}")
            return null
        }

        if (cert != null) {
            // TEMPORARY DIAGNOSTIC — remove once cert-mismatch debugging is done.
            // Compare this against the host-side print to confirm both sides see
            // the same cert bytes.
            val sha256 = MessageDigest.getInstance("SHA-256").digest(cert)
            Log.i(TAG, "signing cert: size=${cert.size} sha256=${sha256.toHexString()}")
        }
        return cert
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
