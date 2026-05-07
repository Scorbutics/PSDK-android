#include "hkdf.h"
#include "hmac_sha256.h"
#include "sha256.h"
#include <string.h>

#define HKDF_MAX_INFO_LEN 256

int hkdf_sha256(const uint8_t *ikm, size_t ikm_len,
                const uint8_t *salt, size_t salt_len,
                const uint8_t *info, size_t info_len,
                uint8_t *out, size_t out_len) {
    uint8_t prk[SHA256_DIGEST_SIZE];
    uint8_t t_block[SHA256_DIGEST_SIZE];
    uint8_t zero_salt[SHA256_DIGEST_SIZE];
    uint8_t buf[SHA256_DIGEST_SIZE + HKDF_MAX_INFO_LEN + 1];
    size_t  pos = 0;
    size_t  t_len = 0;
    uint8_t ctr;

    if (out_len > 255 * SHA256_DIGEST_SIZE) return -1;
    if (info_len > HKDF_MAX_INFO_LEN) return -1;

    /* RFC 5869: if salt is empty, use HashLen zero bytes. */
    if (salt == NULL || salt_len == 0) {
        memset(zero_salt, 0, sizeof(zero_salt));
        salt = zero_salt;
        salt_len = SHA256_DIGEST_SIZE;
    }

    /* Extract: PRK = HMAC(salt, IKM) */
    hmac_sha256(salt, salt_len, ikm, ikm_len, prk);

    /* Expand: T(0) = empty; T(i) = HMAC(PRK, T(i-1) || info || i) */
    for (ctr = 1; pos < out_len; ctr++) {
        size_t buf_len = 0;
        if (t_len > 0) {
            memcpy(buf, t_block, t_len);
            buf_len = t_len;
        }
        if (info_len > 0) {
            memcpy(buf + buf_len, info, info_len);
            buf_len += info_len;
        }
        buf[buf_len++] = ctr;

        hmac_sha256(prk, SHA256_DIGEST_SIZE, buf, buf_len, t_block);
        t_len = SHA256_DIGEST_SIZE;

        size_t copy_len = (out_len - pos < SHA256_DIGEST_SIZE) ? (out_len - pos) : SHA256_DIGEST_SIZE;
        memcpy(out + pos, t_block, copy_len);
        pos += copy_len;
    }

    memset(prk, 0, sizeof(prk));
    memset(t_block, 0, sizeof(t_block));
    memset(buf, 0, sizeof(buf));
    return 0;
}
