#include <jni.h>
#include <string.h>
#include <stdint.h>

#include "hkdf.h"

/*
 * KDF for EPSA archives.
 *
 * Inputs (from Kotlin):
 *   certDer     : APK signing cert DER bytes
 *   buildId     : 8 random bytes baked into the archive at encrypt time
 *   kdfVersion  : single-byte version selector (currently always 1)
 *
 * Output: 32-byte K_master suitable for AES-256.
 *
 * The salt and info-prefix constants are stored XOR'd against OBF_KEY so they
 * don't appear as readable byte runs in `strings`/`hexdump`. They are
 * deobfuscated into stack buffers for the duration of the call and zeroized
 * before return. This is friction, not real obfuscation — anyone running
 * ghidra will see the XOR loop and reverse it. The point is only to defeat
 * naive grep-based extraction.
 *
 * Must produce output byte-for-byte identical to the host Ruby implementation
 * in plugins/epsa_kdf.rb. The parity test in plugins/test_epsa_kdf.rb is the
 * gate that catches drift.
 */

static const uint8_t OBF_KEY = 0x7b;

/* Original SALT bytes (32) XOR'd against OBF_KEY. */
static const uint8_t SALT_XOR[32] = {
    0x64, 0xab, 0x5f, 0xc7, 0x69, 0x6b, 0xc6, 0xc2,
    0xd8, 0xb9, 0xcc, 0x90, 0x99, 0x54, 0x97, 0x59,
    0xd1, 0x5e, 0xef, 0x1f, 0x98, 0xd3, 0xd2, 0x89,
    0xed, 0x0a, 0x37, 0xd1, 0x98, 0xf4, 0xc0, 0xfe
};

/* Original INFO bytes ("psdk-epsa-bundle", 16) XOR'd against OBF_KEY. */
static const uint8_t INFO_XOR[16] = {
    0x0b, 0x08, 0x1f, 0x10, 0x56, 0x1e, 0x0b, 0x08,
    0x1a, 0x56, 0x19, 0x0e, 0x15, 0x1f, 0x17, 0x1e
};

#define EPSA_SALT_SIZE     32
#define EPSA_INFO_PFX_SIZE 16
#define EPSA_BUILD_ID_SIZE  8
#define EPSA_KEY_SIZE      32

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_psdk_zip_EpsaKdfNative_deriveNative(JNIEnv *env, jobject /*thiz*/,
                                              jbyteArray certDer,
                                              jbyteArray buildId,
                                              jint kdfVersion) {
    if (certDer == nullptr || buildId == nullptr) return nullptr;

    jsize cert_len = env->GetArrayLength(certDer);
    jsize bid_len  = env->GetArrayLength(buildId);
    if (bid_len != EPSA_BUILD_ID_SIZE) return nullptr;
    if (kdfVersion < 0 || kdfVersion > 255) return nullptr;

    jbyte *cert_ptr = env->GetByteArrayElements(certDer, nullptr);
    jbyte *bid_ptr  = env->GetByteArrayElements(buildId, nullptr);
    if (cert_ptr == nullptr || bid_ptr == nullptr) {
        if (cert_ptr) env->ReleaseByteArrayElements(certDer, cert_ptr, JNI_ABORT);
        if (bid_ptr)  env->ReleaseByteArrayElements(buildId, bid_ptr,  JNI_ABORT);
        return nullptr;
    }

    uint8_t salt[EPSA_SALT_SIZE];
    uint8_t info[EPSA_INFO_PFX_SIZE + 1 + EPSA_BUILD_ID_SIZE];
    uint8_t key[EPSA_KEY_SIZE];

    /* Deobfuscate salt and info prefix. */
    for (size_t i = 0; i < EPSA_SALT_SIZE; i++) {
        salt[i] = (uint8_t)(SALT_XOR[i] ^ OBF_KEY);
    }
    for (size_t i = 0; i < EPSA_INFO_PFX_SIZE; i++) {
        info[i] = (uint8_t)(INFO_XOR[i] ^ OBF_KEY);
    }
    info[EPSA_INFO_PFX_SIZE] = (uint8_t)(kdfVersion & 0xff);
    memcpy(info + EPSA_INFO_PFX_SIZE + 1, bid_ptr, EPSA_BUILD_ID_SIZE);

    int rc = hkdf_sha256(reinterpret_cast<const uint8_t *>(cert_ptr),
                         (size_t)cert_len,
                         salt, sizeof(salt),
                         info, sizeof(info),
                         key,  sizeof(key));

    /* Wipe sensitive stack buffers regardless of outcome. */
    volatile uint8_t *p = salt;
    for (size_t i = 0; i < sizeof(salt); i++) p[i] = 0;
    p = info;
    for (size_t i = 0; i < sizeof(info); i++) p[i] = 0;

    env->ReleaseByteArrayElements(certDer, cert_ptr, JNI_ABORT);
    env->ReleaseByteArrayElements(buildId, bid_ptr,  JNI_ABORT);

    if (rc != 0) {
        p = key;
        for (size_t i = 0; i < sizeof(key); i++) p[i] = 0;
        return nullptr;
    }

    jbyteArray result = env->NewByteArray(EPSA_KEY_SIZE);
    if (result == nullptr) {
        p = key;
        for (size_t i = 0; i < sizeof(key); i++) p[i] = 0;
        return nullptr;
    }
    env->SetByteArrayRegion(result, 0, EPSA_KEY_SIZE, reinterpret_cast<jbyte *>(key));

    p = key;
    for (size_t i = 0; i < sizeof(key); i++) p[i] = 0;

    return result;
}
