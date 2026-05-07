#ifndef PSDK_SHA256_H
#define PSDK_SHA256_H

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

#define SHA256_DIGEST_SIZE 32
#define SHA256_BLOCK_SIZE  64

typedef struct {
    uint32_t state[8];
    uint64_t bitlen;
    uint8_t  buffer[SHA256_BLOCK_SIZE];
    size_t   buflen;
} sha256_ctx;

void sha256_init(sha256_ctx *ctx);
void sha256_update(sha256_ctx *ctx, const uint8_t *data, size_t len);
void sha256_final(sha256_ctx *ctx, uint8_t out[SHA256_DIGEST_SIZE]);
void sha256_digest(const uint8_t *data, size_t len, uint8_t out[SHA256_DIGEST_SIZE]);

#ifdef __cplusplus
}
#endif

#endif
