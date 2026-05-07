#ifndef PSDK_HKDF_H
#define PSDK_HKDF_H

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Returns 0 on success, -1 on invalid arguments. */
int hkdf_sha256(const uint8_t *ikm, size_t ikm_len,
                const uint8_t *salt, size_t salt_len,
                const uint8_t *info, size_t info_len,
                uint8_t *out, size_t out_len);

#ifdef __cplusplus
}
#endif

#endif
