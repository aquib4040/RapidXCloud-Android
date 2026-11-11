/**
 * rapidxclouddrive.h — High-Security Cross-Platform Native Cryptographic Engine
 * C-ABI Headers for Linux, macOS, Windows, iOS, Android, and Desktop Integration.
 * 
 * Standards:
 * - RFC 9106 (Argon2id Password-Based Key Derivation)
 * - NIST SP 800-38D (AES-256-GCM Authenticated Encryption)
 * - RFC 5869 (HKDF-SHA256)
 */

#ifndef RAPIDXCLOUDDRIVE_H
#define RAPIDXCLOUDDRIVE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define RXC_OK 0
#define RXC_ERR_AUTH_FAILED -1
#define RXC_ERR_INVALID_PARAM -2
#define RXC_ERR_KDF_FAILED -3
#define RXC_ERR_BUFFER_TOO_SMALL -4

#define RXC_KEY_LEN 32
#define RXC_NONCE_LEN 12
#define RXC_TAG_LEN 16
#define RXC_MIN_SALT_LEN 16

/**
 * Derives a 32-byte cryptographic key from a password using Argon2id.
 * 
 * @param password_ptr Pointer to UTF-8 password bytes
 * @param password_len Length of password in bytes
 * @param salt_ptr     Pointer to salt bytes (min 16 bytes)
 * @param salt_len     Length of salt in bytes
 * @param memory_kib   Memory cost in KiB (e.g. 65536 for 64MB, 262144 for 256MB)
 * @param iterations   Time cost in iterations (min 1, recommended >= 3)
 * @param parallelism  Number of threads (1 to 16)
 * @param out_buf      Buffer to receive derived key (must be at least out_len bytes)
 * @param out_len      Length of derived key (typically 32 bytes)
 * @return RXC_OK on success, or negative error code.
 */
int32_t rxc_argon2id(
    const uint8_t *password_ptr,
    size_t password_len,
    const uint8_t *salt_ptr,
    size_t salt_len,
    uint32_t memory_kib,
    uint32_t iterations,
    uint32_t parallelism,
    uint8_t *out_buf,
    size_t out_len
);

/**
 * Encrypts plaintext using AES-256-GCM.
 * Appends 16-byte authentication tag to ciphertext.
 * 
 * @param key_ptr       32-byte AES key
 * @param key_len       Must be 32
 * @param nonce_ptr     12-byte GCM nonce
 * @param nonce_len     Must be 12
 * @param plaintext_ptr Pointer to plaintext bytes
 * @param plaintext_len Length of plaintext in bytes
 * @param aad_ptr       Pointer to Additional Authenticated Data (optional, can be NULL)
 * @param aad_len       Length of AAD in bytes (0 if NULL)
 * @param out_buf       Buffer to receive ciphertext + 16-byte tag (must be >= plaintext_len + 16)
 * @param out_buf_len   Size of out_buf in bytes
 * @param out_written   Pointer to size_t receiving number of bytes written
 * @return RXC_OK on success, or negative error code.
 */
int32_t rxc_aes_gcm_encrypt(
    const uint8_t *key_ptr,
    size_t key_len,
    const uint8_t *nonce_ptr,
    size_t nonce_len,
    const uint8_t *plaintext_ptr,
    size_t plaintext_len,
    const uint8_t *aad_ptr,
    size_t aad_len,
    uint8_t *out_buf,
    size_t out_buf_len,
    size_t *out_written
);

/**
 * Decrypts and authenticates ciphertext using AES-256-GCM.
 * Fails safely if tag, nonce, ciphertext, or AAD has been tampered with.
 * Plaintext is NEVER returned if authentication fails.
 * 
 * @param key_ptr        32-byte AES key
 * @param key_len        Must be 32
 * @param nonce_ptr      12-byte GCM nonce
 * @param nonce_len      Must be 12
 * @param ciphertext_ptr Pointer to ciphertext + 16-byte tag
 * @param ciphertext_len Length of ciphertext + tag in bytes
 * @param aad_ptr        Pointer to AAD (must match encryption AAD)
 * @param aad_len        Length of AAD in bytes
 * @param out_buf        Buffer to receive decrypted plaintext (must be >= ciphertext_len - 16)
 * @param out_buf_len    Size of out_buf in bytes
 * @param out_written    Pointer to size_t receiving number of bytes written
 * @return RXC_OK on success, or RXC_ERR_AUTH_FAILED if tampered.
 */
int32_t rxc_aes_gcm_decrypt(
    const uint8_t *key_ptr,
    size_t key_len,
    const uint8_t *nonce_ptr,
    size_t nonce_len,
    const uint8_t *ciphertext_ptr,
    size_t ciphertext_len,
    const uint8_t *aad_ptr,
    size_t aad_len,
    uint8_t *out_buf,
    size_t out_buf_len,
    size_t *out_written
);

/**
 * Constant-time XOR for Dual-Key Split reconstruction (FileKey = ClientShare ^ ServerShare).
 */
int32_t rxc_xor_shares(
    const uint8_t *share_a_ptr,
    const uint8_t *share_b_ptr,
    size_t len,
    uint8_t *out_buf
);

#ifdef __cplusplus
}
#endif

#endif /* RAPIDXCLOUDDRIVE_H */
