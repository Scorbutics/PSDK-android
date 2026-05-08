package com.psdk.zip

/**
 * JNI bridge to the native HKDF that produces the EPSA bundle keys.
 *
 * The full derivation runs in libepsakdf.so; Kotlin only marshals inputs and
 * outputs across the boundary. The companion host implementation lives in
 * plugins/epsa_kdf.rb and must produce byte-identical output.
 */
object EpsaKdfNative {

    init {
        System.loadLibrary("epsakdf")
    }

    /**
     * v4 dual-key derivation. Returns Pair(K_enc, K_mac), each 32 bytes.
     * Native side runs HKDF twice with distinct info-suffix tags so the
     * two keys are independent.
     */
    fun deriveV4(certDer: ByteArray, buildId: ByteArray, kdfVersion: Int): Pair<ByteArray, ByteArray> {
        require(buildId.size == 8) { "buildId must be exactly 8 bytes (got ${buildId.size})" }
        require(kdfVersion in 0..255) { "kdfVersion out of byte range: $kdfVersion" }
        val both = deriveV4Native(certDer, buildId, kdfVersion)
            ?: throw IllegalStateException("Native KDF v4 failed")
        check(both.size == 64) { "Expected 64 bytes from native KDF v4, got ${both.size}" }
        return both.copyOfRange(0, 32) to both.copyOfRange(32, 64)
    }

    private external fun deriveV4Native(certDer: ByteArray, buildId: ByteArray, kdfVersion: Int): ByteArray?
}
