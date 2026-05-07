package com.psdk.zip

/**
 * JNI bridge to the native HKDF that produces the EPSA bundle key.
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
     * @param certDer    APK signing cert DER bytes
     * @param buildId    8 random bytes from the .epsa header (per-build)
     * @param kdfVersion 0..255; rotates the KDF derivation namespace
     * @return 32-byte K_master, or throws on invalid input
     */
    fun derive(certDer: ByteArray, buildId: ByteArray, kdfVersion: Int): ByteArray {
        require(buildId.size == 8) { "buildId must be exactly 8 bytes (got ${buildId.size})" }
        require(kdfVersion in 0..255) { "kdfVersion out of byte range: $kdfVersion" }
        return deriveNative(certDer, buildId, kdfVersion)
            ?: throw IllegalStateException("Native KDF failed")
    }

    private external fun deriveNative(certDer: ByteArray, buildId: ByteArray, kdfVersion: Int): ByteArray?
}
