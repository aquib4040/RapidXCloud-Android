//! aes_gcm.rs — AES-256-GCM Authenticated Encryption with Associated Data (AEAD)
//! NIST SP 800-38D compliant. Enforces fresh nonces and mandatory authentication.

use crate::error::{CryptoError, Result};
use aes_gcm::aead::{Aead, KeyInit, Payload};
use aes_gcm::{Aes256Gcm, Key, Nonce};
use zeroize::Zeroize;

pub const KEY_LEN: usize = 32;
pub const NONCE_LEN: usize = 12;
pub const TAG_LEN: usize = 16;

/// Generates fresh cryptographically secure 256-bit key using OS CSPRNG.
pub fn generate_key() -> Result<[u8; KEY_LEN]> {
    let mut key = [0u8; KEY_LEN];
    getrandom::getrandom(&mut key).map_err(|_| CryptoError::EntropyError)?;
    Ok(key)
}

/// Generates fresh cryptographically secure 96-bit GCM nonce using OS CSPRNG.
pub fn generate_nonce() -> Result<[u8; NONCE_LEN]> {
    let mut nonce = [0u8; NONCE_LEN];
    getrandom::getrandom(&mut nonce).map_err(|_| CryptoError::EntropyError)?;
    Ok(nonce)
}

/// Encrypts plaintext using AES-256-GCM with optional Additional Authenticated Data (AAD).
/// Returns: ciphertext with 16-byte authentication tag appended.
pub fn encrypt(key: &[u8], nonce: &[u8], plaintext: &[u8], aad: Option<&[u8]>) -> Result<Vec<u8>> {
    if key.len() != KEY_LEN {
        return Err(CryptoError::InvalidParameter("AES-256 key must be exactly 32 bytes"));
    }
    if nonce.len() != NONCE_LEN {
        return Err(CryptoError::InvalidParameter("GCM nonce must be exactly 12 bytes"));
    }

    let cipher_key = Key::<Aes256Gcm>::from_slice(key);
    let cipher = Aes256Gcm::new(cipher_key);
    let gcm_nonce = Nonce::from_slice(nonce);

    let payload = Payload {
        msg: plaintext,
        aad: aad.unwrap_or(&[]),
    };

    let ciphertext_and_tag = cipher
        .encrypt(gcm_nonce, payload)
        .map_err(|_| CryptoError::AuthenticationFailed)?;

    Ok(ciphertext_and_tag)
}

/// Decrypts and authenticates ciphertext using AES-256-GCM.
/// Fails safely if ciphertext, tag, nonce, or AAD has been manipulated.
/// Plaintext is NEVER returned if authentication fails.
pub fn decrypt(
    key: &[u8],
    nonce: &[u8],
    ciphertext_and_tag: &[u8],
    aad: Option<&[u8]>,
) -> Result<Vec<u8>> {
    if key.len() != KEY_LEN {
        return Err(CryptoError::InvalidParameter("AES-256 key must be exactly 32 bytes"));
    }
    if nonce.len() != NONCE_LEN {
        return Err(CryptoError::InvalidParameter("GCM nonce must be exactly 12 bytes"));
    }
    if ciphertext_and_tag.len() < TAG_LEN {
        return Err(CryptoError::AuthenticationFailed);
    }

    let cipher_key = Key::<Aes256Gcm>::from_slice(key);
    let cipher = Aes256Gcm::new(cipher_key);
    let gcm_nonce = Nonce::from_slice(nonce);

    let payload = Payload {
        msg: ciphertext_and_tag,
        aad: aad.unwrap_or(&[]),
    };

    let plaintext = cipher
        .decrypt(gcm_nonce, payload)
        .map_err(|_| CryptoError::AuthenticationFailed)?;

    Ok(plaintext)
}

/// Helper wrapper that zeroes memory on drop
pub struct SecureKey {
    bytes: [u8; KEY_LEN],
}

impl SecureKey {
    pub fn new(raw: [u8; KEY_LEN]) -> Self {
        Self { bytes: raw }
    }

    pub fn as_bytes(&self) -> &[u8] {
        &self.bytes
    }
}

impl Drop for SecureKey {
    fn drop(&mut self) {
        self.bytes.zeroize();
    }
}
