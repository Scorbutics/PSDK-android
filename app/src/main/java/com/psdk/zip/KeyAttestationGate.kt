package com.psdk.zip

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.psdk.BuildConfig
import org.bouncycastle.asn1.ASN1Boolean
import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1Enumerated
import org.bouncycastle.asn1.ASN1InputStream
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.ASN1TaggedObject
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * Hardware-backed device integrity gate, run before EPSA decryption.
 *
 * Strategy:
 *   1. Generate an ephemeral EC key in AndroidKeyStore with an attestation
 *      challenge. The keystore returns a cert chain rooted in Google's
 *      hardware attestation CA.
 *   2. Verify the chain links + that the root matches one of Google's
 *      published attestation root keys (bundled at assets/google_attestation_roots.pem).
 *   3. Parse the leaf cert's attestation extension (OID 1.3.6.1.4.1.11129.2.1.17)
 *      and require:
 *        - attestationSecurityLevel >= TrustedEnvironment
 *        - verifiedBootState != Failed
 *   4. Cache pass/fail for 30 days, signed by a separate HMAC key kept in
 *      AndroidKeyStore so an attacker can't poison the cache file.
 *
 * Cost: free, offline after first run, no Google Play Console required.
 *
 * Bypass surface: patching the Kotlin gate logic, or compromising the device's
 * TEE itself. Both are independent of the native KDF, so an attacker has to
 * defeat both layers separately.
 */
object KeyAttestationGate {
    private const val TAG = "KeyAttestationGate"

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val ATTESTATION_KEY_ALIAS = "psdk_attestation_ephemeral"
    private const val CACHE_HMAC_KEY_ALIAS = "psdk_attestation_cache_hmac"

    private const val CACHE_FILE = "attestation_verdict.bin"
    private const val CACHE_VALIDITY_MS = 30L * 24 * 60 * 60 * 1000  // 30 days
    private const val CACHE_VERSION: Byte = 1

    // OID for Android Key Attestation extension (KeyDescription).
    private const val KEY_DESCRIPTION_OID = "1.3.6.1.4.1.11129.2.1.17"

    // attestationSecurityLevel enum
    private const val SECURITY_SOFTWARE = 0
    private const val SECURITY_TEE      = 1
    private const val SECURITY_STRONGBOX = 2

    // verifiedBootState enum
    private const val BOOT_VERIFIED   = 0
    private const val BOOT_SELF_SIGNED = 1
    private const val BOOT_UNVERIFIED = 2
    private const val BOOT_FAILED     = 3

    // RootOfTrust is at tag [704] inside teeEnforced AuthorizationList
    private const val TAG_ROOT_OF_TRUST = 704

    sealed class Result {
        object Pass : Result()
        data class Fail(val reason: String) : Result()
    }

    /**
     * Run the gate (with cache). Returns Pass on success, Fail with a reason otherwise.
     *
     * Debug builds (BuildConfig.DEBUG == true) skip attestation entirely — useful
     * for emulator testing where Keystore is software-only and would always fail.
     * The bypass is logged loudly so it can never silently leak into a release
     * build (release builds have BuildConfig.DEBUG == false).
     */
    fun check(context: Context): Result {
        if (BuildConfig.DEBUG) {
            Log.w(TAG, "DEBUG BUILD: skipping Key Attestation gate. " +
                    "This must NEVER happen in a release build.")
            return Result.Pass
        }

        readCachedVerdict(context)?.let { return it }

        val verdict = try {
            attestFresh(context)
        } catch (e: Exception) {
            Log.w(TAG, "attestation failed", e)
            Result.Fail("attestation_exception:${e.javaClass.simpleName}")
        }

        writeCachedVerdict(context, verdict)
        return verdict
    }

    // -------------------------------------------------------------------------
    // Fresh attestation
    // -------------------------------------------------------------------------

    private fun attestFresh(context: Context): Result {
        val challenge = ByteArray(32).also { SecureRandom().nextBytes(it) }

        // Generate an ephemeral EC keypair with attestation. Delete it immediately
        // afterwards — we don't need to keep the key, only the cert chain.
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(ATTESTATION_KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAttestationChallenge(challenge)
            .build()
        kpg.initialize(spec)
        kpg.generateKeyPair()

        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val chain = keyStore.getCertificateChain(ATTESTATION_KEY_ALIAS)
            ?: return Result.Fail("no_cert_chain")

        // Drop the ephemeral attestation key — we have what we need.
        try {
            keyStore.deleteEntry(ATTESTATION_KEY_ALIAS)
        } catch (_: Exception) { /* ignore */ }

        if (chain.isEmpty()) return Result.Fail("empty_cert_chain")

        val x509Chain = chain.map { it as X509Certificate }

        // Verify chain links and that the root matches one of Google's attestation roots.
        verifyChainSignatures(x509Chain)?.let { return Result.Fail(it) }
        verifyRootIsGoogle(context, x509Chain.last())?.let { return Result.Fail(it) }

        // Parse leaf attestation extension.
        val keyDescription = x509Chain.first().getExtensionValue(KEY_DESCRIPTION_OID)
            ?: return Result.Fail("no_key_description_extension")

        val parsed = parseKeyDescription(keyDescription)
            ?: return Result.Fail("malformed_key_description")

        if (parsed.attestationSecurityLevel < SECURITY_TEE) {
            return Result.Fail("software_only_security_level")
        }
        if (parsed.verifiedBootState != null && parsed.verifiedBootState == BOOT_FAILED) {
            return Result.Fail("verified_boot_failed")
        }

        return Result.Pass
    }

    // -------------------------------------------------------------------------
    // Cert chain verification
    // -------------------------------------------------------------------------

    private fun verifyChainSignatures(chain: List<X509Certificate>): String? {
        for (i in 0 until chain.size - 1) {
            try {
                chain[i].verify(chain[i + 1].publicKey)
            } catch (e: Exception) {
                return "chain_signature_invalid_at_$i:${e.javaClass.simpleName}"
            }
        }
        return null
    }

    private fun verifyRootIsGoogle(context: Context, root: X509Certificate): String? {
        val factory = CertificateFactory.getInstance("X.509")
        val bundled = try {
            context.assets.open("google_attestation_roots.pem").use { input ->
                factory.generateCertificates(input).map { it as X509Certificate }
            }
        } catch (e: Exception) {
            // Without bundled roots, we can't validate. Strict mode = fail.
            return "no_bundled_roots:${e.javaClass.simpleName}"
        }
        if (bundled.isEmpty()) return "bundled_roots_empty"

        for (g in bundled) {
            if (root.publicKey == g.publicKey) return null
        }
        return "root_not_recognized"
    }

    // -------------------------------------------------------------------------
    // ASN.1 parsing of the KeyDescription extension
    // -------------------------------------------------------------------------

    private data class KeyDescription(
        val attestationSecurityLevel: Int,
        val verifiedBootState: Int?
    )

    private fun parseKeyDescription(extValue: ByteArray): KeyDescription? {
        return try {
            // The extension value is an OCTET STRING wrapping the actual ASN.1.
            val wrapper = ASN1InputStream(ByteArrayInputStream(extValue)).use { it.readObject() }
            val payload = (wrapper as ASN1OctetString).octets
            val seq = ASN1InputStream(ByteArrayInputStream(payload)).use { it.readObject() } as ASN1Sequence

            // KeyDescription fields (since attestationVersion >= 1):
            //   0: attestationVersion           INTEGER
            //   1: attestationSecurityLevel     ENUMERATED
            //   2: keyMintVersion / keymasterVersion       INTEGER
            //   3: keyMintSecurityLevel / keymasterSecurityLevel  ENUMERATED
            //   4: attestationChallenge          OCTET_STRING
            //   5: uniqueId                      OCTET_STRING
            //   6: softwareEnforced              AuthorizationList
            //   7: teeEnforced                   AuthorizationList
            if (seq.size() < 8) return null

            val attestationSecurityLevel = (seq.getObjectAt(1) as ASN1Enumerated).value.toInt()
            val teeEnforced = seq.getObjectAt(7) as ASN1Sequence
            val verifiedBootState = extractVerifiedBootState(teeEnforced)

            KeyDescription(attestationSecurityLevel, verifiedBootState)
        } catch (e: Exception) {
            Log.w(TAG, "parseKeyDescription failed", e)
            null
        }
    }

    private fun extractVerifiedBootState(authList: ASN1Sequence): Int? {
        // AuthorizationList is a SEQUENCE of EXPLICIT-tagged optional entries. We
        // look for tag [704] which holds RootOfTrust.
        for (i in 0 until authList.size()) {
            val entry = authList.getObjectAt(i)
            if (entry is ASN1TaggedObject && entry.tagNo == TAG_ROOT_OF_TRUST) {
                val rootOfTrust = entry.`object` as? ASN1Sequence ?: continue
                // RootOfTrust ::= SEQUENCE {
                //   verifiedBootKey   OCTET_STRING,
                //   deviceLocked      BOOLEAN,
                //   verifiedBootState ENUMERATED,
                //   verifiedBootHash  OCTET_STRING (since version 3)
                // }
                if (rootOfTrust.size() < 3) return null
                return (rootOfTrust.getObjectAt(2) as ASN1Enumerated).value.toInt()
            }
        }
        return null
    }

    // -------------------------------------------------------------------------
    // Cache (HMAC-signed verdict file)
    // -------------------------------------------------------------------------

    private data class CachedVerdict(val pass: Boolean, val reason: String, val timestampMs: Long)

    private fun cacheFile(context: Context) = File(context.filesDir, CACHE_FILE)

    private fun readCachedVerdict(context: Context): Result? {
        val file = cacheFile(context)
        if (!file.exists()) return null

        val data = try { file.readBytes() } catch (e: Exception) { return null }
        if (data.size < 32 + 1 + 8 + 4) return null // minimum: hmac + version + ts + reasonLen

        val hmac = data.copyOfRange(0, 32)
        val payload = data.copyOfRange(32, data.size)
        if (!verifyCacheHmac(payload, hmac)) {
            Log.w(TAG, "cache hmac mismatch — discarding")
            return null
        }

        val buf = ByteBuffer.wrap(payload)
        val version = buf.get()
        if (version != CACHE_VERSION) return null
        val pass = buf.get() != 0.toByte()
        val timestampMs = buf.long
        val reasonLen = buf.int
        if (reasonLen < 0 || reasonLen > 4096 || buf.remaining() < reasonLen) return null
        val reasonBytes = ByteArray(reasonLen)
        buf.get(reasonBytes)
        val reason = String(reasonBytes, Charsets.UTF_8)

        if (System.currentTimeMillis() - timestampMs > CACHE_VALIDITY_MS) return null
        return if (pass) Result.Pass else Result.Fail(reason)
    }

    private fun writeCachedVerdict(context: Context, verdict: Result) {
        val pass = verdict is Result.Pass
        val reason = if (verdict is Result.Fail) verdict.reason else ""
        val reasonBytes = reason.toByteArray(Charsets.UTF_8)

        val payload = ByteBuffer.allocate(1 + 1 + 8 + 4 + reasonBytes.size).apply {
            put(CACHE_VERSION)
            put(if (pass) 1.toByte() else 0.toByte())
            putLong(System.currentTimeMillis())
            putInt(reasonBytes.size)
            put(reasonBytes)
        }.array()

        val hmac = computeCacheHmac(payload)
        try {
            cacheFile(context).writeBytes(hmac + payload)
        } catch (e: Exception) {
            Log.w(TAG, "failed to write attestation cache", e)
        }
    }

    private fun cacheHmacKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(CACHE_HMAC_KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(CACHE_HMAC_KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
        if (Build.VERSION.SDK_INT >= 28) {
            // Use StrongBox if available; falls through if not supported.
            try {
                builder.setIsStrongBoxBacked(true)
                kg.init(builder.build())
                return kg.generateKey()
            } catch (_: Exception) {
                // StrongBox not available; fall back to TEE.
                builder.setIsStrongBoxBacked(false)
            }
        }
        kg.init(builder.build())
        return kg.generateKey()
    }

    private fun computeCacheHmac(payload: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(cacheHmacKey())
        return mac.doFinal(payload)
    }

    private fun verifyCacheHmac(payload: ByteArray, expected: ByteArray): Boolean {
        val actual = computeCacheHmac(payload)
        if (actual.size != expected.size) return false
        var diff = 0
        for (i in actual.indices) diff = diff or (actual[i].toInt() xor expected[i].toInt())
        return diff == 0
    }
}
