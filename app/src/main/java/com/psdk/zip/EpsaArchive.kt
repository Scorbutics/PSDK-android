package com.psdk.zip

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * v4 .epsa header parser + key resolver.
 *
 * The decryption itself runs streaming in the embedded Ruby VM (see
 * lib/epsa_stream.rb + lib/archive_mount.rb). The JVM side only:
 *   1. Validates the v4 header (magic / version / consistent sizes).
 *   2. Reads the signing cert from PackageManager.
 *   3. Calls EpsaKdfNative.deriveV4 to produce K_enc and K_mac.
 *   4. Verifies the first chunk's HMAC as a cheap sanity check that
 *      everything (cert, KDF, archive integrity) lines up before we hand
 *      the bundle to Ruby.
 *
 * Format reference: PSDKTechnicalDemo/plugins/epsa_format.rb (single source
 * of truth). v4 layout is mirrored in app/src/main/ruby/lib/epsa_format.rb.
 */
object EpsaArchive {
    private const val TAG = "EpsaArchive"

    private const val MAGIC = "PSAE"
    private const val MAGIC_VERSION_SIZE = 8
    const val VERSION = 4
    const val HEADER_SIZE = 48
    const val BUILD_ID_SIZE = 8
    const val NONCE_SIZE = 16
    const val HMAC_SIZE = 32

    sealed class Result {
        data class Ok(val keys: Keys) : Result()
        data class Failure(val message: String) : Result()
    }

    /** Derived key material + the .epsa path the Ruby side will open. */
    data class Keys(
        val epsaPath: String,
        val encKey: ByteArray,
        val macKey: ByteArray
    )

    /** True if the file's magic bytes match a .epsa archive (any version). */
    fun isEpsaFile(file: File): Boolean {
        if (!file.exists() || file.length() < MAGIC_VERSION_SIZE) return false
        return file.inputStream().use { input ->
            val magic = ByteArray(4)
            input.read(magic) == 4 && String(magic, Charsets.US_ASCII) == MAGIC
        }
    }

    /**
     * Parse the v4 header, derive K_enc/K_mac, and verify the first chunk's
     * HMAC. On success returns the derived keys; on failure returns a
     * human-readable error.
     *
     * The keys live only in the returned ByteArrays. Callers that pass them
     * onward via env var should prefer to fill those env vars and discard
     * the arrays as soon as possible — see PsdkInterpreter for the handoff.
     */
    fun resolve(context: Context, epsaFile: File): Result {
        if (!epsaFile.exists() || !epsaFile.canRead()) {
            return Result.Failure("Encrypted archive not found or not readable: ${epsaFile.path}")
        }
        if (epsaFile.length() < HEADER_SIZE) {
            return Result.Failure("File too small to be a valid v$VERSION .epsa archive")
        }

        return try {
            RandomAccessFile(epsaFile, "r").use { raf ->
                val header = ByteArray(HEADER_SIZE)
                if (raf.read(header) != HEADER_SIZE) {
                    return Result.Failure("Failed to read archive header")
                }

                val magicBytes = String(header, 0, 4, Charsets.US_ASCII)
                if (magicBytes != MAGIC) {
                    return Result.Failure("Invalid archive: expected magic '$MAGIC', got '$magicBytes'")
                }

                val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                val version = bb.getInt(4)
                if (version != VERSION) {
                    return Result.Failure(
                        "Unsupported archive version: $version (expected $VERSION). " +
                                "Re-export the game with the latest exporter."
                    )
                }

                val kdfVersion = header[8].toInt() and 0xFF
                if (header[9] != 0.toByte() || header[10] != 0.toByte() || header[11] != 0.toByte()) {
                    return Result.Failure("Reserved header bytes are non-zero — archive corrupt or tampered")
                }
                val buildId = header.copyOfRange(12, 12 + BUILD_ID_SIZE)
                val chunkLog2 = bb.getInt(36)
                if (chunkLog2 !in 4..30) {
                    return Result.Failure("Implausible chunk_log2 ($chunkLog2) — archive likely corrupt")
                }
                val plaintextLen = bb.getLong(40)
                if (plaintextLen < 0) {
                    return Result.Failure("Negative plaintext length — archive corrupt")
                }

                val chunkSize = 1L shl chunkLog2
                val nChunks = (plaintextLen + chunkSize - 1) / chunkSize
                val expectedSize = HEADER_SIZE.toLong() + nChunks * HMAC_SIZE + plaintextLen
                if (raf.length() != expectedSize) {
                    return Result.Failure(
                        "Archive size ${raf.length()} does not match header expectation $expectedSize " +
                                "— file truncated or corrupt"
                    )
                }

                // Hardware-backed integrity gate — defense in depth on top of
                // the cert-bound KDF. Independent of v3/v4 archive format.
                when (val verdict = KeyAttestationGate.check(context)) {
                    is KeyAttestationGate.Result.Pass -> { /* proceed */ }
                    is KeyAttestationGate.Result.Fail -> {
                        Log.w(TAG, "Key attestation failed: ${verdict.reason}")
                        return Result.Failure("Device integrity check failed: ${verdict.reason}")
                    }
                }

                val certDer = signingCertDer(context)
                    ?: return Result.Failure("Failed to read APK signing certificate")

                val (encKey, macKey) = try {
                    EpsaKdfNative.deriveV4(certDer, buildId, kdfVersion)
                } catch (e: Throwable) {
                    Log.w(TAG, "Native v4 KDF failed: ${e.message}")
                    return Result.Failure("Native KDF failed: ${e.message}")
                }

                if (nChunks > 0) {
                    val firstChunkLen = minOf(chunkSize, plaintextLen).toInt()
                    val firstHmac = ByteArray(HMAC_SIZE)
                    raf.seek(HEADER_SIZE.toLong())
                    if (raf.read(firstHmac) != HMAC_SIZE) {
                        return Result.Failure("Failed to read first HMAC — archive truncated")
                    }
                    val firstChunkOffset = HEADER_SIZE.toLong() + nChunks * HMAC_SIZE
                    val firstChunk = ByteArray(firstChunkLen)
                    raf.seek(firstChunkOffset)
                    if (raf.read(firstChunk) != firstChunkLen) {
                        return Result.Failure("Failed to read first chunk — archive truncated")
                    }

                    val mac = Mac.getInstance("HmacSHA256")
                    mac.init(SecretKeySpec(macKey, "HmacSHA256"))
                    // Encrypt-then-MAC input: u64_le(0) || ciphertext_chunk_0
                    mac.update(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0))
                    mac.update(firstChunk)
                    val computed = mac.doFinal()

                    if (!MessageDigest.isEqual(computed, firstHmac)) {
                        return Result.Failure(
                            "First-chunk HMAC mismatch — wrong signing cert, tampered archive, " +
                                    "or .epsa from a different build"
                        )
                    }
                }

                Result.Ok(Keys(epsaPath = epsaFile.absolutePath, encKey = encKey, macKey = macKey))
            }
        } catch (e: Exception) {
            Result.Failure("Error reading .epsa: ${e.localizedMessage}")
        }
    }

    private fun signingCertDer(context: Context): ByteArray? {
        val pm = context.packageManager
        return try {
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
            null
        }
    }

    fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}
