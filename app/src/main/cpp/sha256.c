#include "sha256.h"
#include <string.h>

static const uint32_t K[64] = {
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5,
    0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3,
    0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc,
    0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7,
    0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
    0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3,
    0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
    0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
    0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
};

static const uint32_t H_INIT[8] = {
    0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
    0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19
};

#define ROTR(x, n)   (((x) >> (n)) | ((x) << (32 - (n))))
#define CH(x, y, z)  (((x) & (y)) ^ (~(x) & (z)))
#define MAJ(x, y, z) (((x) & (y)) ^ ((x) & (z)) ^ ((y) & (z)))
#define BSIG0(x)     (ROTR(x,  2) ^ ROTR(x, 13) ^ ROTR(x, 22))
#define BSIG1(x)     (ROTR(x,  6) ^ ROTR(x, 11) ^ ROTR(x, 25))
#define SSIG0(x)     (ROTR(x,  7) ^ ROTR(x, 18) ^ ((x) >>  3))
#define SSIG1(x)     (ROTR(x, 17) ^ ROTR(x, 19) ^ ((x) >> 10))

static void sha256_transform(sha256_ctx *ctx, const uint8_t block[SHA256_BLOCK_SIZE]) {
    uint32_t W[64];
    uint32_t a, b, c, d, e, f, g, h, T1, T2;
    int t;

    for (t = 0; t < 16; t++) {
        W[t] = ((uint32_t)block[t * 4]     << 24) |
               ((uint32_t)block[t * 4 + 1] << 16) |
               ((uint32_t)block[t * 4 + 2] <<  8) |
               ((uint32_t)block[t * 4 + 3]);
    }
    for (t = 16; t < 64; t++) {
        W[t] = SSIG1(W[t - 2]) + W[t - 7] + SSIG0(W[t - 15]) + W[t - 16];
    }

    a = ctx->state[0]; b = ctx->state[1]; c = ctx->state[2]; d = ctx->state[3];
    e = ctx->state[4]; f = ctx->state[5]; g = ctx->state[6]; h = ctx->state[7];

    for (t = 0; t < 64; t++) {
        T1 = h + BSIG1(e) + CH(e, f, g) + K[t] + W[t];
        T2 = BSIG0(a) + MAJ(a, b, c);
        h = g; g = f; f = e; e = d + T1;
        d = c; c = b; b = a; a = T1 + T2;
    }

    ctx->state[0] += a; ctx->state[1] += b; ctx->state[2] += c; ctx->state[3] += d;
    ctx->state[4] += e; ctx->state[5] += f; ctx->state[6] += g; ctx->state[7] += h;
}

void sha256_init(sha256_ctx *ctx) {
    memcpy(ctx->state, H_INIT, sizeof(H_INIT));
    ctx->bitlen = 0;
    ctx->buflen = 0;
}

void sha256_update(sha256_ctx *ctx, const uint8_t *data, size_t len) {
    ctx->bitlen += (uint64_t)len * 8;
    while (len > 0) {
        size_t n = SHA256_BLOCK_SIZE - ctx->buflen;
        if (n > len) n = len;
        memcpy(ctx->buffer + ctx->buflen, data, n);
        ctx->buflen += n;
        data += n;
        len  -= n;
        if (ctx->buflen == SHA256_BLOCK_SIZE) {
            sha256_transform(ctx, ctx->buffer);
            ctx->buflen = 0;
        }
    }
}

void sha256_final(sha256_ctx *ctx, uint8_t out[SHA256_DIGEST_SIZE]) {
    uint64_t bitlen = ctx->bitlen;
    uint8_t  pad[SHA256_BLOCK_SIZE];
    uint8_t  lenbytes[8];
    size_t   pad_len;
    int i;

    pad[0] = 0x80;
    pad_len = (ctx->buflen < 56) ? (56 - ctx->buflen) : (120 - ctx->buflen);
    if (pad_len > 1) memset(pad + 1, 0, pad_len - 1);
    sha256_update(ctx, pad, pad_len);

    for (i = 0; i < 8; i++) {
        lenbytes[7 - i] = (uint8_t)(bitlen >> (i * 8));
    }
    sha256_update(ctx, lenbytes, 8);

    for (i = 0; i < 8; i++) {
        out[i * 4]     = (uint8_t)(ctx->state[i] >> 24);
        out[i * 4 + 1] = (uint8_t)(ctx->state[i] >> 16);
        out[i * 4 + 2] = (uint8_t)(ctx->state[i] >>  8);
        out[i * 4 + 3] = (uint8_t)(ctx->state[i]);
    }
}

void sha256_digest(const uint8_t *data, size_t len, uint8_t out[SHA256_DIGEST_SIZE]) {
    sha256_ctx ctx;
    sha256_init(&ctx);
    sha256_update(&ctx, data, len);
    sha256_final(&ctx, out);
}
