package com.psdk

import android.util.Log
import com.android.apksig.ApkSigner
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.nio.file.Path
import java.security.*
import java.security.cert.X509Certificate
import java.util.*
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.name
import kotlin.io.path.outputStream

class Signer(
    private val signingOptions: SigningOptions
) {
    companion object {
        const val BouncyCastleKeystoreType = "BKS"
        const val BouncyCastleProvider = "BC"
    }
    private val passwordCharArray = signingOptions.password.toCharArray()
    private fun newKeystore(out: Path) {
        val (publicKey, privateKey) = createKey()
        val privateKS = KeyStore.getInstance(BouncyCastleKeystoreType, BouncyCastleProvider)
        privateKS.load(null, passwordCharArray)
        privateKS.setKeyEntry("alias", privateKey, passwordCharArray, arrayOf(publicKey))
        out.outputStream().use { stream -> privateKS.store(stream, passwordCharArray) }
    }

    private fun createKey(): Pair<X509Certificate, PrivateKey> {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(4096)
        val pair = gen.generateKeyPair()
        var serialNumber: BigInteger
        do serialNumber = BigInteger.valueOf(SecureRandom().nextLong()) while (serialNumber < BigInteger.ZERO)
        val x500Name = X500Name("CN=${signingOptions.signerName}")
        val builder = X509v3CertificateBuilder(
            x500Name,
            serialNumber,
            Date(System.currentTimeMillis() - 1000L * 60L * 60L * 24L * 30L),
            Date(System.currentTimeMillis() + 1000L * 60L * 60L * 24L * 366L * 30L),
            Locale.ENGLISH,
            x500Name,
            SubjectPublicKeyInfo.getInstance(pair.public.encoded)
        )
        val signer: ContentSigner = JcaContentSignerBuilder("SHA256withRSA").build(pair.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer)) to pair.private
    }

    private fun loadKeystore(): KeyStore {
        val ks = signingOptions.keyStoreFilePath
        if (!ks.exists()) newKeystore(ks) else {
            Log.i("Signer", "Found existing keystore: ${ks.name}")
        }

        Security.addProvider(BouncyCastleProvider())
        val keyStore = KeyStore.getInstance(BouncyCastleKeystoreType, BouncyCastleProvider)
        ks.inputStream().use { keyStore.load(it, null) }
        return keyStore
    }

    fun canUnlock(): Boolean {
        val keyStore = loadKeystore()
        val alias = keyStore.aliases().nextElement()

        try {
            keyStore.getKey(alias, passwordCharArray)
        } catch (_: UnrecoverableKeyException) {
            return false
        }

        return true
    }

    fun signApk(input: File, output: File) {
        val keyStore = loadKeystore()
        val alias = keyStore.aliases().nextElement()

        val config = ApkSigner.SignerConfig.Builder(
            signingOptions.signerName,
            keyStore.getKey(alias, passwordCharArray) as PrivateKey,
            listOf(keyStore.getCertificate(alias) as X509Certificate)
        ).build()

        val signer = ApkSigner.Builder(listOf(config))
        signer.setCreatedBy(signingOptions.signerName)
        signer.setInputApk(input)
        signer.setOutputApk(output)

        signer.build().sign()
    }
}