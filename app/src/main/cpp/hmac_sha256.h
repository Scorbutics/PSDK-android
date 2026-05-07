#ifndef PSDK_HMAC_SHA256_H
#define PSDK_HMAC_SHA256_H

#include "sha256.h"

#ifdef __cplusplus
extern "C" {
#endif

void hmac_sha256(const uint8_t *key, size_t key_len,
                 const uint8_t *msg, size_t msg_len,
                 uint8_t out[SHA256_DIGEST_SIZE]);

#ifdef __cplusplus
}
#endif

#endif
