#include "hmac_sha256.h"
#include <string.h>

void hmac_sha256(const uint8_t *key, size_t key_len,
                 const uint8_t *msg, size_t msg_len,
                 uint8_t out[SHA256_DIGEST_SIZE]) {
    uint8_t k_pad[SHA256_BLOCK_SIZE];
    uint8_t k_hash[SHA256_DIGEST_SIZE];
    sha256_ctx ctx;
    int i;

    if (key_len > SHA256_BLOCK_SIZE) {
        sha256_digest(key, key_len, k_hash);
        key = k_hash;
        key_len = SHA256_DIGEST_SIZE;
    }

    memset(k_pad, 0, SHA256_BLOCK_SIZE);
    memcpy(k_pad, key, key_len);
    for (i = 0; i < SHA256_BLOCK_SIZE; i++) k_pad[i] ^= 0x36;
    sha256_init(&ctx);
    sha256_update(&ctx, k_pad, SHA256_BLOCK_SIZE);
    sha256_update(&ctx, msg, msg_len);
    sha256_final(&ctx, out);

    memset(k_pad, 0, SHA256_BLOCK_SIZE);
    memcpy(k_pad, key, key_len);
    for (i = 0; i < SHA256_BLOCK_SIZE; i++) k_pad[i] ^= 0x5c;
    sha256_init(&ctx);
    sha256_update(&ctx, k_pad, SHA256_BLOCK_SIZE);
    sha256_update(&ctx, out, SHA256_DIGEST_SIZE);
    sha256_final(&ctx, out);

    memset(k_pad, 0, SHA256_BLOCK_SIZE);
    memset(k_hash, 0, SHA256_DIGEST_SIZE);
}
