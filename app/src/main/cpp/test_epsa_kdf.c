/*
 * Standalone parity test driver for the EPSA KDF.
 *
 * Compiles against the same sha256.c / hmac_sha256.c / hkdf.c files used by
 * the Android JNI library, so a passing test here proves the C implementation
 * agrees with the Ruby host implementation in plugins/epsa_kdf.rb.
 *
 * This file is NOT part of the Android build (CMakeLists.txt does not include
 * it). It is intended to be compiled with the host C compiler:
 *
 *   cc -O2 -o test_epsa_kdf \
 *      app/src/main/cpp/test_epsa_kdf.c \
 *      app/src/main/cpp/sha256.c \
 *      app/src/main/cpp/hmac_sha256.c \
 *      app/src/main/cpp/hkdf.c \
 *      -I app/src/main/cpp
 *   ./test_epsa_kdf
 *
 * Inputs and expected output must match plugins/test_epsa_kdf.rb byte-for-byte.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

#include "hkdf.h"

static const uint8_t OBF_KEY = 0x7b;

static const uint8_t SALT_XOR[32] = {
    0x64, 0xab, 0x5f, 0xc7, 0x69, 0x6b, 0xc6, 0xc2,
    0xd8, 0xb9, 0xcc, 0x90, 0x99, 0x54, 0x97, 0x59,
    0xd1, 0x5e, 0xef, 0x1f, 0x98, 0xd3, 0xd2, 0x89,
    0xed, 0x0a, 0x37, 0xd1, 0x98, 0xf4, 0xc0, 0xfe
};

static const uint8_t INFO_XOR[16] = {
    0x0b, 0x08, 0x1f, 0x10, 0x56, 0x1e, 0x0b, 0x08,
    0x1a, 0x56, 0x19, 0x0e, 0x15, 0x1f, 0x17, 0x1e
};

/* Test inputs — must match plugins/test_epsa_kdf.rb. */
static const uint8_t TEST_CERT[32] = {
    0x34, 0x08, 0x0a, 0x5a, 0xcf, 0x49, 0xc2, 0xe3,
    0x63, 0x0c, 0xa1, 0x15, 0x81, 0x14, 0xb0, 0xc8,
    0x82, 0xfe, 0x65, 0x17, 0x07, 0x85, 0x75, 0xc0,
    0x64, 0x91, 0xee, 0x00, 0xd4, 0xc0, 0xea, 0xc3
};

static const uint8_t TEST_BUILD_ID[8] = {
    0x3a, 0xe9, 0xf9, 0x68, 0x1b, 0xb4, 0x45, 0x9c
};

#define TEST_KDF_VERSION 1

/* Expected output — must match EXPECTED_KEY_HEX in test_epsa_kdf.rb. */
static const char *EXPECTED_HEX =
    "2b7d74cd611eb268e5d90bbdc03413f99151b252c0ea959c39d3eab238a03b22";

int main(void) {
    uint8_t salt[32];
    uint8_t info[16 + 1 + 8];
    uint8_t key[32];
    char    actual_hex[65];

    for (size_t i = 0; i < 32; i++) salt[i] = SALT_XOR[i] ^ OBF_KEY;
    for (size_t i = 0; i < 16; i++) info[i] = INFO_XOR[i] ^ OBF_KEY;
    info[16] = (uint8_t)TEST_KDF_VERSION;
    memcpy(info + 17, TEST_BUILD_ID, 8);

    int rc = hkdf_sha256(TEST_CERT, sizeof(TEST_CERT),
                         salt, sizeof(salt),
                         info, sizeof(info),
                         key,  sizeof(key));
    if (rc != 0) {
        fprintf(stderr, "FAIL  hkdf_sha256 returned %d\n", rc);
        return 1;
    }

    for (int i = 0; i < 32; i++) {
        snprintf(actual_hex + i * 2, 3, "%02x", key[i]);
    }
    actual_hex[64] = '\0';

    if (strcmp(actual_hex, EXPECTED_HEX) == 0) {
        printf("PASS  expected=%s\n", EXPECTED_HEX);
        printf("      actual  =%s\n", actual_hex);
        return 0;
    } else {
        fprintf(stderr, "FAIL  expected=%s\n", EXPECTED_HEX);
        fprintf(stderr, "      actual  =%s\n", actual_hex);
        return 1;
    }
}
